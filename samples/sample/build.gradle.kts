// The sample's UI sidecar: the React app in web/ and its API.
import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.fathony.electron.sidecar")
}

kotlin {
    jvmToolchain(21)
}

electronSidecar {
    mainClass = "dev.fathony.electron.sample.MainKt"
    // The React and Monaco app in ./web, built with Vite into the jar's page/ resources.
    npmProject("web")
}

// Vite's build does not type-check, so `check` runs tsc as well.
val typecheckPage by tasks.registering(NpmTask::class) {
    description = "Type-checks the page in web/."
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "typecheck"))
}

tasks.named("check") {
    dependsOn(typecheckPage)
}
