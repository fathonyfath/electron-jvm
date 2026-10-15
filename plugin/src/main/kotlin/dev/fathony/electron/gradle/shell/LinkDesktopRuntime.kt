package dev.fathony.electron.gradle.shell

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Links the Java runtime the sidecars share with `jlink`, with the union of the modules their `sidecar.json` files
 * declare.
 */
@DisableCachingByDefault(because = "Its inputs are paths on this machine, and jlink is quick to rerun")
abstract class LinkDesktopRuntime : DefaultTask() {
    /** `bin/jlink` of the JDK to link from. */
    @get:Input
    abstract val jlink: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifests: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun link() {
        val declared = manifests.files.flatMap { file ->
            val list = MODULES.find(file.readText())?.groupValues?.get(1).orEmpty()
            QUOTED.findAll(list).map { it.groupValues[1] }.toList()
        }
        val modules = (listOf("java.base") + declared).distinct().joinToString(",")
        val out = output.get().asFile
        // jlink refuses to write into an existing directory.
        out.deleteRecursively()
        execOperations.exec {
            executable(jlink.get())
            args("--add-modules", modules, "--strip-debug", "--no-header-files", "--no-man-pages", "--output", out.absolutePath)
        }
        // jlink writes some files read-only, which would stop the next sync from replacing its copies.
        out.walk().forEach { it.setWritable(true, true) }
    }

    private companion object {
        val MODULES = Regex("\"modules\"\\s*:\\s*\\[([^]]*)]")
        val QUOTED = Regex("\"([^\"]+)\"")
    }
}
