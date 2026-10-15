package dev.fathony.electron.gradle.sidecar

import javax.inject.Inject
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * `electronSidecar { }`: what the shell needs to start this module, and the page it serves if it is the UI sidecar.
 * The shell links one runtime for all its sidecars from the union of the Java modules they use, which `jdeps` finds
 * in their jars.
 */
abstract class ElectronSidecarExtension @Inject constructor(private val onPage: (String) -> Unit) {
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

    /**
     * Builds the npm project in [directory] (`npm run build`, with its output directory in `PAGE_OUT`) into the
     * jar's resources under the same name, for `page("<directory>")` in the sidecar's `main`.
     */
    fun page(directory: String) = onPage(directory)
}
