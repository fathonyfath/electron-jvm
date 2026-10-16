package dev.fathony.electron.sample

import dev.fathony.electron.sidecar.Sidecar
import dev.fathony.electron.sidecar.runSidecar
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.staticPaths
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.coroutines.delay

private val PREVIEW_FILES = listOf("index.html", "main.js")

/** The UI sidecar: the React page and its API. */
fun main() {
    runSidecar {
        // The React app; Monaco injects its styles at run time.
        page(Sidecar.contentSecurityPolicy(inlineStyles = true))
        val worker = peer("worker")
        val preview = previewDirectory(dataDirectory)
        api {
            staticPaths("preview/files", preview) {
                modify { _, call ->
                    // Framed by the page, with the API's access: show only what the user wrote.
                    call.response.headers.append("Content-Security-Policy", Sidecar.contentSecurityPolicy(frames = true))
                    // Saves change the files under the same URLs.
                    call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                }
            }
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
            post("preview/read/{file}") { call.respondText(previewFile(preview, call.parameters["file"]).readText()) }
            post("preview/save/{file}") {
                previewFile(preview, call.parameters["file"]).writeText(call.receiveText())
                call.respondText("saved")
            }
        }
    }
}

/** `preview/` in the data folder, seeded from the bundled files on the first launch. */
private fun previewDirectory(dataDirectory: Path): Path {
    val directory = dataDirectory.resolve("preview").createDirectories()
    for (name in PREVIEW_FILES) {
        val file = directory.resolve(name)
        if (file.notExists()) {
            val bundled = object {}.javaClass.getResource("/preview/$name") ?: error("no bundled preview/$name")
            file.writeText(bundled.readText())
        }
    }
    return directory
}

/** Refuses any other name, such as a path. */
private fun previewFile(directory: Path, name: String?): Path {
    require(name in PREVIEW_FILES) { "no preview file $name" }
    return directory.resolve(name!!)
}
