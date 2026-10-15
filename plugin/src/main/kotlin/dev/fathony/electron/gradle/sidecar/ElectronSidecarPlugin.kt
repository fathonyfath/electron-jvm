package dev.fathony.electron.gradle.sidecar

import com.github.gradle.node.npm.task.NpmTask
import dev.fathony.electron.gradle.DEFAULT_NODE_VERSION
import dev.fathony.electron.gradle.ElectronVersions
import dev.fathony.electron.gradle.useNode
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition
import org.gradle.api.attributes.Usage
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaToolchainService

/**
 * Makes a JVM module a sidecar of the Electron shell. `installSidecar` lays out `build/sidecar/<project name>/`
 * with `lib/` (the jar and its runtime classpath) and `sidecar.json`, published as the `sidecarDistribution` variant
 * (Usage `electron-sidecar`) that the shell collects. No runtime is included: the shell ships one for all sidecars.
 *
 * `electronSidecar { page("web") }` adds the UI sidecar's page: the npm project in `web/`, installed with `npm ci` from
 * its lock file and built by `buildPage` into the jar's resources under `web/`, so the continuous build of live reload
 * rebuilds it on every change as well. Development builds pass `PAGE_SOURCE_MAPS=true`.
 *
 * The contract at run time: the shell runs `<mainClass>` on its `runtime/bin/java` with every jar in `lib/` on the
 * class path, writes JSON lines to its stdin (the token first, then the other sidecars' ports), reads
 * `SIDECAR_READY <port>` from its stdout, and closes stdin when the sidecar should exit.
 */
class ElectronSidecarPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        lateinit var sidecar: ElectronSidecarExtension
        val onPage: (String) -> Unit = { directory -> configurePage(project, sidecar, directory) }
        sidecar = project.extensions.create("electronSidecar", ElectronSidecarExtension::class.java, onPage)
        sidecar.modules.convention(emptyList())
        sidecar.nodeVersion.convention(DEFAULT_NODE_VERSION)
        sidecar.library.convention(LIBRARY)

        project.plugins.withId("java") {
            project.dependencies.addProvider("implementation", sidecar.library)
            val toolchain = project.extensions.getByType(JavaPluginExtension::class.java).toolchain
            val jdk = project.extensions.getByType(JavaToolchainService::class.java).launcherFor(toolchain)
            val isWindows = System.getProperty("os.name").startsWith("Windows")
            val manifest = project.tasks.register("sidecarManifest", WriteSidecarManifest::class.java) {
                sidecarName.set(project.name)
                mainClass.set(sidecar.mainClass)
                extraModules.set(sidecar.modules)
                jars.from(project.tasks.named("jar"), project.configurations.named("runtimeClasspath"))
                jdeps.set(jdk.map { it.metadata.installationPath.file(if (isWindows) "bin/jdeps.exe" else "bin/jdeps").asFile.absolutePath })
                release.set(jdk.map { it.metadata.languageVersion.asInt() })
                output.set(project.layout.buildDirectory.file("sidecar-manifest/sidecar.json"))
            }
            val distribution = project.layout.buildDirectory.dir("sidecar")
            val install = project.tasks.register("installSidecar", Sync::class.java) {
                description = "Lays out the sidecar under build/sidecar/${project.name}: lib/ and sidecar.json."
                // The whole folder is synced, so nothing stale survives next to the sidecar's own directory.
                into(distribution)
                from(project.tasks.named("jar")) { into("${project.name}/lib") }
                from(project.configurations.named("runtimeClasspath")) { into("${project.name}/lib") }
                from(manifest) { into(project.name) }
            }
            project.configurations.consumable("sidecarDistribution") {
                attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, USAGE))
                outgoing.artifact(distribution) {
                    type = ArtifactTypeDefinition.DIRECTORY_TYPE
                    builtBy(install)
                }
            }
        }
    }

    private fun configurePage(project: Project, sidecar: ElectronSidecarExtension, directory: String) {
        project.useNode(sidecar.nodeVersion, project.provider { project.layout.projectDirectory.dir(directory) }, lockedInstall = true)
        val output = project.layout.buildDirectory.dir("page")
        // Source maps only in development (a requested `run`, or -Pelectron.development from live reload), so a
        // packaged app carries no page sources.
        val development = project.gradle.startParameter.taskNames.any { it == "run" || it.endsWith(":run") } ||
            project.providers.gradleProperty("electron.development").isPresent
        val buildPage = project.tasks.register("buildPage", NpmTask::class.java) {
            description = "Builds the page, the npm project in $directory/, into build/page."
            dependsOn(project.tasks.named("npmInstall"))
            args.set(listOf("run", "build"))
            environment.put("PAGE_OUT", output.map { it.asFile.absolutePath })
            environment.put("PAGE_SOURCE_MAPS", development.toString())
            inputs.property("sourceMaps", development)
            inputs.files(project.fileTree(directory) { exclude("node_modules/**", "dist/**") })
            outputs.dir(output)
        }
        project.plugins.withId("java") {
            project.tasks.named("processResources", Copy::class.java) {
                from(buildPage) { into(directory) }
            }
        }
    }

    companion object {
        /** The Usage attribute the shell asks for. */
        const val USAGE = "electron-sidecar"

        /** The library at the plugin's own version, which a build containing the library substitutes. */
        const val LIBRARY = "dev.fathony.electron:electron-sidecar:${ElectronVersions.ELECTRON}"
    }
}
