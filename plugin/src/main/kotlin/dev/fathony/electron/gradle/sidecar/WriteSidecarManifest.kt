package dev.fathony.electron.gradle.sidecar

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

/**
 * Writes `sidecar.json`, the sidecar's half of its contract with the shell: `{"name", "mainClass", "modules"}`. The
 * modules are what `jdeps` finds the sidecar's jars use, plus [extraModules], since the shell links one runtime from
 * the union of its sidecars' modules and a missing one only shows when its class is first loaded.
 */
@DisableCachingByDefault(because = "Its inputs are paths on this machine, and jdeps is quick to rerun")
abstract class WriteSidecarManifest : DefaultTask() {
    @get:Input
    abstract val sidecarName: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    /** Modules `jdeps` cannot see, such as ones only loaded by reflection. */
    @get:Input
    abstract val extraModules: ListProperty<String>

    /** The sidecar's jar and its runtime classpath. */
    @get:Classpath
    abstract val jars: ConfigurableFileCollection

    /** `bin/jdeps` of the sidecar's toolchain. */
    @get:Input
    abstract val jdeps: Property<String>

    /** The Java release the jars target, for multi-release jars. */
    @get:Input
    abstract val release: Property<Int>

    @get:OutputFile
    abstract val output: RegularFileProperty

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun write() {
        val classpath = jars.files.joinToString(java.io.File.pathSeparator) { it.absolutePath }
        val out = ByteArrayOutputStream()
        execOperations.exec {
            executable(jdeps.get())
            args("--ignore-missing-deps", "--multi-release", release.get().toString(), "--print-module-deps", "--class-path", classpath)
            args(jars.files.map { it.absolutePath })
            standardOutput = out
        }
        // The last line is the comma-separated list; jdeps prints warnings before it.
        val found = out.toString().trim().lines().last().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val modules = (found + extraModules.get()).distinct().sorted().joinToString(", ") { "\"$it\"" }
        output.get().asFile.writeText(
            "{\"name\": \"${sidecarName.get()}\", \"mainClass\": \"${mainClass.get()}\", \"modules\": [$modules]}\n",
        )
    }
}
