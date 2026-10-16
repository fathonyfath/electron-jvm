package dev.fathony.electron.sidecar

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class SidecarTest {
    @Test
    fun `api takes the token from the Authorization header`() = sidecarTest {
        val response = client.post("/api/echo") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals("echo", response.bodyAsText())
    }

    @Test
    fun `api refuses a request without the token, with a wrong one, or with it in the query`() = sidecarTest {
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/echo").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/echo") { header(HttpHeaders.Authorization, "Bearer wrong") }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/api/echo?token=$TOKEN").status)
    }

    @Test
    fun `a rendered page takes the token, with the page's policy`() = sidecarTest {
        val response = client.get("/page/todos") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }
        assertEquals("<ul><li>one</li></ul>", response.bodyAsText())
        assertEquals(Sidecar.contentSecurityPolicy(), response.headers["Content-Security-Policy"])
    }

    @Test
    fun `the shell's entry point reaches a rendered page's root`() = sidecarTest {
        assertEquals("<h1>home</h1>", client.get("/page/") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.bodyAsText())
    }

    @Test
    fun `an HTMX request to a rendered page takes the token as well`() = sidecarTest {
        val response = client.post("/page/todos") {
            header(HttpHeaders.Authorization, "Bearer $TOKEN")
            header("HX-Request", "true")
        }
        assertEquals("<li>two</li>", response.bodyAsText())
    }

    @Test
    fun `a rendered page refuses a request without the token`() = sidecarTest {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/page/todos").status)
    }

    @Test
    fun `the built page needs no token, and its routes fall back to index html`() = sidecarTest {
        assertEquals("<p>built page</p>", client.get("/page/index.html").bodyAsText().trim())
        assertEquals("<p>built page</p>", client.get("/page/editor").bodyAsText().trim())
    }

    @Test
    fun `public routes need no token`() = sidecarTest {
        assertEquals("open", client.get("/public/open").bodyAsText())
    }

    @Test
    fun `paths outside the prefixes are not found`() = sidecarTest {
        assertEquals(HttpStatusCode.NotFound, client.get("/todos") { header(HttpHeaders.Authorization, "Bearer $TOKEN") }.status)
    }

    @Test
    fun `a request for another host is refused`() = sidecarTest(host = "evil.example") {
        assertEquals(HttpStatusCode.Forbidden, client.get("/public/open").status)
    }

    private fun sidecarTest(host: String = "localhost:80", block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            val dataDirectory = Files.createTempDirectory("sidecar-test")
            val message = """{"type": "token", "token": "$TOKEN", "data": "$dataDirectory"}"""
            application {
                sidecar(SidecarToken.fromMessage(message), Peers(), dataDirectory) {
                    page()
                    page {
                        get("/") { call.respondText("<h1>home</h1>", ContentType.Text.Html) }
                        get("todos") { call.respondText("<ul><li>one</li></ul>", ContentType.Text.Html) }
                        post("todos") { call.respondText("<li>two</li>", ContentType.Text.Html) }
                    }
                    api { post("echo") { call.respondText("echo") } }
                    publicRoutes { get("open") { call.respondText("open") } }
                }
            }
            // The test engine's connection is port 80, and the server only answers its own Host.
            client = createClient { defaultRequest { headers.append(HttpHeaders.Host, host) } }
            block()
        }

    private companion object {
        const val TOKEN = "test-token"
    }
}
