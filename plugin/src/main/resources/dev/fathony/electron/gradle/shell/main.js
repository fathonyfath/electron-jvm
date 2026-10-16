// Electron's main process: starts every sidecar on the shared Java runtime, tells each the others' ports over its
// stdin, opens a window on the UI sidecar's page, and stops them all on quit. In development it also runs Gradle's continuous build of the desktop folder and
// restarts a sidecar whose files changed, then reloads the window.
const { app, BrowserWindow, shell } = require("electron");
const { spawn } = require("node:child_process");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");

// runtime/, sidecars/<name>/ and shell.json. Packaged, they sit in the app's resources; in development Gradle
// passes the folder it assembled.
const desktopDir = app.isPackaged ? path.join(process.resourcesPath, "desktop") : process.env.DESKTOP_DIR;
const sidecarsDir = path.join(desktopDir, "sidecars");
const java = path.join(desktopDir, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
const token = crypto.randomBytes(32).toString("hex");
// name -> { child, port }; a sidecar being restarted on purpose is marked so its exit does not end the app.
const sidecars = new Map();
const restarting = new Set();
let window = null;
let uiName = null;
let quitting = false;

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, "utf8"));
}

function startSidecar(name) {
  const dir = path.join(sidecarsDir, name);
  const { mainClass } = readJson(path.join(dir, "sidecar.json"));
  const child = spawn(java, ["-cp", path.join(dir, "lib", "*"), mainClass], { stdio: ["pipe", "pipe", "inherit"] });
  // The token goes first on stdin rather than in the environment, which other processes of the same user can read.
  // With it goes the folder the sidecar keeps its files in: its own one in the app's data folder (Electron's userData,
  // named after the app: ~/Library/Application Support/<app> on macOS, %APPDATA%\<app> on Windows, ~/.config/<app> on
  // Linux).
  const data = path.join(app.getPath("userData"), "sidecars", name);
  child.stdin.write(JSON.stringify({ type: "token", token, data }) + "\n");
  return new Promise((resolve, reject) => {
    readline.createInterface({ input: child.stdout }).on("line", (line) => {
      const ready = /^SIDECAR_READY (\d+)$/.exec(line);
      if (ready) {
        sidecars.set(name, { child, port: Number(ready[1]) });
        resolve();
      } else {
        console.log(`[${name}] ${line}`);
      }
    });
    child.once("error", reject);
    child.once("exit", (code) => {
      reject(new Error(`sidecar ${name} exited with ${code} before it was ready`));
      // Losing a sidecar ends the app, unless it is being restarted or the app is quitting anyway.
      if (!restarting.has(name) && !quitting) {
        console.log(`[${name}] exited with ${code}`);
        app.quit();
      }
    });
  });
}

function stopSidecar(name) {
  const running = sidecars.get(name);
  sidecars.delete(name);
  if (!running) return Promise.resolve();
  // The others forget its port first, so nothing sends the token to a port it no longer holds.
  sendPeers();
  return new Promise((resolve) => {
    running.child.once("exit", resolve);
    // Closing stdin tells the sidecar to exit.
    running.child.stdin.end();
  });
}

function ports() {
  return Object.fromEntries([...sidecars].map(([name, { port }]) => [name, port]));
}

// Every sidecar learns the others' ports from one JSON line on its stdin, sent again whenever a restart moves one.
function sendPeers() {
  const message = JSON.stringify({ type: "peers", peers: ports() }) + "\n";
  for (const { child } of sidecars.values()) {
    if (child.stdin.writable) child.stdin.write(message);
  }
}

// Links leave the app only for the web and mail; anything else (file:, custom schemes that start programs) is dropped.
function openOutside(url) {
  const protocol = (() => {
    try {
      return new URL(url).protocol;
    } catch {
      return null;
    }
  })();
  if (["https:", "http:", "mailto:"].includes(protocol)) shell.openExternal(url);
  else console.warn(`refused to open ${url}`);
}

// The UI sidecar's page, with the token and every sidecar's port in the fragment, which never reaches a server.
function pageUrl() {
  const fragment = new URLSearchParams({ token, sidecars: JSON.stringify(ports()) }).toString();
  return `http://127.0.0.1:${sidecars.get(uiName).port}/#${fragment}`;
}

function openWindow() {
  window = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 640,
    minHeight: 400,
    webPreferences: { contextIsolation: true, sandbox: true },
  });
  // The app shows only the UI sidecar's page; anything else opens in the system browser.
  window.webContents.setWindowOpenHandler(({ url }) => {
    openOutside(url);
    return { action: "deny" };
  });
  window.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith(`http://127.0.0.1:${sidecars.get(uiName).port}`)) {
      event.preventDefault();
      openOutside(url);
    }
  });
  window.loadURL(pageUrl());
}

// Development only: rebuild the desktop folder on every change (Gradle's continuous build), and restart a sidecar
// once its folder has been quiet for a moment, so a half-written rebuild is never started.
function watchForChanges() {
  const command = JSON.parse(process.env.DESKTOP_WATCH);
  const gradle = spawn(command[0], command.slice(1), { stdio: ["ignore", "inherit", "inherit"] });
  app.on("will-quit", () => gradle.kill());

  const pending = new Map();
  fs.watch(sidecarsDir, { recursive: true }, (event, file) => {
    const name = file && file.split(path.sep)[0];
    if (!name || !sidecars.has(name)) return;
    clearTimeout(pending.get(name));
    pending.set(name, setTimeout(() => restart(name), 500));
  });
}

async function restart(name) {
  if (restarting.has(name) || quitting) return;
  restarting.add(name);
  console.log(`[${name}] changed, restarting`);
  try {
    await stopSidecar(name);
    await startSidecar(name);
    sendPeers();
    window?.loadURL(pageUrl());
  } catch (error) {
    // A broken build: the sidecar stays down until the next change fixes it.
    console.error(error.message);
  } finally {
    restarting.delete(name);
  }
}

// One instance: a second launch focuses the first window instead.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (window) {
      if (window.isMinimized()) window.restore();
      window.focus();
    }
  });

  app.whenReady().then(async () => {
    try {
      uiName = readJson(path.join(desktopDir, "shell.json")).ui;
      await Promise.all(fs.readdirSync(sidecarsDir).map(startSidecar));
      sendPeers();
      openWindow();
      if (!app.isPackaged && process.env.DESKTOP_WATCH) watchForChanges();
    } catch (error) {
      console.error(error);
      app.exit(1);
    }
  });

  app.on("window-all-closed", () => app.quit());
  app.on("will-quit", () => {
    quitting = true;
    for (const { child } of sidecars.values()) child.stdin.end();
  });
}
