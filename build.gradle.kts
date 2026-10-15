/**
 * Loads the Kotlin Gradle plugin once for all modules.
 */
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
}

/**
 * Included builds don't receive tasks run by name, so these forward them.
 */
listOf("check", "publishToMavenLocal").forEach { name ->
    tasks.register(name) {
        dependsOn(listOf("plugin", "library").map { gradle.includedBuild(it).task(":$name") })
    }
}
