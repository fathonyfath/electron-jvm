dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
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

rootProject.name = "electron-gradle-plugin"
