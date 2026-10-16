package dev.fathony.electron.sidecar

import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
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
