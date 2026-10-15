// The sample's second sidecar, without a page: an API the UI sidecar calls as a peer.
plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.fathony.electron.sidecar")
}

kotlin {
    jvmToolchain(21)
}

electronSidecar {
    mainClass = "dev.fathony.electron.worker.MainKt"
}
