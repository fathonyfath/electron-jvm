package dev.fathony.electron.sidecar

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

/**
 * Runs `main` as a sidecar: a Ktor server on a free loopback port, set up by [configure]. Reads the token and data
 * folder from stdin, prints `SIDECAR_READY <port>`, then takes the peers' ports from stdin until it closes, and exits.
 */
fun runSidecar(configure: Sidecar.() -> Unit): Nothing {
    val input = System.`in`.bufferedReader()
    val peers = Peers()
    val port = try {
        val first = input.readLine()
        val token = SidecarToken.fromMessage(first)
        val dataDirectory = SidecarToken.dataDirectory(first).createDirectories()
        val server = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            sidecar(token, peers, dataDirectory, configure)
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

internal fun Application.sidecar(token: SidecarToken, peers: Peers, dataDirectory: Path, configure: Sidecar.() -> Unit) {
    install(LoopbackHostOnly)
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respondText(cause.message ?: cause.toString(), status = HttpStatusCode.InternalServerError)
        }
    }
    install(Authentication) {
        bearer(Sidecar.AUTHENTICATION) {
            authenticate { credential -> if (token.matches(credential.token)) UserIdPrincipal("page") else null }
        }
    }
    routing { Sidecar(this, token, peers, dataDirectory).configure() }
}

/** Stops web pages whose domain was rebound to 127.0.0.1: their `Host` is not this server's. */
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
