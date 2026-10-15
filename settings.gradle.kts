rootProject.name = "electron-jvm"

pluginManagement {
    // The Gradle plugins (dev.fathony.electron.shell, dev.fathony.electron.sidecar), built from source.
    includeBuild("plugin")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Downloads Temurin for the shared runtime when it is not installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Node.js for npm, which the plugins configure node-gradle to take from here: with repositories in settings
        // only, the plugin may not add its own.
        ivy {
            name = "Node.js"
            setUrl("https://nodejs.org/dist/")
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

/**
 * Substituted for `dev.fathony.electron:electron-sidecar`, which the sidecar plugin adds to sidecars.
 */
includeBuild("library")

include(":samples:shell")
include(":samples:sample")
include(":samples:worker")
