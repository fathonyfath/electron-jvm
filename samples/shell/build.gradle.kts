// The sample app: the UI sidecar `sample`, the `worker` sidecar it calls, and `htmx`, pages rendered in Kotlin.
// `./gradlew :samples:shell:run` starts it in development with live reload; `./gradlew :samples:shell:package`
// builds this platform's app under build/package.
plugins {
    id("dev.fathony.electron.shell")
}

electronShell {
    appName = "Electron Sidecar Sample"
    uiSidecar = "sample"
}

dependencies {
    sidecar(projects.samples.sample)
    sidecar(projects.samples.worker)
    sidecar(projects.samples.htmx)
}
