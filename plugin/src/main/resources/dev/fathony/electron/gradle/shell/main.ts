// Electron's main process: starts the sidecars and stops them on quit, and shows their pages as sidecar://<name>/…,
// forwarding each request to the sidecar's port with the token. Fixed origins keep the window's storage.
// Electron runs this file as it is (Node strips the types), so it uses only erasable syntax.
import { app, BrowserWindow, protocol, shell } from "electron";
import { spawn, type ChildProcessByStdio } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import readline from "node:readline";
import type { Readable, Writable } from "node:stream";

interface SidecarManifest {
  name: string;
  mainClass: string;
  modules: string[];
}

interface ShellManifest {
  ui: string;
}

type SidecarProcess = ChildProcessByStdio<Writable, Readable, null>;

interface RunningSidecar {
  child: SidecarProcess;
  port: number;
}

const desktopDir = app.isPackaged ? path.join(process.resourcesPath, "desktop") : developmentDesktopDir();
const sidecarsDir = path.join(desktopDir, "sidecars");
const java = path.join(desktopDir, "runtime", "bin", process.platform === "win32" ? "java.exe" : "java");
const token = crypto.randomBytes(32).toString("hex");
const SCHEME = "sidecar";

// All sidecars, running or not, so their URLs stay valid while one restarts.
let names: string[] = [];
const sidecars = new Map<string, RunningSidecar>();
// Restarted on purpose: their exit must not end the app.
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
  // On stdin, not in the environment, which other processes can read.
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

function send(child: SidecarProcess, message: object): void {
  if (child.stdin.writable) child.stdin.write(JSON.stringify(message) + "\n");
}

function sendPeers(): void {
  const peers = Object.fromEntries([...sidecars].map(([name, { port }]) => [name, port]));
  for (const { child } of sidecars.values()) send(child, { type: "peers", peers });
}

function pageUrl(): string {
  return `${SCHEME}://${uiName}/page/`;
}

function sidecarNamed(url: string): string | undefined {
  if (!URL.canParse(url)) return undefined;
  const { protocol, hostname } = new URL(url);
  if (protocol !== `${SCHEME}:`) return undefined;
  // URLs lower-case their host.
  return names.find((name) => name.toLowerCase() === hostname);
}

// Headers fetch refuses, or sets itself.
const DROPPED_HEADERS = ["host", "connection", "keep-alive", "transfer-encoding", "upgrade"];

async function forward(request: Request): Promise<Response> {
  const name = sidecarNamed(request.url);
  if (!name) return new Response("no such sidecar", { status: 404 });
  const { pathname, search } = new URL(request.url);
  const headers = new Headers(request.headers);
  for (const header of DROPPED_HEADERS) headers.delete(header);
  headers.set("Authorization", `Bearer ${token}`);
  let response: Response;
  try {
    const port = sidecars.get(name)?.port;
    if (port === undefined) throw new Error("not running");
    response = await fetch(`http://127.0.0.1:${port}${pathname}${search}`, {
      method: request.method,
      headers,
      body: request.body,
      duplex: "half",
      redirect: "manual",
    });
  } catch {
    return new Response(`sidecar ${name} is not answering`, { status: 502 });
  }
  const responseHeaders = new Headers(response.headers);
  // fetch has decoded the body already.
  responseHeaders.delete("content-encoding");
  responseHeaders.delete("content-length");
  const location = responseHeaders.get("location");
  if (location) responseHeaders.set("location", toSidecarUrl(location));
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers: responseHeaders });
}

function toSidecarUrl(location: string): string {
  if (!URL.canParse(location)) return location;
  const url = new URL(location);
  const name = [...sidecars].find(([, { port }]) => url.hostname === "127.0.0.1" && Number(url.port) === port)?.[0];
  return name ? `${SCHEME}://${name}${url.pathname}${url.search}${url.hash}` : location;
}

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
  opened.webContents.setWindowOpenHandler(({ url }) => {
    openOutside(url);
    return { action: "deny" };
  });
  const stayOnSidecars = (event: Electron.Event, url: string) => {
    if (sidecarNamed(url)) return;
    event.preventDefault();
    openOutside(url);
  };
  opened.webContents.on("will-navigate", stayOnSidecars);
  opened.webContents.on("will-redirect", stayOnSidecars);
  void opened.loadURL(pageUrl());
  window = opened;
}

// Development only: a sidecar restarts once its folder has been quiet a moment, so a half-written build never starts.
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
    window?.webContents.reload();
  } catch (error) {
    // A broken build: the sidecar stays down until the next change fixes it.
    console.error((error as Error).message);
  } finally {
    restarting.delete(name);
  }
}

// Has to run before the app is ready.
protocol.registerSchemesAsPrivileged([
  { scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true, corsEnabled: true } },
]);

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
      names = fs.readdirSync(sidecarsDir);
      await Promise.all(names.map(startSidecar));
      sendPeers();
      protocol.handle(SCHEME, forward);
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
