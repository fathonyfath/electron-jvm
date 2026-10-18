// Electron's main process: starts the sidecars and stops them on quit, and shows their pages as sidecar://<name>/…,
// forwarding each request to the sidecar's port with the token. Fixed origins keep the window's storage. With an
// integrated title bar the page draws the title bar, and sets the app's menu and the window buttons' colors through
// preload.cjs's bridge.
// Electron runs this file as it is (Node strips the types), so it uses only erasable syntax.
import { app, BrowserWindow, ipcMain, Menu, nativeTheme, protocol, shell } from "electron";
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
  titleBar?: TitleBar;
}

/** `native`: the system's title bar and menu bar. `integrated`: the page draws the title bar; see openWindow. */
type TitleBar = "native" | "integrated";

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
let titleBar: TitleBar = "native";
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
// Chromium sends sidecar:// no Sec-Fetch-Site or Origin, so forward cannot tell which frame sent a request, and adds
// the token to all of them. A second policy on every response keeps frames to sidecars' pages, whatever a page allows.
const FRAMES_POLICY = `frame-src ${SCHEME}:`;

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
  responseHeaders.append("Content-Security-Policy", FRAMES_POLICY);
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

// The window: with an integrated title bar, on macOS the window buttons sit over the page's top left and the menu is the
// screen's menu bar; on Windows and Linux Electron draws the window buttons over the page's top right (the window
// controls overlay, which the page finds as CSS env(titlebar-area-*)) and the menu bar is hidden, the page opening the
// menu itself. The page makes its title bar draggable with CSS (-webkit-app-region).
function openWindow(): void {
  const integrated = titleBar === "integrated";
  const mac = process.platform === "darwin";
  const frame: Electron.BrowserWindowConstructorOptions = !integrated
    ? {}
    : mac
      ? { titleBarStyle: "hiddenInset" }
      : { titleBarStyle: "hidden", titleBarOverlay: { height: TITLE_BAR_HEIGHT } };
  const opened = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 640,
    minHeight: 400,
    ...frame,
    webPreferences: {
      contextIsolation: true,
      sandbox: true,
      preload: path.join(import.meta.dirname, "preload.cjs"),
      additionalArguments: [`--electron-shell-title-bar=${titleBar}`],
    },
  });
  showMenu(opened);
  opened.on("focus", () => showMenu(opened));
  // A new page, or the same one reloaded: the old page's menu goes.
  opened.webContents.on("did-navigate", () => {
    menus.delete(opened);
    showMenu(opened);
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

// The window buttons' overlay height until the page sets its own, in CSS pixels.
const TITLE_BAR_HEIGHT = 40;

// What the window's page may ask of the shell (preload.cjs): the page the window shows, whichever sidecar serves it, in
// its main frame, never a frame inside it.
function fromPage(event: Electron.IpcMainInvokeEvent): boolean {
  return (
    window !== null &&
    event.sender === window.webContents &&
    event.senderFrame === window.webContents.mainFrame &&
    sidecarNamed(event.senderFrame.url) !== undefined
  );
}

// The menu is the page's: each window keeps the one its page set, shown while the window is focused (macOS has one menu
// bar for the app; Windows and Linux a menu per window). A page that navigates or reloads takes its menu with it, and
// the window has the default one until the new page sets its own, so no menu sends clicks to a page that is gone.
const menus = new WeakMap<BrowserWindow, Electron.Menu>();

function defaultMenu(): Electron.Menu {
  const mac = process.platform === "darwin";
  return Menu.buildFromTemplate([
    ...(mac ? [{ role: "appMenu" } as const] : []),
    { role: "fileMenu" },
    { role: "editMenu" },
    { role: "viewMenu" },
    { role: "windowMenu" },
  ]);
}

function showMenu(owner: BrowserWindow): void {
  const menu = menus.get(owner) ?? defaultMenu();
  if (process.platform === "darwin") {
    Menu.setApplicationMenu(menu);
  } else {
    owner.setMenu(menu);
    // Its shortcuts work hidden; the page opens it with popUpMenu.
    if (titleBar === "integrated") owner.setMenuBarVisibility(false);
  }
}

const MENU_ROLES = new Set([
  "undo", "redo", "cut", "copy", "paste", "pasteAndMatchStyle", "delete", "selectAll", "reload", "forceReload",
  "toggleDevTools", "resetZoom", "zoomIn", "zoomOut", "togglefullscreen", "window", "minimize", "close", "help",
  "about", "services", "hide", "hideOthers", "unhide", "quit", "front", "zoom", "appMenu", "fileMenu", "editMenu",
  "viewMenu", "windowMenu",
]);
const MENU_TYPES = new Set(["normal", "separator", "checkbox", "radio", "submenu"]);
const MENU_DEPTH = 4;

// The page's menu, checked item by item, since it comes from a page: each item an id the page hears about when it is
// clicked, or a role Electron carries out, with a label, a shortcut (accelerator), checked and enabled.
function menuTemplate(value: unknown, page: Electron.WebContents, depth = 0): Electron.MenuItemConstructorOptions[] {
  if (!Array.isArray(value) || depth >= MENU_DEPTH) throw new Error(`a menu is an array of items, at most ${MENU_DEPTH} deep`);
  return value.map((raw: unknown) => {
    if (typeof raw !== "object" || raw === null) throw new Error("a menu item is an object");
    const item = raw as Record<string, unknown>;
    const text = (key: string): string | undefined => {
      const field = item[key];
      if (field !== undefined && typeof field !== "string") throw new Error(`a menu item's ${key} is a string`);
      return field;
    };
    const flag = (key: string): boolean | undefined => {
      const field = item[key];
      if (field !== undefined && typeof field !== "boolean") throw new Error(`a menu item's ${key} is true or false`);
      return field;
    };
    const option: Electron.MenuItemConstructorOptions = {};
    const type = text("type");
    if (type !== undefined) {
      if (!MENU_TYPES.has(type)) throw new Error(`no menu item type ${type}`);
      option.type = type as Electron.MenuItemConstructorOptions["type"];
    }
    const role = text("role");
    if (role !== undefined) {
      if (!MENU_ROLES.has(role)) throw new Error(`no menu role ${role}`);
      option.role = role as Electron.MenuItemConstructorOptions["role"];
    }
    const label = text("label");
    if (label !== undefined) option.label = label;
    const accelerator = text("accelerator");
    if (accelerator !== undefined) option.accelerator = accelerator;
    const enabled = flag("enabled");
    if (enabled !== undefined) option.enabled = enabled;
    const checked = flag("checked");
    if (checked !== undefined) option.checked = checked;
    if (item.submenu !== undefined) option.submenu = menuTemplate(item.submenu, page, depth + 1);
    const id = text("id");
    if (id !== undefined) {
      option.id = id;
      option.click = () => {
        if (!page.isDestroyed()) page.send("electron-shell:menu-click", id);
      };
    }
    return option;
  });
}

const COLOR = /^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$/;

function handlePageRequests(): void {
  ipcMain.handle("electron-shell:set-menu", (event, items: unknown) => {
    if (!fromPage(event) || !window) throw new Error("only the window's page sets the menu");
    const template = menuTemplate(items, event.sender);
    // macOS's first menu is the app's own, named after it.
    if (process.platform === "darwin" && template[0]?.role !== "appMenu") template.unshift({ role: "appMenu" });
    menus.set(window, Menu.buildFromTemplate(template));
    showMenu(window);
  });
  ipcMain.handle("electron-shell:pop-up-menu", (event, at: unknown) => {
    if (!fromPage(event) || !window) throw new Error("only the window's page opens the menu");
    const { x, y } = (at ?? {}) as { x?: unknown; y?: unknown };
    if (typeof x !== "number" || typeof y !== "number") throw new Error("the menu opens at numbers x and y");
    (menus.get(window) ?? defaultMenu()).popup({ window, x: Math.round(x), y: Math.round(y) });
  });
  ipcMain.handle("electron-shell:title-bar-colors", (event, colors: unknown) => {
    if (!fromPage(event) || !window) throw new Error("only the window's page colors the title bar");
    // Only Windows and Linux draw the window buttons over the page.
    if (titleBar !== "integrated" || process.platform === "darwin") return;
    const { color, symbolColor, height } = (colors ?? {}) as { color?: unknown; symbolColor?: unknown; height?: unknown };
    const overlay: Electron.TitleBarOverlay = {};
    if (color !== undefined) {
      if (typeof color !== "string" || !COLOR.test(color)) throw new Error("the title bar's color is #rrggbb or #rrggbbaa");
      overlay.color = color;
    }
    if (symbolColor !== undefined) {
      if (typeof symbolColor !== "string" || !COLOR.test(symbolColor)) throw new Error("the symbols' color is #rrggbb or #rrggbbaa");
      overlay.symbolColor = symbolColor;
    }
    if (height !== undefined) {
      if (typeof height !== "number" || height < 20 || height > 100) throw new Error("the title bar is 20 to 100 pixels tall");
      overlay.height = Math.round(height);
    }
    window.setTitleBarOverlay(overlay);
  });

  // The app's theme: its native parts (frames, menus, dialogs) and the pages' prefers-color-scheme. It stays until a
  // page sets another, so a page that does not care keeps the last one.
  ipcMain.handle("electron-shell:set-theme", (event, theme: unknown) => {
    if (!fromPage(event)) throw new Error("only the window's page sets the theme");
    if (theme !== "system" && theme !== "light" && theme !== "dark") throw new Error("the theme is system, light or dark");
    nativeTheme.themeSource = theme;
  });
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
      const manifest = readJson<ShellManifest>(path.join(desktopDir, "shell.json"));
      uiName = manifest.ui;
      titleBar = manifest.titleBar ?? "native";
      names = fs.readdirSync(sidecarsDir);
      await Promise.all(names.map(startSidecar));
      sendPeers();
      protocol.handle(SCHEME, forward);
      handlePageRequests();
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
