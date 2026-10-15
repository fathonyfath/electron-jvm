package dev.fathony.electron.sidecar

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Runs `main` as a sidecar of the Electron shell: a Ktor server on a free loopback port, set up by [configure] (see
 * [Sidecar], which also gives clients for the other sidecars).
 *
 * The shell's first line on stdin is the per-launch token, which every route under `/api` requires, as
 * `Authorization: Bearer <token>` or, for `EventSource`, which cannot send headers, as `?token=`. Requests whose
 * `Host` is not this server's own loopback address are refused, which keeps a web page that has rebound its domain to
 * 127.0.0.1 out. A handler's exception becomes a 500 with its message. Once the server listens this prints
 * `SIDECAR_READY <port>` for the shell, then reads the shell's later messages (the other sidecars' ports) until it
 * closes stdin, when it quits or crashes, and ends the process. A failure before that ends it with status 1, which
 * the shell reports.
 */
fun runSidecar(configure: Sidecar.() -> Unit): Nothing {
    val input = System.`in`.bufferedReader()
    val peers = Peers()
    val port = try {
        val token = SidecarToken.fromMessage(input.readLine())
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            install(LoopbackHostOnly)
            install(SSE)
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    call.respondText(cause.message ?: cause.toString(), status = HttpStatusCode.InternalServerError)
                }
            }
            install(Authentication) {
                bearer(Sidecar.AUTHENTICATION) {
                    authHeader { call ->
                        call.request.parseAuthorizationHeader()
                            ?: call.request.queryParameters["token"]?.let { HttpAuthHeader.Single("Bearer", it) }
                    }
                    authenticate { credential -> if (token.matches(credential.token)) UserIdPrincipal("page") else null }
                }
            }
            routing { Sidecar(this, token, peers).configure() }
        }
        server.start(wait = false)
        Runtime.getRuntime().addShutdownHook(Thread { server.stop(100, 1_000) })
        runBlocking { server.engine.resolvedConnectors().first().port }
    } catch (e: Throwable) {
        e.printStackTrace()
        exitProcess(1)
    }
    println("$READY_PREFIX$port")
    System.out.flush()
    input.lineSequence().forEach(peers::receive)
    exitProcess(0)
}

/**
 * Refuses a request whose `Host` header is not `127.0.0.1:<port>` or `localhost:<port>` of the connection it came
 * on. Any program can send any `Host`, and the token covers those; a browser cannot, so this stops a web page whose
 * domain was rebound to 127.0.0.1 from reading even the page.
 */
private val LoopbackHostOnly = createApplicationPlugin("LoopbackHostOnly") {
    onCall { call ->
        val port = call.request.local.localPort
        val host = call.request.headers[HttpHeaders.Host]
        if (host != "127.0.0.1:$port" && host != "localhost:$port") {
            call.respondText("forbidden host", status = HttpStatusCode.Forbidden)
        }
    }
}

private const val READY_PREFIX = "SIDECAR_READY "
