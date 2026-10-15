// The sample app: the UI sidecar `sample` and a second sidecar `worker`.
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
}
