// The navigation as the window's title bar, as on the React page, and this page's own menu: the shell's bridge
// (window.electronShell) used without the npm client, since this page has no npm build. Outside the app it does
// nothing, and the navigation stays a plain bar.
const shell = window.electronShell;
const nav = document.querySelector(".nav");

if (shell?.titleBar === "integrated" && nav) {
  const mac = shell.platform === "darwin";
  nav.classList.add("title-bar", mac ? "mac" : "overlay");
  if (!mac) {
    // The window shows no menu bar, so the bar opens the menu.
    const button = document.createElement("button");
    button.type = "button";
    button.className = "menu-button";
    button.textContent = "☰";
    button.setAttribute("aria-label", "Menu");
    button.addEventListener("click", () => {
      const box = button.getBoundingClientRect();
      void shell.popUpMenu(box.left, box.bottom);
    });
    nav.prepend(button);
    void shell.setTitleBarColors({ color: "#ffffff", symbolColor: "#333333", height: 44 });
  }
}

if (shell) {
  // While this page shows, the window's menu is this one: each sidecar's page sets its own.
  const pages = [
    { id: "sidecar://sample/page/", label: "React Page" },
    { id: "sidecar://sample/page/editor", label: "Editor" },
  ];
  void shell.setMenu([
    { label: "Go", submenu: [{ id: "reload", label: "Server Pages", accelerator: "CmdOrCtrl+R" }, { type: "separator" }, ...pages] },
    { role: "editMenu" },
    { role: "viewMenu" },
  ]);
  shell.onMenuClick((id) => {
    if (id === "reload") location.reload();
    else location.href = id;
  });
}
