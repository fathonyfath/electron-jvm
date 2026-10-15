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
import java.util.concurrent.ConcurrentHashMap

/**
 * What a sidecar sets up in [runSidecar]: the page, if this sidecar serves the window, its API, and clients for the
 * other sidecars. Routes are plain Ktor routes; `sse(...)` is available for server-sent events.
 */
class Sidecar internal constructor(
    private val routing: Routing,
    private val token: SidecarToken,
    private val peers: Peers,
) {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    /**
     * Serves the classpath resources under [resources] at `/`, with `index.html` for `/` and for any path that is no
     * file, so a single-page app's routes survive a reload. The page holds no secret and is served without the token;
     * the shell hands the page the token in its URL fragment. The page gets [contentSecurityPolicy], which by default
     * lets it load scripts, styles and data only from this sidecar, so an injected script cannot run and read the
     * token: scripts go in their own files, not inline.
     */
    fun page(resources: String, contentSecurityPolicy: String = contentSecurityPolicy()) {
        routing.staticResources("/", resources) {
            default("index.html")
            modify { _, call -> call.response.headers.append("Content-Security-Policy", contentSecurityPolicy) }
        }
    }

    /**
     * Routes under `/api`, each requiring the token.
     */
    fun api(routes: Route.() -> Unit) {
        routing.authenticate(AUTHENTICATION) {
            route("/api", routes)
        }
    }

    /**
     * A client for the sidecar called [name]: requests take a path (`post("/api/…")`), go to that sidecar's current
     * port with the token, and wait up to ten seconds for it if it has not started yet. Its port can change when it
     * restarts in development; each request looks it up again.
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

        /**
         * A Content-Security-Policy for [page]: everything from this sidecar only, no inline scripts, no plugins, no
         * framing. [inlineStyles] allows `<style>` elements and `style` attributes, which Monaco needs; an injected
         * style cannot send anything anywhere, since images and requests stay limited to this sidecar. [wasm] allows
         * compiling WebAssembly, which a Compose wasm page needs.
         */
        fun contentSecurityPolicy(inlineStyles: Boolean = false, wasm: Boolean = false): String {
            val scripts = if (wasm) "'self' 'wasm-unsafe-eval'" else "'self'"
            val styles = if (inlineStyles) "'self' 'unsafe-inline'" else "'self'"
            return "default-src 'self'; script-src $scripts; style-src $styles; img-src 'self' data:; " +
                "font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; " +
                "frame-ancestors 'none'"
        }
    }
}
