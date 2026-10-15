// The Gradle plugins, published as dev.fathony.electron:electron-gradle-plugin plus a marker artifact per plugin id.
plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "dev.fathony.electron"
version = libs.versions.electron.get()

/**
 * Credentials come from `reposiliteUsername` and `reposilitePassword`, set by the release workflow.
 */
publishing {
    repositories {
        maven("https://maven.fathony.dev/releases") {
            name = "reposilite"
            credentials(PasswordCredentials::class)
        }
    }
}

dependencies {
    // The plugins apply and configure the node-gradle plugin.
    implementation(plugin(libs.plugins.node.gradle))
}

/**
 * A plugin alias from the catalog as a dependency on its plugin marker artifact.
 */
fun plugin(alias: Provider<PluginDependency>): Provider<String> =
    alias.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

gradlePlugin {
    plugins {
        register("shell") {
            id = "dev.fathony.electron.shell"
            implementationClass = "dev.fathony.electron.gradle.shell.ElectronShellPlugin"
            displayName = "Electron shell"
            description = "An Electron app that ships one Java runtime for its JVM sidecars, with run, live reload and package."
        }
        register("sidecar") {
            id = "dev.fathony.electron.sidecar"
            implementationClass = "dev.fathony.electron.gradle.sidecar.ElectronSidecarPlugin"
            displayName = "Electron sidecar"
            description = "A JVM module that runs as a sidecar of an Electron shell, optionally with an npm-built page."
        }
    }
}

/**
 * Versions the plugins use, kept in sync with the catalog: the sidecar library is added at the plugins' own version,
 * since the two implement one contract, and Node.js, Electron and `@electron/packager` are the extensions' defaults.
 */
val generateElectronVersions =
    tasks.register("generateElectronVersions") {
        val electron = libs.versions.electron
        val nodejs = libs.versions.nodejs
        val electronApp = libs.versions.electronApp
        val electronPackager = libs.versions.electronPackager
        inputs.property("electron", electron)
        inputs.property("nodejs", nodejs)
        inputs.property("electronApp", electronApp)
        inputs.property("electronPackager", electronPackager)
        val outputDir = layout.buildDirectory.dir("generated/source/electronVersions")
        outputs.dir(outputDir)

        doLast {
            val file = outputDir.get().file("dev/fathony/electron/gradle/ElectronVersions.kt").asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
            |package dev.fathony.electron.gradle
            |
            |internal object ElectronVersions {
            |    const val ELECTRON = "${electron.get()}"
            |    const val NODE = "${nodejs.get()}"
            |    const val ELECTRON_APP = "${electronApp.get()}"
            |    const val PACKAGER = "${electronPackager.get()}"
            |}
            |
                """.trimMargin(),
            )
        }
    }

kotlin.sourceSets.main {
    kotlin.srcDir(generateElectronVersions)
}
