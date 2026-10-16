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
 * `electronSidecar { npmProject("web") }` installs the npm project in `web/` with `npm ci` and builds it (`buildPage`)
 * into the jar's `page/` resources, which live reload rebuilds too; development builds pass `PAGE_SOURCE_MAPS=true`.
 * `pageClient` writes the page's client package first ([WritePageClient]).
 *
 * The plugin declares the repositories a sidecar resolves from, so a consuming build only names the plugins'
 * repository in `pluginManagement`: Maven Central, and `maven.fathony.dev` for `dev.fathony.electron` only, which the
 * `electronRepository` property overrides. Project repositories make Gradle ignore the ones in settings.
 *
 * The contract at run time: the shell runs `<mainClass>` on its `runtime/bin/java` with every jar in `lib/` on the
 * class path, writes JSON lines to its stdin (the token first, then the other sidecars' ports), reads
 * `SIDECAR_READY <port>` from its stdout, and closes stdin when the sidecar should exit.
 */
class ElectronSidecarPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        lateinit var sidecar: ElectronSidecarExtension
        val onNpmProject: (String) -> Unit = { directory -> configureNpmProject(project, sidecar, directory) }
        sidecar = project.extensions.create("electronSidecar", ElectronSidecarExtension::class.java, onNpmProject)
        sidecar.modules.convention(emptyList())
        sidecar.nodeVersion.convention(DEFAULT_NODE_VERSION)
        sidecar.library.convention(LIBRARY)

        project.repositories.mavenCentral()
        project.repositories.maven {
            name = "electron"
            setUrl(project.findProperty("electronRepository") ?: REPOSITORY)
            mavenContent { includeGroup("dev.fathony.electron") }
        }

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

    private fun configureNpmProject(project: Project, sidecar: ElectronSidecarExtension, directory: String) {
        project.useNode(sidecar.nodeVersion, project.provider { project.layout.projectDirectory.dir(directory) }, lockedInstall = true)
        val pageClient = project.tasks.register("pageClient", WritePageClient::class.java) {
            description = "Writes the npm package ${WritePageClient.PACKAGE} into $directory/${WritePageClient.FOLDER}."
            output.set(project.layout.projectDirectory.dir("$directory/${WritePageClient.FOLDER}"))
        }
        // A file: dependency, so it must exist before npm installs.
        project.tasks.named("npmInstall") { dependsOn(pageClient) }
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
            inputs.files(project.fileTree(directory) { exclude("node_modules/**", "dist/**", "${WritePageClient.FOLDER}/**") })
            inputs.files(pageClient)
            outputs.dir(output)
        }
        project.plugins.withId("java") {
            project.tasks.named("processResources", Copy::class.java) {
                from(buildPage) { into(PAGE_RESOURCES) }
            }
        }
    }

    companion object {
        /** The Usage attribute the shell asks for. */
        const val USAGE = "electron-sidecar"

        /** The library's `page()` serves this folder by default. */
        const val PAGE_RESOURCES = "page"

        /** Where the library is published. */
        const val REPOSITORY = "https://maven.fathony.dev/releases"

        /** The library at the plugin's own version, which a build containing the library substitutes. */
        const val LIBRARY = "dev.fathony.electron:electron-sidecar:${ElectronVersions.ELECTRON}"
    }
}
