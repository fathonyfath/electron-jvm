# electron-gradle-plugin

Gradle plugins for Electron apps with JVM sidecars. The shell owns the platform: Electron, and one Java runtime that
all sidecars share, linked from Temurin 21 with the union of the Java modules they use (found by `jdeps` in each
sidecar's jars).

```kotlin
plugins {
    id("dev.fathony.electron.shell") version "0.1.0"    // the Electron app
    id("dev.fathony.electron.sidecar") version "0.1.0"  // a JVM program it runs
}
```

The plugins are published to [maven.fathony.dev](https://maven.fathony.dev), which a consuming build names in
`pluginManagement`; the [root README](../README.md#installation) has the settings. The sidecar plugin adds the library,
`dev.fathony.electron:electron-sidecar`, at its own version, and declares the repositories it resolves from on the
project: Maven Central, and `maven.fathony.dev` for the `dev.fathony.electron` group only (`-PelectronRepository=<url>`
overrides it). node-gradle adds Node.js's download site the same way. Project repositories make Gradle ignore the
ones in settings, and `RepositoriesMode.FAIL_ON_PROJECT_REPOS` refuses them.

| Plugin | What it sets up | Tasks it adds |
|---|---|---|
| `dev.fathony.electron.sidecar` | The sidecar library, `sidecar.json` from `jdeps`, and optionally a page built with npm | `installSidecar`, `buildPage` |
| `dev.fathony.electron.shell` | The shared runtime, the generated Electron app, development with live reload, packaging | `assembleDesktop`, `run`, `package` |

## Sidecar

A sidecar is an ordinary JVM program with a `main`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.fathony.electron.sidecar")
}

electronSidecar {
    mainClass = "com.example.MainKt"
    // modules = listOf(...) only for Java modules jdeps cannot see, such as ones loaded by reflection
}
```

A sidecar whose page is built with npm names the npm project's folder; Gradle installs it, builds it with
`npm run build` and puts the output in the jar's `page/` resources:

```kotlin
electronSidecar {
    mainClass = "com.example.MainKt"
    npmProject("web")
}
```

Its `main` declares Ktor routes; `runSidecar` starts the server on a free loopback port, requires the token on
everything under `/api` and on the pages it renders, turns a handler's exception into a 500 with its message, and does
the rest of the contract with the shell:

```kotlin
fun main() {
    runSidecar {
        page()   // the npm build (resources/page) at /page
        api {
            post("reverse") { call.respondText(call.receiveText().reversed()) }
            sse("events") { send(ServerSentEvent(data = "hello", event = "greeting")) }
        }
    }
}
```

The window reaches each sidecar as `sidecar://<name>/…`, and the shell adds the token to every request it forwards,
so the page's code holds no token: `fetch("/api/reverse")`, `new EventSource("/api/events")`, links, forms, iframes,
module imports and HTMX all reach `/api` as they are. A page links to another sidecar's pages with a plain
`<a href="sidecar://other/page/">`, and back the same way.

For an npm page, the plugin also writes `electron-sidecar-client` into the page's folder, as `.sidecar-client`, before
`npm ci`: the page's side of the library's conventions, versioned with the plugin, so every sidecar's page shares one
copy. It carries its own `.gitignore`. The page depends on it with the same `file:` dependency wherever its folder
sits:

```json
"dependencies": {
  "electron-sidecar-client": "file:.sidecar-client"
}
```

```ts
import { ApiError, call, electronShell, listen } from "electron-sidecar-client";

const reversed = await call("reverse", "olleh");   // POST /api/reverse; a failure throws ApiError(status, message)
const stop = listen("events", "tick", (n) => console.log(n));   // EventSource on /api/events
const shell = electronShell();   // the window's menu and title bar, or null outside the app (see Title bar and menu)
```

Run `./gradlew <sidecar>:pageClient` once before the first `npm install` in the page's folder, so the package exists.

The page can be files built by npm, pages the sidecar renders in Kotlin, or both. Rendered pages are routes under
`/page`, such as Ktor's HTML builder with HTMX, and take the token like the API:

```kotlin
runSidecar {
    page {
        staticResources("assets", "assets")   // htmx.min.js and styles, as files: no inline scripts
        get("/") { call.respondHtml { /* … */ } }
        post("todos") { call.respondHtml { /* the fragment HTMX swaps in */ } }
    }
    api { /* … */ }
}
```

Each kind of route has its own prefix, so none can shadow another:

| Prefix | What | Token |
|---|---|---|
| `/page` | `page()`: the page's files, and `index.html` for any other path under it, so a single-page app's routes survive a reload | no |
| `/page` | `page { }`: pages the sidecar renders; a route wins over a file of the same path | yes |
| `/api` | `api { }` | yes |

Any other path is a 404. A built page is built for its prefix: its asset URLs start with `/page/` (Vite's
`base: "/page/"`) and its router's base path is `/page` (wouter's `<Router base="/page">`).

Every page gets a Content-Security-Policy: scripts from this sidecar's files only, never inline or through `eval`.
`Sidecar.contentSecurityPolicy(wasm = true)` lets a page compile WebAssembly, `inlineStyles = true` allows `<style>`
elements and `style` attributes, and `frames = true` lets the sidecar's own pages show its pages in an iframe. With
HTMX, leave out `hx-on` and its event filters, which evaluate code, and turn off its indicator styles
(`<meta name="htmx-config" content='{"includeIndicatorStyles": false}'>`) or allow inline styles.

`dataDirectory` is the folder a sidecar keeps its files in across launches: `sidecars/<name>` in the app's data folder,
which Electron names after `appName` (`~/Library/Application Support/<appName>` on macOS, `%APPDATA%\<appName>` on
Windows, `~/.config/<appName>` on Linux).

Sidecars call each other through `peer(name)`, a Ktor client that adds the token and sends each request to the peer's
current port, waiting up to ten seconds for a peer that has not started yet:

```kotlin
runSidecar {
    val worker = peer("worker")
    api {
        post("shout") { call.respondText(worker.post("/api/shout") { setBody(call.receiveText()) }.bodyAsText()) }
    }
}
```

`electronSidecar { library = ... }` takes the library as any dependency notation, for a build that wants another
version. A build that includes the library's build (`includeBuild("library")`, as this repo does) gets it substituted
without setting anything.

## Shell

The shell lists its sidecars as dependencies and names the one whose page the window shows:

```kotlin
plugins {
    id("dev.fathony.electron.shell")
}

electronShell {
    appName = "My App"
    uiSidecar = "app"
    // titleBar is "native" or "integrated" (see Title bar and menu)
    // javaVersion, nodeVersion, electronVersion and packagerVersion have defaults (see Versions)
}

dependencies {
    sidecar(projects.app)
    sidecar(projects.worker)
}
```

The runtime and the sidecars are laid out under `<shell>/build/desktop`, and the app carries the same folder
as `Resources/desktop`:

```
desktop/
  runtime/              the shared Java runtime
  sidecars/<name>/      lib/ (the sidecar's jars) and sidecar.json (name, main class, modules)
  shell.json            which sidecar serves the UI, and the title bar's mode
```

### Title bar and menu

By default (`titleBar = "native"`) the window has the system's title bar and menu bar, and the menu is Electron's
default (File, Edit, View, Window) until the page sets its own. With `titleBar = "integrated"` the page draws the title
bar, as editors do, and the window has no other row above it:

- **macOS:** the window buttons sit over the page's top left (`titleBarStyle: hiddenInset`), and the menu is the
  screen's menu bar, as on every Mac app.
- **Windows and Linux:** Electron draws the window buttons over the page's top right (its window controls overlay),
  in colors the page sets, and hides the menu bar. Its shortcuts still work; the page opens the menu itself, from a
  button in its title bar, as a native drop-down (`popUpMenu`). A Linux desktop with a global menu bar, such as KDE's,
  may show the menu there as well.

The page then makes its title bar draggable with CSS, and leaves room for the window buttons: on Windows and Linux
Chromium tells it where they are not, as `env(titlebar-area-x)`, `env(titlebar-area-width)` and
`env(titlebar-area-height)`; on macOS they take about the first 80 pixels.

```css
.title-bar { -webkit-app-region: drag; padding-right: calc(100vw - env(titlebar-area-x, 0px) - env(titlebar-area-width, 100vw)); }
.title-bar button { -webkit-app-region: no-drag; }
```

**The menu belongs to the page the window shows,** whichever sidecar serves it: the page sets it, the shell builds it
into a native menu, and clicks come back to the page. When the window navigates or reloads, the menu goes with the
page and the window has the default one until the new page sets its own, so a menu never sends clicks to a page that
is gone. Each window keeps its own page's menu: Windows and Linux have a menu per window, and macOS's menu bar shows the
focused window's. Only the page in the window's main frame reaches the shell, never a frame inside it, and the shell
checks every item: an `id` the page hears about, or a `role` Electron carries out (`undo`, `copy`, `paste`,
`toggleDevTools`, `quit`, `editMenu`…), with a `label`, an `accelerator`, `checked`, `enabled` and a `submenu`. On macOS
the app's own menu comes first, added when the page leaves it out.

```ts
import { electronShell } from "electron-sidecar-client";

const shell = electronShell();   // null outside the app's window, such as in a browser
if (shell) {
  await shell.setMenu([
    { label: "File", submenu: [{ id: "open", label: "Open…", accelerator: "CmdOrCtrl+O" }, { type: "separator" }, { role: "quit" }] },
    { role: "editMenu" },
    { label: "View", submenu: [{ id: "sidebar", label: "Sidebar", type: "checkbox", checked: true }, { role: "toggleDevTools" }] },
  ]);
  shell.onMenuClick((id) => console.log(`clicked ${id}`));
  menuButton.onclick = () => shell.popUpMenu(menuButton.offsetLeft, menuButton.offsetTop + menuButton.offsetHeight);
  await shell.setTitleBarColors({ color: "#ffffff", symbolColor: "#1b1f24", height: 44 });   // Windows and Linux only
  await shell.setTheme("dark");   // or "light", or "system"
}
```

The page sets the menu again whenever what it shows changes (a checked item, a disabled one); it is cheap.

A page with its own light and dark setting tells the shell with `setTheme`, so the app's native parts match it: on
macOS the menus, context menus and window buttons; on Windows the window frames and menus; and on every platform
Electron's own dialogs and every page's `prefers-color-scheme`, which `system` leaves to the system. The theme is the
app's, not the page's: it stays when the page navigates, until a page sets another. On Linux the menus, the desktop's
window decorations and its global menu bar follow the desktop's theme, as every app's there do.

### Files dragged in and out

A file dropped on the page arrives as the web's `File`, its contents, which a page can send to its sidecar as it is;
since Electron 32 it carries no path. When the sidecar needs the file where it is (a folder to open in place, say),
`pathForFile` gives its absolute path; it is null for a file that is not on disk. Dragging out works the other way:
in a `dragstart` handler the page calls `startDrag` with absolute paths, and the files themselves go to the desktop or
another app, a plain page icon under the cursor. A drop that the page does not take does nothing: Chromium would open
the file as a page, and the window shows only sidecars' pages.

```ts
const shell = electronShell();
zone.addEventListener("dragover", (event) => event.preventDefault());
zone.addEventListener("drop", (event) => {
  event.preventDefault();
  for (const file of event.dataTransfer?.files ?? []) console.log(shell?.pathForFile(file) ?? file.name);
});
row.draggable = true;
row.addEventListener("dragstart", (event) => {
  event.preventDefault();
  shell?.startDrag("/path/to/the/file.png");
});
```

### Versions

The defaults are the versions a plugin release was tested with: Java 21, and Node.js, Electron and
`@electron/packager` from this repo's [version catalog](../gradle/libs.versions.toml). They are kept current on `main`
and ship with whichever release comes next; a new Electron or Node.js alone does not make a release.

- **Electron** is in the app you ship, and only its latest three majors get security fixes, about six months each.
  Pin it in your build rather than relying on the plugin's default, and keep it updated there.
- **Node.js** only runs the build (npm, the page's bundler, the packager); Electron carries its own. The default
  follows an LTS line, and changes major before that line's end of life.
- **`@electron/packager`** is called by the plugin's own packaging script, so leave it at the default unless a
  release says otherwise.

With the version in your catalog, annotated with where it comes from, a regex manager in your `renovate.json` lets
[Renovate](https://docs.renovatebot.com/) keep it updated:

```toml
# gradle/libs.versions.toml
[versions]
# renovate: datasource=npm depName=electron
electronApp = "43.7.7"
```

```kotlin
electronShell {
    electronVersion = libs.versions.electronApp
}
```

```json
{
  "customManagers": [{
    "customType": "regex",
    "managerFilePatterns": ["/^gradle/libs\\.versions\\.toml$/"],
    "matchStrings": ["# renovate: datasource=(?<datasource>\\S+) depName=(?<depName>\\S+)\\n(?<propName>\\S+) = \"(?<currentValue>[^\"]+)\""]
  }]
}
```

This repo does the same for its defaults. Its [renovate.json](../renovate.json) groups updates into a weekly PR
each and merges patches and minors once the checks, which package the sample with them, pass; Kotlin, Ktor and
logback, which reach every app through the library, and every major wait for review.

## How the Shell Runs Its Sidecars

At launch the shell starts every sidecar as its own process on `runtime/bin/java`, writes it the per-launch token as
the first JSON line on its stdin (not in the environment, which other processes of the same user can read), waits for
`SIDECAR_READY <port>` on its stdout, tells each sidecar the others' ports with a further line, and opens a window on
the UI sidecar's `sidecar://<ui>/page/`.

The window never talks to a port. The shell registers the `sidecar://` scheme and forwards each request for
`sidecar://<name>/…` to that sidecar's current port, adding `Authorization: Bearer <token>`; sidecars send each other
the same header through `peer`. Any local process can reach a loopback port, and the token is how sidecars tell the
app's requests apart, while page scripts never see it. Each sidecar's origin, `sidecar://<name>`, is the same every
launch, so the window's storage (localStorage, IndexedDB) persists, and a sidecar restarting on a new port changes
no URL. The window shows only sidecars' pages: other navigations and redirects open in the system browser. Sidecars
refuse requests whose `Host` is not their own loopback address, which keeps out a web page that has pointed its domain
at 127.0.0.1. The page gets a Content-Security-Policy that allows scripts, styles and requests only from its sidecar,
so scripts and styles live in their own files. Links to `https`, `http` and `mailto` open in the system browser;
other schemes are refused. Quitting closes every sidecar's stdin, which ends it, and losing any sidecar ends the app.

In development, `run` also keeps Gradle's continuous build of the desktop folder running: saving a sidecar's Kotlin
or page rebuilds it, and the app restarts that sidecar and reloads the window, a second or two after the save.

Packaging builds for the platform it runs on, because the runtime comes from that platform's JDK. Apps are
unsigned: macOS needs a Developer ID and notarization and Windows a code-signing certificate before distribution.

## Development

This plugin and `library/` are included builds of this repo, so the samples always build against their current
source. To publish both locally:

```bash
./gradlew publishToMavenLocal
```

To release, run the [Bump Version](../.github/workflows/bump.yml) workflow and pick `patch`, `minor` or `major`:

```bash
gh workflow run bump.yml -f bump=minor                         # from main
gh workflow run bump.yml -f bump=patch --ref release/1.x      # patch an older major
```

It takes the latest `v*` tag on the branch it runs on, runs the checks, publishes the plugins and
`electron-sidecar` at the next version, then creates the tag and a GitHub Release. Run it on `main` for new
releases, or on a `release/<major>.x` branch to patch an older major. A version can only be published once.
Publishing needs the `REPOSILITE_USERNAME` and `REPOSILITE_TOKEN` repository secrets.

The plugins and the library always share one version: the catalog's `electron` (a SNAPSHOT), replaced by
`-Pelectron.version=<version>` on release. The plugin build writes it, with the default Node.js, Electron and
`@electron/packager` versions (the catalog's `nodejs`, `electronApp` and `electronPackager`), into the generated
`ElectronVersions`. Renovate updates those three on `main` (see [Versions](#versions)); they need no release of
their own.

### The Electron app

The Electron app's code (`main.ts`, `preload.cjs`, `package.mjs`) ships with the shell plugin, under
`src/main/resources/dev/fathony/electron/gradle/shell/`; edit it there. The plugin writes it, with a `package.json`
pinning Electron (`electronShell { electronVersion }`) and `@electron/packager`, into `<shell>/build/electron-app`
(for the sample, `samples/shell/build/electron-app`), where npm runs. `main.ts` ships as it is: Electron's Node strips
its types, so it uses only syntax that erases. `preload.cjs`, the page's way to the shell (`window.electronShell`),
is plain CommonJS, since a sandboxed preload can be neither an ES module nor TypeScript. `tsconfig.json` in this folder takes Electron's types from the sample's
generated app, so the IDE resolves `electron` once `./gradlew :samples:shell:npmInstall` has run. To work on it with
IntelliJ IDEA's JavaScript and Node.js support (IntelliJ IDEA Ultimate):

- **Node interpreter:** Settings, Languages & Frameworks, Node.js: point it at the Node the build downloaded,
  `~/.gradle/nodejs/node-v<nodejs>-<os>-<arch>/bin/node` (`<nodejs>` from the catalog), so the IDE and Gradle use the
  same one.
- **Debugging the main process:** run `./gradlew :samples:shell:assembleDesktop :samples:shell:npmInstall`, then a
  Node.js run configuration with the interpreter `samples/shell/build/electron-app/node_modules/.bin/electron`, the
  working directory `samples/shell/build/electron-app` and the environment
  `DESKTOP_DIR=<repo>/samples/shell/build/desktop` stops at breakpoints in the generated `main.ts`. The page itself
  is debugged with Chromium's DevTools (View, Toggle Developer Tools in the app's menu).
