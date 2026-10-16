// The library a sidecar is written with, added to sidecars by the sidecar plugin as
// dev.fathony.electron:electron-sidecar: runSidecar starts a Ktor server on a loopback port that checks the shell's
// token on its API and rendered pages, gives clients for the other sidecars (peer), and does the rest of the shell's
// contract.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
}

group = "dev.fathony.electron"
version = libs.versions.electron.get()

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

/**
 * Credentials come from `reposiliteUsername` and `reposilitePassword`, set by the release workflow.
 */
publishing {
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
    repositories {
        maven("https://maven.fathony.dev/releases") {
            name = "reposilite"
            credentials(PasswordCredentials::class)
        }
    }
}

dependencies {
    // Sidecars write Ktor routes and use Ktor clients, so Ktor is part of this library's API.
    api(libs.ktor.server.core)
    api(libs.ktor.server.sse)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.status.pages)
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
}

tasks.test {
    useJUnitPlatform()
}
