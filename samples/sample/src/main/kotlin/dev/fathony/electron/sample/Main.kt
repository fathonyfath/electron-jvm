package dev.fathony.electron.sample

import dev.fathony.electron.sidecar.Sidecar
import dev.fathony.electron.sidecar.runSidecar
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.http.content.staticPaths
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.notExists
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
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
        val files = dataDirectory.resolve("files").createDirectories()
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
            // The Files page: a dropped file's contents, kept in the data folder, and a note written there; each
            // answers with the file's absolute path, which the page drags out of the window.
            post("files/keep") {
                val file = files.resolve(safeName(call.request.queryParameters["name"]))
                file.writeBytes(call.receive<ByteArray>())
                call.respondText(file.toAbsolutePath().toString())
            }
            post("files/note") {
                val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH-mm-ss"))
                val file = files.resolve("note-$time.txt")
                file.writeText("Written by the sample sidecar at $time.\n")
                call.respondText(file.toAbsolutePath().toString())
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

/** A file name from the page, without folders or anything a file system may refuse. */
private fun safeName(name: String?): String {
    val safe = name.orEmpty().substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
    require(safe.isNotEmpty() && safe.trim('.').isNotEmpty()) { "no file name" }
    return safe.take(120)
}

/** Refuses any other name, such as a path. */
private fun previewFile(directory: Path, name: String?): Path {
    require(name in PREVIEW_FILES) { "no preview file $name" }
    return directory.resolve(name!!)
}
