package dev.fathony.electron.gradle

import com.github.gradle.node.NodeExtension
import com.github.gradle.node.task.NodeSetupTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
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
 *
 * Every project of a build that uses the same Node shares its folder, so only one of them unpacks it (see
 * [unpackOncePerBuild]).
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
    unpackOncePerBuild()
}

/**
 * Leaves unpacking each Node folder to the first project of the build whose `nodeSetup` is configured for it; the
 * others disable theirs and depend on that one. node-gradle's `nodeSetup` empties its folder before it unpacks, so two
 * of them on one folder, such as a shell's and a sidecar's, deleted the Node the other project's npm was starting
 * ("A problem occurred starting process"). This is the arrangement node-gradle's maintainers suggest until it shares
 * an installation itself (node-gradle/gradle-node-plugin#61).
 *
 * The owners are kept on the build's [org.gradle.api.invocation.Gradle] in a map of JDK types: each project may load
 * this plugin in a classloader of its own, so a type of the plugin's, such as a shared build service, would not be
 * shared between them.
 */
private fun Project.unpackOncePerBuild() {
    val extra = gradle.extensions.extraProperties
    @Suppress("UNCHECKED_CAST")
    val owners = synchronized(gradle) {
        if (!extra.has(NODE_SETUP_OWNERS)) extra.set(NODE_SETUP_OWNERS, ConcurrentHashMap<String, String>())
        extra.get(NODE_SETUP_OWNERS)
    } as ConcurrentMap<String, String>
    // The folder nodeSetup empties and unpacks into.
    val workDir = extensions.getByType(NodeExtension::class.java).workDir
    tasks.named(NodeSetupTask.NAME, NodeSetupTask::class.java).configure {
        val owner = owners.putIfAbsent(workDir.get().asFile.absolutePath, path) ?: path
        if (owner != path) {
            enabled = false
            dependsOn(owner)
        }
    }
}

/**
 * The key, on the build's extra properties, of the map from each Node folder to the path of the `nodeSetup` that
 * unpacks it.
 */
private const val NODE_SETUP_OWNERS = "dev.fathony.electron.nodeSetupOwners"
