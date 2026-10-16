package dev.fathony.electron.gradle.sidecar

import dev.fathony.electron.gradle.ElectronVersions
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/** Writes the npm package `electron-sidecar-client` into `<page>/.sidecar-client`, at the library's version. */
@DisableCachingByDefault(because = "Writes a few files from resources; faster than a cache lookup")
abstract class WritePageClient : DefaultTask() {
    /** Inputs, so a new plugin version rewrites the package. */
    @get:Input
    val sources: Map<String, String> = FILES.associateWith(::resource)

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun write() {
        val dir = output.get().asFile
        dir.listFiles()?.forEach { it.deleteRecursively() }
        for ((name, text) in sources) dir.resolve(name).writeText(text)
        dir.resolve(".gitignore").writeText("# Written by the sidecar plugin's pageClient task.\n*\n")
        dir.resolve("package.json").writeText(
            """
            |{
            |  "name": "$PACKAGE",
            |  "version": "${ElectronVersions.ELECTRON}",
            |  "private": true,
            |  "type": "module",
            |  "exports": {
            |    ".": { "types": "./index.d.ts", "default": "./index.js" }
            |  }
            |}
            |""".trimMargin(),
        )
    }

    companion object {
        const val PACKAGE = "electron-sidecar-client"

        const val FOLDER = ".sidecar-client"
        private val FILES = listOf("index.js", "index.d.ts")

        private fun resource(name: String): String =
            checkNotNull(WritePageClient::class.java.getResourceAsStream("client/$name")) { "missing plugin resource client/$name" }
                .use { it.readBytes().decodeToString() }
    }
}
