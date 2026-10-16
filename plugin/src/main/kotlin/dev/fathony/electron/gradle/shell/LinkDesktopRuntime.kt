package dev.fathony.electron.gradle.shell

import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
 *
 * jlink writes into the task's temporary directory, and the result replaces [output] only once it is complete: an
 * interrupted link (a cancelled or failed build) leaves the previous runtime or none, never a partial one. A runtime
 * without `lib/modules`, the file holding every class, starts no JVM ("Failed setting boot class path"), so it fails
 * the task, and the shell plugin never treats such an output as up to date.
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
        // jlink refuses to write into an existing directory.
        val staging = temporaryDir.resolve("runtime")
        staging.deleteRecursively()
        execOperations.exec {
            executable(jlink.get())
            args("--add-modules", modules, "--strip-debug", "--no-header-files", "--no-man-pages", "--output", staging.absolutePath)
        }
        check(isComplete(staging)) { "jlink produced no lib/modules in $staging" }
        // jlink writes some files read-only, which would stop the next sync from replacing its copies.
        staging.walk().forEach { it.setWritable(true, true) }

        val out = output.get().asFile
        out.deleteRecursively()
        // Both directories are under the build directory, so this is a rename on one file system.
        Files.move(staging.toPath(), out.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        /**
         * Whether [runtime] is a whole linked runtime: it has `lib/modules`, which jlink writes last.
         */
        fun isComplete(runtime: java.io.File): Boolean = runtime.resolve("lib/modules").isFile

        private val MODULES = Regex("\"modules\"\\s*:\\s*\\[([^]]*)]")
        private val QUOTED = Regex("\"([^\"]+)\"")
    }
}
