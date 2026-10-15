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

The plugins are published to [maven.fathony.dev](https://maven.fathony.dev). The sidecar plugin adds the library,
`dev.fathony.electron:electron-sidecar`, at its own version, so the repository goes in both `pluginManagement` and
`dependencyResolutionManagement`; the [root README](../README.md#installation) has the full settings.

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
everything under `/api`, turns a handler's exception into a 500 with its message, and does the rest of the contract
with the shell:

```kotlin
fun main() {
    runSidecar {
        page("web")   // only the sidecar that serves the window: resources/web at /
        api {
            post("reverse") { call.respondText(call.receiveText().reversed()) }
            sse("events") { send(ServerSentEvent(data = "hello", event = "greeting")) }
        }
    }
}
```

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
    // javaVersion = 21, nodeVersion = "24.22.0", electronVersion = "43.7.7", packagerVersion = "20.3.0" by default
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

## How the Shell Runs Its Sidecars

At launch the shell starts every sidecar as its own process on `runtime/bin/java`, writes it the per-launch token as
the first JSON line on its stdin (not in the environment, which other processes of the same user can read), waits for
`SIDECAR_READY <port>` on its stdout, tells each sidecar the others' ports with a further line, and opens a window on
the UI sidecar's `http://127.0.0.1:<port>/`.

The page talks only to the UI sidecar, which calls the others for it. The token and every sidecar's port arrive in
the page's URL fragment. The page talks to the JVM with `fetch` and `EventSource`, sending the token as
`Authorization: Bearer` (or `?token=` for event streams), since any local process can reach a loopback port. Sidecars
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
`-Pelectron.version=<version>` on release. The plugin build writes it, with the default Node.js version, into the
generated `ElectronVersions`.

### The Electron app

The Electron app's code (`main.js`, `package.mjs`) ships with the shell plugin, under
`src/main/resources/dev/fathony/electron/gradle/shell/`; edit it there. The plugin writes it, with a `package.json`
pinning Electron (`electronShell { electronVersion }`) and `@electron/packager`, into `<shell>/build/electron-app`
(for the sample, `samples/shell/build/electron-app`), where npm runs. To work on it with IntelliJ IDEA's JavaScript
and Node.js support (IntelliJ IDEA Ultimate):

- **Node interpreter:** Settings, Languages & Frameworks, Node.js: point it at the Node the build downloaded,
  `~/.gradle/nodejs/node-v24.22.0-<os>-<arch>/bin/node`, so the IDE and Gradle use the same one.
- **Debugging the main process:** run `./gradlew :samples:shell:assembleDesktop :samples:shell:npmInstall`, then a
  Node.js run configuration with the interpreter `samples/shell/build/electron-app/node_modules/.bin/electron`, the
  working directory `samples/shell/build/electron-app` and the environment
  `DESKTOP_DIR=<repo>/samples/shell/build/desktop` stops at breakpoints in the generated `main.js`. The page itself
  is debugged with Chromium's DevTools (View, Toggle Developer Tools in the app's menu).
