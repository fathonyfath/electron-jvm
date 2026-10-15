pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
            /**
             * Releases pass `-Pelectron.version=<tag>`; everything else uses the catalog's SNAPSHOT.
             */
            providers.gradleProperty("electron.version").orNull?.let { version("electron", it) }
        }
    }
}

rootProject.name = "electron-sidecar"
