package dev.fathony.electron.gradle

import com.github.gradle.node.NodeExtension
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

/** The Node.js version the conventions use unless told otherwise, from the version catalog. */
const val DEFAULT_NODE_VERSION = ElectronVersions.NODE

/**
 * Applies the node-gradle plugin with Node [version] downloaded into `~/.gradle/nodejs` and npm run in [projectDir].
 * The repository declares Node.js's download site in its settings, which allow no project repositories. With
 * [lockedInstall], dependencies are installed with `npm ci`: exactly the committed lock file, or a failure if it is
 * out of date, since npm packages run their own install scripts on the build machine.
 */
internal fun Project.useNode(version: Provider<String>, projectDir: Provider<Directory>, lockedInstall: Boolean = false) {
    plugins.apply("com.github.node-gradle.node")
    extensions.configure(NodeExtension::class.java) {
        download.set(true)
        this.version.set(version)
        // Null makes the plugin add no repository of its own.
        distBaseUrl.set(null as String?)
        workDir.set(layout.dir(provider { gradle.gradleUserHomeDir.resolve("nodejs") }))
        npmWorkDir.set(layout.dir(provider { gradle.gradleUserHomeDir.resolve("npm") }))
        nodeProjectDir.set(projectDir)
        if (lockedInstall) npmInstallCommand.set("ci")
    }
}
