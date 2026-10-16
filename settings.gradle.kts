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
    // The samples get their repositories from the plugins, as any consuming build does.
    repositories {
        mavenCentral()
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
include(":samples:htmx")
