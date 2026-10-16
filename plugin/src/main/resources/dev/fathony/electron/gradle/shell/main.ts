// Electron's main process: starts the sidecars, opens the UI sidecar's page, and stops them all on quit. In
// development it also restarts a sidecar when Gradle rebuilds it.
// Electron runs this file as it is (Node strips the types), so it uses only erasable syntax: no enums or namespaces.
import { app, BrowserWindow, shell } from "electron";
import { spawn, type ChildProcessByStdio } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import type { Readable, Writable } from "node:stream";

/** `sidecars/<name>/sidecar.json`, written by the sidecar plugin. */
interface SidecarManifest {
  name: string;
  mainClass: string;
  modules: string[];
}

/** `shell.json`: which sidecar serves the window. */
interface ShellManifest {
  ui: string;
}

type SidecarProcess = ChildProcessByStdio<Writable, Readable, null>;

interface RunningSidecar {
  child: SidecarProcess;
  port: number;
}

// Packaged, the desktop folder is in the app's resources; in development Gradle passes it.
const desktopDir = app.isPackaged ? path.join(process.resourcesPath, "desktop") : developmentDesktopDir();
const sidecarsDir = path.join(desktopDir, "sidecars");
const java = path.join(desktopDir, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");

/** The per-launch secret every sidecar checks. */
const token = crypto.randomBytes(32).toString("hex");

const sidecars = new Map<string, RunningSidecar>();
/** Sidecars restarted on purpose, whose exit must not end the app. */
const restarting = new Set<string>();
let window: BrowserWindow | null = null;
let uiName = "";
let quitting = false;

function developmentDesktopDir(): string {
  const dir = process.env.DESKTOP_DIR;
  if (!dir) throw new Error("DESKTOP_DIR is not set; start the app with the shell plugin's run task");
  return dir;
}

function readJson<T>(file: string): T {
  return JSON.parse(fs.readFileSync(file, "utf8")) as T;
}

function startSidecar(name: string): Promise<void> {
  const dir = path.join(sidecarsDir, name);
  const { mainClass } = readJson<SidecarManifest>(path.join(dir, "sidecar.json"));
  const child = spawn(java, ["-cp", path.join(dir, "lib", "*"), mainClass], { stdio: ["pipe", "pipe", "inherit"] });

  // The token goes on stdin, not in the environment, which other processes can read. With it goes the sidecar's own
  // data folder, inside the app's.
  const data = path.join(app.getPath("userData"), "sidecars", name);
  send(child, { type: "token", token, data });

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
      // Losing a sidecar ends the app, unless it is restarting or the app is quitting.
      if (!restarting.has(name) && !quitting) {
        console.log(`[${name}] exited with ${code}`);
        app.quit();
      }
    });
  });
}

function stopSidecar(name: string): Promise<void> {
  const running = sidecars.get(name);
  sidecars.delete(name);
  if (!running) return Promise.resolve();
  // The others forget its port first, so no token goes to a port it no longer holds.
  sendPeers();
  return new Promise((resolve) => {
    running.child.once("exit", () => resolve());
    // Closing stdin tells the sidecar to exit.
    running.child.stdin.end();
  });
}

/** One JSON line on a sidecar's stdin. */
function send(child: SidecarProcess, message: object): void {
  if (child.stdin.writable) child.stdin.write(JSON.stringify(message) + "\n");
}

function ports(): Record<string, number> {
  return Object.fromEntries([...sidecars].map(([name, { port }]) => [name, port]));
}

// Every sidecar gets all ports, again whenever a restart moves one.
function sendPeers(): void {
  const peers = ports();
  for (const { child } of sidecars.values()) send(child, { type: "peers", peers });
}

function uiOrigin(): string {
  const ui = sidecars.get(uiName);
  if (!ui) throw new Error(`the UI sidecar ${uiName} is not running`);
  return `http://127.0.0.1:${ui.port}`;
}

// The page gets the token and the sidecars' ports in the fragment, which never reaches a server.
function pageUrl(): string {
  const fragment = new URLSearchParams({ token, sidecars: JSON.stringify(ports()) });
  return `${uiOrigin()}/page/#${fragment}`;
}

// Only web and mail links leave the app; other schemes (file:, ones that start programs) are refused.
function openOutside(url: string): void {
  const protocol = URL.canParse(url) ? new URL(url).protocol : null;
  if (protocol === "https:" || protocol === "http:" || protocol === "mailto:") {
    void shell.openExternal(url);
  } else {
    console.warn(`refused to open ${url}`);
  }
}

function openWindow(): void {
  const opened = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 640,
    minHeight: 400,
    webPreferences: { contextIsolation: true, sandbox: true },
  });
  // The window shows only the UI sidecar; anything else opens in the system browser.
  opened.webContents.setWindowOpenHandler(({ url }) => {
    openOutside(url);
    return { action: "deny" };
  });
  opened.webContents.on("will-navigate", (event, url) => {
    if (!url.startsWith(uiOrigin())) {
      event.preventDefault();
      openOutside(url);
    }
  });
  void opened.loadURL(pageUrl());
  window = opened;
}

// Development only: Gradle rebuilds continuously; a sidecar restarts once its folder has been quiet for a moment, so a
// half-written build never starts.
function watchForChanges(watchCommand: string): void {
  const [command, ...args] = JSON.parse(watchCommand) as string[];
  const gradle = spawn(command, args, { stdio: ["ignore", "inherit", "inherit"] });
  app.on("will-quit", () => gradle.kill());

  const pending = new Map<string, NodeJS.Timeout>();
  fs.watch(sidecarsDir, { recursive: true }, (_event, file) => {
    const name = file?.split(path.sep)[0];
    if (!name || !sidecars.has(name)) return;
    clearTimeout(pending.get(name));
    pending.set(name, setTimeout(() => void restart(name), 500));
  });
}

async function restart(name: string): Promise<void> {
  if (restarting.has(name) || quitting) return;
  restarting.add(name);
  console.log(`[${name}] changed, restarting`);
  try {
    await stopSidecar(name);
    await startSidecar(name);
    sendPeers();
    void window?.loadURL(pageUrl());
  } catch (error) {
    // A broken build: the sidecar stays down until the next change fixes it.
    console.error((error as Error).message);
  } finally {
    restarting.delete(name);
  }
}

// One instance: a second launch focuses the first window.
if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (!window) return;
    if (window.isMinimized()) window.restore();
    window.focus();
  });

  app.whenReady().then(async () => {
    try {
      uiName = readJson<ShellManifest>(path.join(desktopDir, "shell.json")).ui;
      await Promise.all(fs.readdirSync(sidecarsDir).map(startSidecar));
      sendPeers();
      openWindow();
      const watchCommand = process.env.DESKTOP_WATCH;
      if (!app.isPackaged && watchCommand) watchForChanges(watchCommand);
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
