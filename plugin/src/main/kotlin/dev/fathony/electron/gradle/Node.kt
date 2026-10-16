package dev.fathony.electron.gradle

import com.github.gradle.node.NodeExtension
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** The Node.js version the conventions use unless told otherwise, from the version catalog. */
const val DEFAULT_NODE_VERSION = ElectronVersions.NODE

/**
 * Applies the node-gradle plugin with Node [version] downloaded into `~/.gradle/nodejs` and npm run in [projectDir].
 * node-gradle adds Node.js's download site to the project's repositories itself, so a consuming build declares
 * nothing for it. With [lockedInstall], dependencies are installed with `npm ci`: exactly the committed lock file, or
 * a failure if it is out of date, since npm packages run their own install scripts on the build machine.
 */
internal fun Project.useNode(version: Provider<String>, projectDir: Provider<Directory>, lockedInstall: Boolean = false) {
    plugins.apply("com.github.node-gradle.node")
    extensions.configure(NodeExtension::class.java) {
        download.set(true)
        this.version.set(version)
        workDir.set(layout.dir(provider { gradle.gradleUserHomeDir.resolve("nodejs") }))
        npmWorkDir.set(layout.dir(provider { gradle.gradleUserHomeDir.resolve("npm") }))
        nodeProjectDir.set(projectDir)
        if (lockedInstall) npmInstallCommand.set("ci")
    }
}
