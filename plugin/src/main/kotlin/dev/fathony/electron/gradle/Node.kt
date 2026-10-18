package dev.fathony.electron.gradle

import com.github.gradle.node.NodeExtension
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** The Node.js version the conventions use unless told otherwise, from the version catalog. */
const val DEFAULT_NODE_VERSION = ElectronVersions.NODE

/**
 * Applies the node-gradle plugin with Node [version] downloaded into `~/.gradle/electron-jvm/node/<version>` and npm run
 * in [projectDir]. Each version has a folder of its own because node-gradle empties its folder of every Node before it
 * unpacks one: in a folder shared with other Node downloads, such as Kotlin/Wasm's in `~/.gradle/nodejs`, or with
 * another version, each setup would delete the other's.
 * node-gradle adds Node.js's download site to the project's repositories itself, so a consuming build declares
 * nothing for it. With [lockedInstall], dependencies are installed with `npm ci`: exactly the committed lock file, or
 * a failure if it is out of date, since npm packages run their own install scripts on the build machine.
 */
internal fun Project.useNode(version: Provider<String>, projectDir: Provider<Directory>, lockedInstall: Boolean = false) {
    plugins.apply("com.github.node-gradle.node")
    extensions.configure(NodeExtension::class.java) {
        download.set(true)
        this.version.set(version)
        val home = gradle.gradleUserHomeDir.resolve("electron-jvm")
        workDir.set(layout.dir(version.map { home.resolve("node/$it") }))
        npmWorkDir.set(layout.dir(provider { home.resolve("npm") }))
        nodeProjectDir.set(projectDir)
        if (lockedInstall) npmInstallCommand.set("ci")
    }
}
