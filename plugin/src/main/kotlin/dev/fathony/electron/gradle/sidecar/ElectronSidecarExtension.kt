package dev.fathony.electron.gradle.sidecar

import javax.inject.Inject
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `electronSidecar { }`: what the shell needs to start this module, and the npm project it builds, if any.
 * The shell links one runtime for all its sidecars from the union of the Java modules they use, which `jdeps` finds
 * in their jars.
 */
abstract class ElectronSidecarExtension @Inject constructor(private val onNpmProject: (String) -> Unit) {
    /**
     * The sidecar library, as any dependency notation: by default `dev.fathony.electron:electron-sidecar` at the
     * plugin's own version, since the two implement one contract. A build that includes the library's build gets it
     * substituted without setting this.
     */
    abstract val library: Property<Any>

    /** The fully qualified main class (`...MainKt` for a top-level `main`). */
    abstract val mainClass: Property<String>

    /** Java modules `jdeps` cannot see in the jars, such as ones only loaded by reflection; none by default. */
    abstract val modules: ListProperty<String>

    /** The Node.js version for building the page. */
    abstract val nodeVersion: Property<String>

    /** Builds the npm project in [directory] (`npm run build` into `PAGE_OUT`) into the jar's `page/` resources. */
    fun npmProject(directory: String) = onNpmProject(directory)
}
