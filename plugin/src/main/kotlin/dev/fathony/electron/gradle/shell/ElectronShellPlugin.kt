package dev.fathony.electron.gradle.shell

import com.github.gradle.node.npm.task.NpmTask
import dev.fathony.electron.gradle.DEFAULT_NODE_VERSION
import dev.fathony.electron.gradle.ElectronVersions
import dev.fathony.electron.gradle.useNode
import dev.fathony.electron.gradle.sidecar.ElectronSidecarPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/**
 * Makes the module an Electron app that ships one Java runtime for its sidecars. The app itself is this plugin's:
 * `generateElectronApp` writes `main.js`, `package.mjs` and a `package.json` with the versions from the extension
 * into `build/electron-app`, where npm runs, so the module holds nothing but its build script. Sidecars are added as `sidecar(project(...))` dependencies on modules that apply
 * [ElectronSidecarPlugin]; the `electronShell { }` extension ([ElectronShellExtension]) names the app and its UI
 * sidecar.
 *
 * `assembleDesktop` lays out `build/desktop`: `runtime/` (linked from Temurin with the union of the sidecars'
 * modules; a JetBrains SDK cannot be linked from, its patched jmods fail java.base's recorded hashes),
 * `sidecars/<name>/` and `shell.json`. `run` starts Electron in development with that folder as `DESKTOP_DIR`, and
 * `package` builds this platform's app with it as `Resources/desktop`. Node comes from the node-gradle plugin,
 * downloaded into `~/.gradle/nodejs`; the repository declares Node.js's download site in its settings.
 */
class ElectronShellPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("base")
        project.plugins.apply("jvm-toolchains")

        val shell = project.extensions.create("electronShell", ElectronShellExtension::class.java)
        shell.javaVersion.convention(21)
        shell.nodeVersion.convention(DEFAULT_NODE_VERSION)
        shell.electronVersion.convention(ElectronVersions.ELECTRON_APP)
        shell.packagerVersion.convention(ElectronVersions.PACKAGER)

        val app = project.layout.buildDirectory.dir("electron-app")
        val generate = project.tasks.register("generateElectronApp", GenerateElectronApp::class.java) {
            packageName.set(project.name.lowercase().replace(Regex("[^a-z0-9-]"), "-"))
            electronVersion.set(shell.electronVersion)
            packagerVersion.set(shell.packagerVersion)
            output.set(app)
        }

        project.useNode(shell.nodeVersion, app)
        project.tasks.named("npmInstall") { dependsOn(generate) }

        val sidecar = project.configurations.dependencyScope("sidecar")
        val sidecars = project.configurations.resolvable("sidecars") {
            extendsFrom(sidecar.get())
            attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(Usage::class.java, ElectronSidecarPlugin.USAGE))
        }

        val isWindows = System.getProperty("os.name").startsWith("Windows")
        val jdk = project.extensions.getByType(JavaToolchainService::class.java).launcherFor {
            languageVersion.set(shell.javaVersion.map { JavaLanguageVersion.of(it) })
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }
        val runtime = project.tasks.register("desktopRuntime", LinkDesktopRuntime::class.java) {
            description = "Links the Java runtime the sidecars share, with the modules they declare."
            jlink.set(jdk.map { it.metadata.installationPath.file(if (isWindows) "bin/jlink.exe" else "bin/jlink").asFile.absolutePath })
            manifests.from(sidecars.map { it.asFileTree.matching { include("*/sidecar.json") } })
            output.set(project.layout.buildDirectory.dir("desktop-runtime"))
            // A damaged runtime is relinked rather than copied into the app.
            outputs.upToDateWhen { LinkDesktopRuntime.isComplete(output.get().asFile) }
        }

        val manifest = project.tasks.register("desktopManifest", WriteShellManifest::class.java) {
            uiSidecar.set(shell.uiSidecar)
            sidecarManifests.from(sidecars.map { it.asFileTree.matching { include("*/sidecar.json") } })
            output.set(project.layout.buildDirectory.file("desktop-manifest/shell.json"))
        }

        val desktop = project.layout.buildDirectory.dir("desktop")
        val assemble = project.tasks.register("assembleDesktop", Sync::class.java) {
            description = "Lays out the runtime, the sidecars and shell.json under build/desktop."
            into(desktop)
            from(runtime) { into("runtime") }
            from(sidecars) { into("sidecars") }
            from(manifest)
        }
        val desktopDir = desktop.map { it.asFile.absolutePath }

        // Live reload: the app runs Gradle's continuous build of assembleDesktop alongside itself and restarts a
        // sidecar whose folder it rewrites. A second build in the same project is fine; Gradle coordinates them.
        // Electron starts it from the generated app folder, so the build's root is passed explicitly.
        val wrapper = project.rootDir.resolve(if (isWindows) "gradlew.bat" else "gradlew").absolutePath
        val watchCommand = listOf(
            wrapper, "--project-dir", project.rootDir.absolutePath, "--continuous", "--quiet", "-Pelectron.development",
            "${project.path}:assembleDesktop",
        )
        val watch = watchCommand.joinToString(", ", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }

        project.tasks.register("run", NpmTask::class.java) {
            group = "application"
            description = "Starts the Electron app in development, rebuilding and restarting sidecars as their code changes."
            dependsOn(project.tasks.named("npmInstall"), assemble)
            args.set(listOf("start"))
            environment.put("DESKTOP_DIR", desktopDir)
            environment.put("DESKTOP_WATCH", watch)
        }

        project.tasks.register("package", NpmTask::class.java) {
            group = "distribution"
            description = "Packages the Electron app for this platform, runtime and sidecars included, under build/package."
            dependsOn(project.tasks.named("npmInstall"), assemble)
            args.set(listOf("run", "package"))
            environment.put("DESKTOP_DIR", desktopDir)
            environment.put("APP_NAME", shell.appName)
            environment.put("PACKAGE_OUT", project.layout.buildDirectory.dir("package").map { it.asFile.absolutePath })
        }
    }
}
