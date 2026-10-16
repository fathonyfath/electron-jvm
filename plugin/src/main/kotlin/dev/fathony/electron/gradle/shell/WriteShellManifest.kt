package dev.fathony.electron.gradle.shell

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes `shell.json`, which tells `main.ts` which sidecar serves the window: `{"ui"}`. Fails the build when
 * [uiSidecar] names none of the sidecars, which would otherwise only show as the app quitting at launch.
 */
@DisableCachingByDefault(because = "Writes one small file; faster than a cache lookup")
abstract class WriteShellManifest : DefaultTask() {
    @get:Input
    abstract val uiSidecar: Property<String>

    /** The `sidecar.json` of every sidecar the shell carries. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sidecarManifests: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val ui = uiSidecar.get()
        val names = sidecarManifests.files.mapNotNull { NAME.find(it.readText())?.groupValues?.get(1) }.sorted()
        if (ui !in names) {
            val known = if (names.isEmpty()) "the shell has no sidecars; add one with sidecar(...)" else "its sidecars are ${names.joinToString()}"
            throw GradleException("electronShell { uiSidecar = \"$ui\" } names no sidecar of this shell: $known")
        }
        output.get().asFile.writeText("{\"ui\": \"$ui\"}\n")
    }

    private companion object {
        val NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
    }
}
