package dev.fathony.electron.sidecar

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What a sidecar sets up in [runSidecar], as Ktor routes under their own prefixes: the page at `/page`, the API at
 * `/api`, token-free routes at `/public`. The shell adds the token to the window's requests.
 */
class Sidecar internal constructor(
    private val routing: Routing,
    private val token: SidecarToken,
    private val peers: Peers,
    /** This sidecar's folder in the app's data folder, kept across launches. */
    val dataDirectory: Path,
) {
    private val clients = ConcurrentHashMap<String, HttpClient>()

    /**
     * Serves the classpath folder [resources] (where `npmProject` builds to) at `/page`, without the token; paths that
     * are no file get `index.html`.
     */
    fun page(contentSecurityPolicy: String = contentSecurityPolicy(), resources: String = "page") {
        routing.staticResources(PAGE, resources) {
            default("index.html")
            modify { _, call -> call.response.headers.append("Content-Security-Policy", contentSecurityPolicy) }
        }
    }

    /**
     * Pages this sidecar renders under `/page`, such as kotlinx.html with HTMX; they require the token. The policy
     * allows no inline scripts or `eval`: with HTMX, skip `hx-on` and turn off `includeIndicatorStyles`. Call it once.
     */
    fun page(contentSecurityPolicy: String = contentSecurityPolicy(), routes: Route.() -> Unit) {
        routing.authenticate(AUTHENTICATION) {
            route(PAGE) {
                install(PagePolicy) { policy = contentSecurityPolicy }
                routes()
            }
        }
    }

    /** Routes under `/api`, each requiring the token. */
    fun api(routes: Route.() -> Unit) {
        routing.authenticate(AUTHENTICATION) {
            route(API, routes)
        }
    }

    /** Routes under `/public` without the token, for clients outside the window. Serve nothing secret. */
    fun publicRoutes(routes: Route.() -> Unit) {
        routing.route(PUBLIC, routes)
    }

    /** A client for sidecar [name]: requests go to its current port with the token, waiting up to 10 s for it. */
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
         * A policy allowing only this sidecar's files: no inline scripts, no framing. [inlineStyles] (Monaco needs
         * them), [wasm] and [frames] (by this sidecar's own pages) loosen it.
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

private class PagePolicyConfig {
    var policy: String = Sidecar.contentSecurityPolicy()
}

private val PagePolicy = createRouteScopedPlugin("PagePolicy", ::PagePolicyConfig) {
    val policy = pluginConfig.policy
    onCall { call -> call.response.headers.append("Content-Security-Policy", policy) }
}
