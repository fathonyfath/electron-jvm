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

A sidecar that serves the window also names its page, an npm project in the module, which Gradle builds with
`npm run build` into the jar's resources:

```kotlin
electronSidecar {
    mainClass = "com.example.MainKt"
    page("web")
}
```

Its `main` declares Ktor routes; `runSidecar` starts the server on a free loopback port, requires the token on
everything under `/api` and on the pages it renders, turns a handler's exception into a 500 with its message, and does
the rest of the contract with the shell:

```kotlin
fun main() {
    runSidecar {
        page("web")   // only the sidecar that serves the window: resources/web at /page
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
| `/page` | `page("web")`: the page's files, and `index.html` for any other path under it, so a single-page app's routes survive a reload | no |
| `/page` | `page { }`: pages the sidecar renders; a route wins over a file of the same path | yes |
| `/api` | `api { }` | yes |
| `/public` | `publicRoutes { }`: for requests from outside the window, such as a link opened in the system browser | no |

Any other path is a 404. A built page is built for its prefix: its asset URLs start with `/page/` (Vite's
`base: "/page/"`) and its router's base path is `/page` (wouter's `<Router base="/page">`). Public routes serve nothing
secret; an unguessable path keeps a URL to whoever was given it.

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
  shell.json            which sidecar serves the UI
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

The Electron app's code (`main.ts`, `package.mjs`) ships with the shell plugin, under
`src/main/resources/dev/fathony/electron/gradle/shell/`; edit it there. The plugin writes it, with a `package.json`
pinning Electron (`electronShell { electronVersion }`) and `@electron/packager`, into `<shell>/build/electron-app`
(for the sample, `samples/shell/build/electron-app`), where npm runs. `main.ts` ships as it is: Electron's Node strips
its types, so it uses only syntax that erases. `tsconfig.json` in this folder takes Electron's types from the sample's
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
