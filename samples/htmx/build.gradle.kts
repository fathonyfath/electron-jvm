// The sample's server-rendered sidecar: pages written in Kotlin (kotlinx.html) with HTMX, no npm build.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.fathony.electron.sidecar")
}

kotlin {
    jvmToolchain(21)
}

electronSidecar {
    mainClass = "dev.fathony.electron.htmx.MainKt"
}

dependencies {
    implementation(libs.ktor.server.html.builder)
    // htmx.min.js, served from the jar.
    runtimeOnly(libs.htmx)
}
