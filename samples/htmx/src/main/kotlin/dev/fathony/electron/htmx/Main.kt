package dev.fathony.electron.htmx

import dev.fathony.electron.sidecar.runSidecar
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.server.html.respondHtml
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.util.Properties
import kotlinx.html.HTML
import kotlinx.html.a
import kotlinx.html.body
import kotlinx.html.button
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.head
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.li
import kotlinx.html.link
import kotlinx.html.main
import kotlinx.html.meta
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.script
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.stream.createHTML
import kotlinx.html.strong
import kotlinx.html.title
import kotlinx.html.ul

/** Pages rendered in Kotlin, with HTMX for the parts that change. */
fun main() {
    runSidecar {
        val notes = Notes(dataDirectory.resolve("notes.txt"))
        val worker = peer("worker")
        page {
            staticResources("assets", "assets")
            get("htmx.min.js") { call.respondBytes(HTMX, ContentType.Text.JavaScript) }
            get("/") { call.respondHtml { notesPage(notes.all()) } }
            // HTMX takes the #notes section from the page.
            post("notes") {
                notes.add(call.receiveParameters()["text"].orEmpty())
                call.respondHtml { notesPage(notes.all()) }
            }
            delete("notes/{id}") {
                notes.remove(call.parameters["id"].orEmpty())
                call.respondHtml { notesPage(notes.all()) }
            }
            post("shout") {
                val text = call.receiveParameters()["text"].orEmpty()
                val shouted = worker.post("/api/shout") { setBody(text) }.bodyAsText()
                // kotlinx.html escapes the text, so it cannot inject markup.
                call.respondText(createHTML().span { +shouted }, ContentType.Text.Html)
            }
        }
    }
}

private fun HTML.notesPage(notes: List<Note>) {
    head {
        meta(charset = "utf-8")
        // HTMX's indicator styles are a <style> element, which the page's policy refuses.
        meta(name = "htmx-config", content = """{"includeIndicatorStyles": false}""")
        title("Server pages")
        link(rel = "stylesheet", href = "assets/style.css")
        script(src = "htmx.min.js") {}
    }
    body {
        nav("nav") {
            strong { +"Server pages" }
            a(href = "sidecar://sample/page/") { +"React page" }
            a(href = "sidecar://sample/page/editor") { +"Editor" }
        }
        main("page") {
            h1 { +"Rendered by Ktor" }
            p { +"This page is HTML from the htmx sidecar, written with kotlinx.html. HTMX updates parts of it in place." }
            section {
                id = "notes"
                // Inherited by the form and the buttons below.
                attributes["hx-target"] = "#notes"
                attributes["hx-select"] = "#notes"
                attributes["hx-swap"] = "outerHTML"
                h2 { +"Notes" }
                p { +"Kept in the sidecar's data folder, so they survive relaunching the app." }
                form(classes = "row") {
                    attributes["hx-post"] = "notes"
                    input(name = "text") {
                        required = true
                        placeholder = "A note"
                    }
                    button { +"Add" }
                }
                ul {
                    for (note in notes) {
                        li {
                            span { +note.text }
                            button {
                                attributes["hx-delete"] = "notes/${note.id}"
                                +"Remove"
                            }
                        }
                    }
                }
            }
            section {
                h2 { +"Shout" }
                p { +"This sidecar asks the worker sidecar, as the React page's sidecar does." }
                form(classes = "row") {
                    attributes["hx-post"] = "shout"
                    attributes["hx-target"] = "#shout"
                    input(name = "text") {
                        required = true
                        placeholder = "Text to shout"
                    }
                    button { +"Shout via the worker" }
                }
                p {
                    id = "shout"
                }
            }
        }
    }
}

// Read directly: staticResources takes a package name and would turn the dots in "htmx.org/2.0.11" into folders.
private val HTMX: ByteArray = run {
    val version = Properties().run {
        resource("/META-INF/maven/org.webjars.npm/htmx.org/pom.properties").use(::load)
        getProperty("version")
    }
    resource("/META-INF/resources/webjars/htmx.org/$version/dist/htmx.min.js").use { it.readBytes() }
}

private fun resource(path: String) = checkNotNull(Note::class.java.getResourceAsStream(path)) { "no $path" }
