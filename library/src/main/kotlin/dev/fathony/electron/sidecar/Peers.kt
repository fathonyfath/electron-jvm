package dev.fathony.electron.sidecar

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The other sidecars' ports, as the shell last reported them over stdin: `{"type": "peers", "peers": {name: port}}`
 * once all sidecars are ready and again whenever a restart moves one.
 */
internal class Peers {
    private val ports = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** The port of [name], waiting for it to be reported if it is not yet; fails if it does not show up in time. */
    suspend fun port(name: String): Int =
        withTimeoutOrNull(WAIT_MS) { ports.map { it[name] }.filterNotNull().first() }
            ?: error("sidecar $name is not running (no port for it after ${WAIT_MS / 1_000} s)")

    /** Takes one line from the shell; messages of other types, or ones that do not parse, are ignored. */
    fun receive(line: String) {
        val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        if (message["type"]?.jsonPrimitive?.content != "peers") return
        val peers = message["peers"]?.jsonObject ?: return
        ports.value = peers.mapValues { (_, port) -> port.jsonPrimitive.int }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
