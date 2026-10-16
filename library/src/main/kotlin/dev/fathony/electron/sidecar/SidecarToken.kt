package dev.fathony.electron.sidecar

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The secret the shell generates for each launch and gives every sidecar and the page. Any local process can reach
 * a loopback port, so requests prove they come from the app by carrying it. The shell sends it on stdin, not in the
 * environment, which other processes of the same user can read. Comparisons take constant time, and the value never
 * appears in `toString`, so it cannot leak into logs.
 */
internal class SidecarToken private constructor(private val value: ByteArray) {
    /** Whether [candidate] is the token. */
    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(value, candidate.encodeToByteArray())

    /** The `Authorization` header value for calls to other sidecars. */
    fun bearer(): String = "Bearer " + value.decodeToString()

    override fun toString(): String = "SidecarToken(****)"

    companion object {
        /** From the shell's first line on stdin, `{"type": "token", "token": "…", "data": "…"}`. */
        fun fromMessage(line: String?): SidecarToken {
            val value = firstMessage(line)?.get("token")?.jsonPrimitive?.content
            requireNotNull(value) { "no token from the shell on stdin; start the sidecar from the Electron shell" }
            return SidecarToken(value.encodeToByteArray())
        }

        /** The sidecar's data folder from the shell's first line on stdin. */
        fun dataDirectory(line: String?): Path {
            val value = firstMessage(line)?.get("data")?.jsonPrimitive?.content
            requireNotNull(value) { "no data folder from the shell on stdin; start the sidecar from the Electron shell" }
            return Path.of(value)
        }

        private fun firstMessage(line: String?): JsonObject? =
            line?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                ?.takeIf { it["type"]?.jsonPrimitive?.content == "token" }
    }
}
