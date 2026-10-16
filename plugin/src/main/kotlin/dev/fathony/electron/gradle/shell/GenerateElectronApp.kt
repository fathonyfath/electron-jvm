package dev.fathony.electron.gradle.shell

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Writes the Electron app the shell runs: `main.ts` and `package.mjs`, which ship with this plugin, and a
 * `package.json` pinning Electron and `@electron/packager` to the versions in `electronShell { }`, with the app's name
 * as `productName`, so the app's data folder has that name in development and packaged alike.
 */
@DisableCachingByDefault(because = "Writes a few files from resources; faster than a cache lookup")
abstract class GenerateElectronApp : DefaultTask() {
    @get:Input
    abstract val packageName: Property<String>

    /** The app's name, which Electron names the app's data folder after (`app.getPath("userData")`). */
    @get:Input
    abstract val productName: Property<String>

    @get:Input
    abstract val electronVersion: Property<String>

    @get:Input
    abstract val packagerVersion: Property<String>

    /** The plugin's own files, as inputs so a new plugin version regenerates the app. */
    @get:Input
    val sources: Map<String, String> = FILES.associateWith(::resource)

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = output.get().asFile
        // Only the generated files are replaced: node_modules stays, so npm does not reinstall Electron every time.
        for ((name, text) in sources) dir.resolve(name).writeText(text)
        dir.resolve("package.json").writeText(
            """
            |{
            |  "name": "${packageName.get()}",
            |  "productName": ${json(productName.get())},
            |  "private": true,
            |  "version": "1.0.0",
            |  "type": "module",
            |  "main": "main.ts",
            |  "scripts": {
            |    "start": "electron .",
            |    "package": "node package.mjs"
            |  },
            |  "devDependencies": {
            |    "@electron/packager": "${packagerVersion.get()}",
            |    "electron": "${electronVersion.get()}"
            |  }
            |}
            |""".trimMargin(),
        )
    }

    private companion object {
        val FILES = listOf("main.ts", "package.mjs")

        fun json(text: String): String =
            "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

        fun resource(name: String): String =
            checkNotNull(GenerateElectronApp::class.java.getResourceAsStream(name)) { "missing plugin resource $name" }
                .use { it.readBytes().decodeToString() }
    }
}
