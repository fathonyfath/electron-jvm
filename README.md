# electron-jvm

Electron desktop apps whose logic runs in JVM sidecars: Electron draws the window, Kotlin programs serve its page and
API on loopback, and the app ships one Java runtime for all of them. Published and versioned together:

- `dev.fathony.electron.shell` and `dev.fathony.electron.sidecar`, the Gradle plugins (`plugin/`).
- `dev.fathony.electron:electron-sidecar`, the library sidecars are written with (`library/`): `runSidecar`, `page`,
  `api`, `peer`, on Ktor.

`samples/` is an app built with them: `shell` with the UI sidecar `sample` (a React and Monaco page), `worker`, and
`htmx`, pages rendered in Kotlin with HTMX; the React page and the server pages link to each other. On the React
page, Preview edits a small HTML page that `sample` keeps in its data folder (`dataDirectory`), serves from its API and
shows in an iframe (`contentSecurityPolicy(frames = true)`).

## Try the Sample

You need a JDK (17 or newer) to run Gradle. Everything else is downloaded into `~/.gradle`: Temurin 21 for the shared
runtime (through the foojay toolchain resolver), Node.js for npm, and Electron on its first start.

```bash
git clone https://github.com/fathonyfath/electron-jvm.git
cd electron-jvm
./gradlew :samples:shell:run       # the sample app in development, with live reload
./gradlew :samples:shell:package   # this platform's app under samples/shell/build/package
```

## Installation

The plugins and the library are published to [maven.fathony.dev](https://maven.fathony.dev). Name it for the
plugins in `settings.gradle.kts`; the plugins declare everything else on the projects that apply them (Maven Central,
the library's repository, and node-gradle's Node.js download site):

```kotlin
pluginManagement {
    repositories {
        maven("https://maven.fathony.dev/releases")
        gradlePluginPortal()
    }
}

plugins {
    // Downloads Temurin for the shared runtime when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

Since the plugins add project repositories, the settings must not use `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

Then a sidecar is an ordinary JVM program with a `main`, and the shell lists its sidecars:

```kotlin
// app/build.gradle.kts
plugins {
    kotlin("jvm") version "2.4.21"
    id("dev.fathony.electron.sidecar") version "0.1.0"
}

electronSidecar {
    mainClass = "com.example.MainKt"
    npmProject("web")   // a page built with npm, which page() in main serves
}
```

```kotlin
// shell/build.gradle.kts
plugins {
    id("dev.fathony.electron.shell") version "0.1.0"
}

electronShell {
    appName = "My App"
    uiSidecar = "app"
}

dependencies {
    sidecar(project(":app"))
}
```

The [plugin README](plugin/README.md) covers the options, the library's API and how the shell and its sidecars talk.

## Project Layout

```
plugin/                     The Gradle plugins, an included build, so the samples build against their source
├── src/main/kotlin/        dev.fathony.electron.shell and dev.fathony.electron.sidecar
└── src/main/resources/     The Electron app (main.ts, package.mjs) the shell plugin generates
library/                    dev.fathony.electron:electron-sidecar, an included build substituted for the published one
samples/
├── shell/                  The sample app: names its sidecars, nothing else
├── sample/                 The UI sidecar: its API in Kotlin and its page in web/
├── worker/                 A second sidecar, called by the others as a peer
└── htmx/                   Server-rendered pages: kotlinx.html and HTMX, no npm build
gradle/libs.versions.toml   Versions, including the plugins' and library's own
```

## Commands

| Command | What it does |
|---|---|
| `./gradlew :samples:shell:run` | Run the sample app; saving a sidecar's Kotlin or page restarts it and reloads the window |
| `./gradlew :samples:shell:package` | Package the sample for this platform under `samples/shell/build/package` |
| `./gradlew :samples:shell:assembleDesktop` | Lay out the shared runtime and the sidecars under `samples/shell/build/desktop` |
| `./gradlew check` | Validate the plugins and build everything |
| `./gradlew publishToMavenLocal` | Publish the plugins and the library to `~/.m2` |

## The Sample's UI

`samples/sample/web` is a React app with wouter for navigation, Zustand for state and Monaco for editing,
built by Vite:

```
web/
  index.html, package.json, vite.config.ts, tsconfig.json
  src/
    main.tsx            entry: renders App
    App.tsx             the layout and navigation, and the routes (under /page)
    pages/              one component per page: HomePage, EditorPage, PreviewPage; they call the sidecar with
                        electron-sidecar-client, which the sidecar plugin writes into web/.sidecar-client
    state/              Zustand stores, outside the pages so they survive switching them: the event log, the
                        editor's text and view states, the preview's files
    components/         CodeEditor (Monaco), Log
    monaco/setup.ts     Monaco from the npm package (no CDN), the slim entry with Kotlin, HTML and JavaScript
    styles/app.css
```

The sidecar serves it with `page(Sidecar.contentSecurityPolicy(inlineStyles = true))`: Monaco injects its
styles at run time, and scripts stay restricted to the sidecar's own files. Unknown paths fall back to `index.html`,
so the router's routes survive a reload. `npm run typecheck` in `web/` type-checks it, and so does
`./gradlew check`; Vite's build does not. Gradle installs its packages with `npm ci`, so builds use exactly `package-lock.json`; after changing
`package.json`, run `npm install` in `web/` and commit the updated lock file. Source maps are built for development
(`run`, live reload) only, so a packaged app carries no page sources.

Chromium's DevTools are in the app's View menu (Cmd+Option+I).

## Releasing

Releases of the plugins and library are cut with the [Bump Version](.github/workflows/bump.yml) workflow; see the
[plugin README](plugin/README.md#development).
