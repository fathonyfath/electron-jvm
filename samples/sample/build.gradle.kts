// The sample's UI sidecar: the React app in web/ and its API.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.fathony.electron.sidecar")
}

kotlin {
    jvmToolchain(21)
}

electronSidecar {
    mainClass = "dev.fathony.electron.sample.MainKt"
    // The React and Monaco app in ./web, built with Vite into the jar's web/ resources.
    page("web")
}
