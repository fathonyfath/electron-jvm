package dev.fathony.electron.htmx

import java.nio.file.Path
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readLines
import kotlin.io.path.writeLines
import kotlin.io.path.writeText

class Note(val id: String, val text: String)

/** Notes in a file, one per line as `<id>\t<text>`. */
class Notes(private val file: Path) {
    @Synchronized
    fun all(): List<Note> =
        if (!file.exists()) {
            emptyList()
        } else {
            file.readLines().mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 } }.map { Note(it[0], it[1]) }
        }

    @Synchronized
    fun add(text: String) {
        // One note per line.
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isNotEmpty()) file.writeText("${UUID.randomUUID()}\t$clean\n", options = arrayOf(CREATE, APPEND))
    }

    @Synchronized
    fun remove(id: String) {
        file.writeLines(all().filter { it.id != id }.map { "${it.id}\t${it.text}" })
    }
}
