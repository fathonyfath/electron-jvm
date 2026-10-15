package dev.fathony.electron.sample

import dev.fathony.electron.sidecar.Sidecar
import dev.fathony.electron.sidecar.runSidecar
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.delay

/**
 * The sample sidecar, which serves the window: the React app built from web/, an API that echoes text reversed,
 * waits, fails, or has the worker sidecar shout it, and a `tick` event every second.
 */
fun main() {
    runSidecar {
        // The React app; Monaco injects its styles at run time.
        page("web", Sidecar.contentSecurityPolicy(inlineStyles = true))
        val worker = peer("worker")
        api {
            post("reverse") { call.respondText(call.receiveText().reversed()) }
            post("wait") {
                val millis = call.receiveText().trim().toLong()
                delay(millis)
                call.respondText("waited $millis ms")
            }
            post("fail") { throw IllegalArgumentException("asked to fail") }
            // The page talks only to this sidecar, which calls the others for it.
            post("shout") { call.respondText(worker.post("/api/shout") { setBody(call.receiveText()) }.bodyAsText()) }
            sse("events") {
                var tick = 0
                while (true) {
                    delay(1_000)
                    send(ServerSentEvent(data = "${++tick}", event = "tick"))
                }
            }
        }
    }
}
