package dev.fathony.electron.sidecar

import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The per-launch secret that proves a request comes from the app; any local process can reach a loopback port. */
internal class SidecarToken private constructor(private val value: ByteArray) {
    fun matches(candidate: String?): Boolean =
        candidate != null && MessageDigest.isEqual(value, candidate.encodeToByteArray())

    fun bearer(): String = "Bearer " + value.decodeToString()

    override fun toString(): String = "SidecarToken(****)"

    companion object {
        /** From the shell's first line on stdin, `{"type": "token", "token": "…", "data": "…"}`. */
        fun fromMessage(line: String?): SidecarToken {
            val value = firstMessage(line)?.get("token")?.jsonPrimitive?.content
            requireNotNull(value) { "no token from the shell on stdin; start the sidecar from the Electron shell" }
            return SidecarToken(value.encodeToByteArray())
        }

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
