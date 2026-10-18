import { type ReactNode, useEffect, useState } from "react";
import { electronShell } from "electron-sidecar-client";
import { Link, Route, Router, Switch, useLocation } from "wouter";
import { EditorPage } from "./pages/EditorPage";
import { FilesPage } from "./pages/FilesPage";
import { HomePage } from "./pages/HomePage";
import { PreviewPage } from "./pages/PreviewPage";

export function App() {
  return (
    <Router base="/page">
      <Layout>
        <Switch>
          <Route path="/" component={HomePage} />
          <Route path="/editor" component={EditorPage} />
          <Route path="/preview" component={PreviewPage} />
          <Route path="/files" component={FilesPage} />
          <Route>Not found</Route>
        </Switch>
      </Layout>
    </Router>
  );
}

// The window's menu, which this page sets: its pages, then Electron's own Edit and View menus. The server pages, another
// sidecar's, set none, so the window shows the default menu there.
const PAGES = [
  { id: "/", label: "Home", accelerator: "CmdOrCtrl+1" },
  { id: "/editor", label: "Editor", accelerator: "CmdOrCtrl+2" },
  { id: "/preview", label: "Preview", accelerator: "CmdOrCtrl+3" },
  { id: "/files", label: "Files", accelerator: "CmdOrCtrl+4" },
];

// The app's theme, for its native parts and the page's prefers-color-scheme.
const THEMES = ["system", "light", "dark"] as const;
type Theme = (typeof THEMES)[number];

function Layout({ children }: { children: ReactNode }) {
  const [location, navigate] = useLocation();
  const shell = electronShell();
  const [theme, setTheme] = useState<Theme>("system");

  useEffect(() => {
    if (!shell) return;
    void shell.setMenu([
      { label: "Go", submenu: PAGES.map((page) => ({ ...page, type: "radio" as const, checked: location === page.id })) },
      { role: "editMenu" },
      { role: "viewMenu" },
      {
        label: "Theme",
        submenu: THEMES.map((option) => ({ id: `theme:${option}`, label: option[0].toUpperCase() + option.slice(1), type: "radio" as const, checked: option === theme })),
      },
    ]);
  }, [shell, location, theme]);

  useEffect(() => {
    void shell?.setTheme(theme);
  }, [shell, theme]);

  useEffect(
    () =>
      shell?.onMenuClick((id) => {
        if (id.startsWith("theme:")) setTheme(id.slice("theme:".length) as Theme);
        else navigate(id);
      }),
    [shell, navigate],
  );

  // With an integrated title bar the navigation is the title bar: on Windows and Linux it opens the menu, which the
  // window does not show, and leaves room on the right for the window buttons; on macOS, room on the left.
  const integrated = shell?.titleBar === "integrated";
  const mac = shell?.platform === "darwin";
  useEffect(() => {
    if (integrated && !mac) void shell?.setTitleBarColors({ color: "#ffffff", symbolColor: "#333333", height: 44 });
  }, [shell, integrated, mac]);

  return (
    <div className="app">
      <nav className={`nav${integrated ? " title-bar" : ""}${integrated && mac ? " mac" : ""}`}>
        {integrated && !mac && (
          <button
            type="button"
            className="menu-button"
            aria-label="Menu"
            onClick={(event) => {
              const box = event.currentTarget.getBoundingClientRect();
              void shell?.popUpMenu(box.left, box.bottom);
            }}
          >
            ☰
          </button>
        )}
        <strong>Electron Sidecar Sample</strong>
        <NavLink href="/">Home</NavLink>
        <NavLink href="/editor">Editor</NavLink>
        <NavLink href="/preview">Preview</NavLink>
        <NavLink href="/files">Files</NavLink>
        <a href="sidecar://htmx/page/">Server pages</a>
      </nav>
      <main className="page">{children}</main>
    </div>
  );
}

function NavLink({ href, children }: { href: string; children: ReactNode }) {
  const [location] = useLocation();
  return (
    <Link href={href} aria-current={location === href ? "page" : undefined}>
      {children}
    </Link>
  );
}
