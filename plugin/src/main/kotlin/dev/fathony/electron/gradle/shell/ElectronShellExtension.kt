package dev.fathony.electron.gradle.shell

import org.gradle.api.provider.Property

/**
 * `electronShell { }`: the Electron app's name, which sidecar serves its window, how the window's title bar looks, and
 * the versions of the platform it ships and builds with.
 */
abstract class ElectronShellExtension {
    /** The app's display name, which also names the packaged app. */
    abstract val appName: Property<String>

    /** The name of the sidecar (its project name) whose page the window shows. */
    abstract val uiSidecar: Property<String>

    /**
     * `"native"` (the default): the window has the system's title bar and menu bar. `"integrated"`: the page draws the
     * title bar, as editors do, and the window has no other row: on macOS the window buttons sit over the page's top
     * left and the menu is the screen's menu bar; on Windows and Linux Electron draws the window buttons over the page's
     * top right and the menu bar is hidden, its menu opened from the page (`window.electronShell`). See the README.
     */
    abstract val titleBar: Property<String>

    /** The Java version of the shared runtime, linked from Temurin; sidecars must not target a newer one. */
    abstract val javaVersion: Property<Int>

    /** The Node.js version the build uses for npm and Electron's tooling. */
    abstract val nodeVersion: Property<String>

    /**
     * The Electron version the app ships. The default is the one this plugin release was tested with; an app should
     * pin its own and keep it updated, since Electron supports only its latest three majors.
     */
    abstract val electronVersion: Property<String>

    /** The `@electron/packager` version that packages it. */
    abstract val packagerVersion: Property<String>
}
