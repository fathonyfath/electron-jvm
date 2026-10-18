/** An error answer: its status and message. */
export declare class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string);
}

/** POSTs [body] to `/api/<method>` and returns the answer's text; throws [ApiError] on failure. */
export declare function call(method: string, body?: string): Promise<string>;

/** Listens to [event] on the event stream `/api/<stream>`; returns a function that stops. */
export declare function listen(stream: string, event: string, onData: (data: string) => void): () => void;

/**
 * A menu item, as the shell builds it into a native menu: an [id] the page hears about when it is clicked
 * ([ElectronShell.onMenuClick]), or a [role] Electron carries out (`undo`, `copy`, `toggleDevTools`, `quit`…); a
 * [label], a shortcut as an Electron accelerator (`CmdOrCtrl+Enter`), and a [submenu] up to four deep.
 */
export interface MenuItem {
  id?: string;
  label?: string;
  role?: string;
  type?: "normal" | "separator" | "checkbox" | "radio" | "submenu";
  accelerator?: string;
  enabled?: boolean;
  checked?: boolean;
  submenu?: MenuItem[];
}

/**
 * The shell, to the page the window shows (its main frame only): the platform (`darwin`, `win32`, `linux`), the title
 * bar's mode (`electronShell { titleBar }`), the window's menu, and the colors of the window buttons it draws over the
 * page on Windows and Linux with an integrated title bar.
 */
export interface ElectronShell {
  readonly platform: string;
  readonly titleBar: "native" | "integrated";
  /** Makes [items] the window's menu, until the page navigates or reloads. On macOS the app's own menu comes first. */
  setMenu(items: MenuItem[]): Promise<void>;
  /** Opens the window's menu at [x], [y] in the page, such as under a menu button. */
  popUpMenu(x: number, y: number): Promise<void>;
  /** Calls [listener] with an item's id when it is clicked; returns a function that stops. */
  onMenuClick(listener: (id: string) => void): () => void;
  /** The window buttons' background, symbols and height, on Windows and Linux with an integrated title bar. */
  setTitleBarColors(colors: { color?: string; symbolColor?: string; height?: number }): Promise<void>;
  /**
   * The app's theme, for its native parts (window frames, menus, dialogs) and every page's `prefers-color-scheme`:
   * the system's, or light or dark whatever the system's is. It stays until a page sets another.
   */
  setTheme(theme: "system" | "light" | "dark"): Promise<void>;
  /**
   * Where a [file] dropped on the page, or picked in a file input, is on disk: its absolute path, or null for a file
   * that is not on disk (made by the page, or dragged from a browser). A dropped folder is a File too, and has its path.
   */
  pathForFile(file: File): string | null;
  /**
   * Drags the files at [paths], absolute paths, out of the window as the files themselves, to the desktop or another
   * app. Call it from a `dragstart` handler, after `event.preventDefault()`. Paths that do not exist cancel the drag.
   */
  startDrag(paths: string | string[]): void;
}

/** The shell, or null when the page is not in its window, such as in a browser. */
export declare function electronShell(): ElectronShell | null;
