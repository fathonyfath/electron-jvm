package dev.fathony.electron.sidecar

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What a sidecar sets up in [runSidecar], as plain Ktor routes, each kind under its own prefix: the page at `/page`,
 * the API at `/api`, token-free routes at `/public`. Any other path is a 404.
 */
class Sidecar internal constructor(
    private val routing: Routing,
    private val token: SidecarToken,
    private val peers: Peers,
    /** Where this sidecar keeps files across launches: `sidecars/<name>` in the app's data folder. It exists. */
    val dataDirectory: Path,
) {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    /**
     * Serves the classpath resources under [resources] at `/page`, without the token, with `index.html` for any path
     * that is no file so a single-page app's routes survive a reload. Build the page for that prefix (Vite's `base`).
     */
    fun page(resources: String, contentSecurityPolicy: String = contentSecurityPolicy()) {
        routing.staticResources(PAGE, resources) {
            default("index.html")
            modify { _, call -> call.response.headers.append("Content-Security-Policy", contentSecurityPolicy) }
        }
    }

    /** Routes under `/api`, each requiring the token. */
    fun api(routes: Route.() -> Unit) {
        routing.authenticate(AUTHENTICATION) {
            route(API, routes)
        }
    }

    /**
     * Routes under `/public` without the token, for what a page loads by URL and so cannot send it with: an ES module's
     * imports, a WebAssembly file. Any local program can read them, so serve nothing secret.
     */
    fun publicRoutes(routes: Route.() -> Unit) {
        routing.route(PUBLIC, routes)
    }

    /**
     * A client for the sidecar called [name]: `post("/api/…")` goes to its current port with the token, waiting up to
     * ten seconds for it to start.
     */
    fun peer(name: String): HttpClient = clients.getOrPut(name) {
        HttpClient(CIO) {
            install(
                createClientPlugin("SidecarPeer") {
                    onRequest { request, _ ->
                        request.url.protocol = URLProtocol.HTTP
                        request.url.host = "127.0.0.1"
                        request.url.port = peers.port(name)
                        request.headers[HttpHeaders.Authorization] = token.bearer()
                    }
                },
            )
        }
    }

    companion object {
        internal const val AUTHENTICATION = "sidecar-token"
        private const val PAGE = "/page"
        private const val API = "/api"
        private const val PUBLIC = "/public"

        /**
         * A Content-Security-Policy for a page: everything from this sidecar only, no inline scripts, no framing.
         * [inlineStyles] allows inline styles (Monaco needs them), [wasm] allows WebAssembly, and [frames] lets this
         * sidecar's own pages frame it.
         */
        fun contentSecurityPolicy(inlineStyles: Boolean = false, wasm: Boolean = false, frames: Boolean = false): String {
            val scripts = if (wasm) "'self' 'wasm-unsafe-eval'" else "'self'"
            val styles = if (inlineStyles) "'self' 'unsafe-inline'" else "'self'"
            val ancestors = if (frames) "'self'" else "'none'"
            return "default-src 'self'; script-src $scripts; style-src $styles; img-src 'self' data:; " +
                "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; " +
                "frame-ancestors $ancestors"
        }
    }
}

