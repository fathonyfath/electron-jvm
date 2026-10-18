// The window's page's way to the shell, as `window.electronShell` (see the sidecar page client's types): the platform,
// the title bar's mode, the app's menu and theme, and the window buttons' colors. It runs in the page's main frame only, isolated
// from the page and sandboxed, so it is CommonJS and uses only what a sandboxed preload has.
const { contextBridge, ipcRenderer } = require("electron");

const titleBar = process.argv.find((arg) => arg.startsWith("--electron-shell-title-bar="))?.split("=")[1] ?? "native";

contextBridge.exposeInMainWorld("electronShell", {
  platform: process.platform,
  titleBar,
  setMenu: (items) => ipcRenderer.invoke("electron-shell:set-menu", items),
  popUpMenu: (x, y) => ipcRenderer.invoke("electron-shell:pop-up-menu", { x, y }),
  onMenuClick: (listener) => {
    const handler = (_event, id) => listener(id);
    ipcRenderer.on("electron-shell:menu-click", handler);
    return () => ipcRenderer.off("electron-shell:menu-click", handler);
  },
  setTitleBarColors: (colors) => ipcRenderer.invoke("electron-shell:title-bar-colors", colors),
  setTheme: (theme) => ipcRenderer.invoke("electron-shell:set-theme", theme),
});
