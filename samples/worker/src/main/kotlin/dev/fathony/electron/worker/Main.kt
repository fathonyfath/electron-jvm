package dev.fathony.electron.worker

import dev.fathony.electron.sidecar.runSidecar
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import kotlinx.coroutines.delay

/**
 * The worker sidecar: `shout` answers with the text upper-cased, after a pause that stands in for real work.
 */
fun main() {
    runSidecar {
        api {
            post("shout") {
                val text = call.receiveText()
                delay(300)
                call.respondText(text.uppercase() + "!")
            }
        }
    }
}
