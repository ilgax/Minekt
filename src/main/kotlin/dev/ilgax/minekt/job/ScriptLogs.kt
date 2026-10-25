package dev.ilgax.minekt.job

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * One log per script, `<scriptDir>/logs/<script>.log`: when it ran, what it echoed or logged, and
 * every error with its full stack trace. The latest entries are also kept in memory for the
 * in-game log viewer. Files are written on a background thread and rotated at [MAX_FILE_BYTES]
 * (the previous one is kept as `<script>.old.log`).
 */
class ScriptLogs(val dir: Path) {
    enum class Level(val tag: String) { INFO("INFO"), ECHO("ECHO"), LOG("LOG"), WARN("WARN"), ERROR("ERROR") }

    class Entry(val time: LocalDateTime, val level: Level, val run: Int?, val text: String, val detail: String?)

    private val recent = ConcurrentHashMap<String, ArrayDeque<Entry>>()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "minekt-logs").apply { isDaemon = true } }
    private val changes = AtomicLong()

    /** Goes up with every entry, so a screen showing logs knows when to refresh. */
    val version: Long get() = changes.get()

    fun add(script: String, level: Level, text: String, detail: String? = null, run: Int? = null) {
        val entry = Entry(LocalDateTime.now(), level, run, text, detail)
        val entries = recent.computeIfAbsent(script) { ArrayDeque() }
        synchronized(entries) {
            entries.addLast(entry)
            while (entries.size > MAX_RECENT) entries.removeFirst()
        }
        changes.incrementAndGet()
        enqueue { write(script, entry) }
    }

    /** This session's entries for [script], oldest first. */
    fun recent(script: String): List<Entry> = recent[script]?.let { synchronized(it) { it.toList() } }.orEmpty()

    /** The last [lines] lines of [script]'s log file (e.g. from earlier sessions). */
    fun tail(script: String, lines: Int = 200): List<String> {
        val file = file(script)
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching { Files.readAllLines(file).takeLast(lines) }.getOrDefault(emptyList())
    }

    fun file(script: String): Path = dir.resolve("$script.log")

    fun clear(script: String) {
        recent.remove(script)
        changes.incrementAndGet()
        enqueue { Files.deleteIfExists(file(script)); Files.deleteIfExists(oldFile(script)) }
    }

    /** Wait until everything added so far is on disk. */
    fun flush() {
        val pending = synchronized(writer) {
            if (writer.isShutdown) return
            writer.submit {}
        }
        runCatching { pending.get(5, TimeUnit.SECONDS) }
    }

    /** Finish queued writes and release the writer after this host's final job has exited. */
    fun close() = synchronized(writer) { writer.shutdown() }

    private fun enqueue(write: () -> Unit) = synchronized(writer) {
        if (!writer.isShutdown) writer.execute { runCatching(write) }
    }

    private fun oldFile(script: String) = dir.resolve("$script.old.log")

    private fun write(script: String, entry: Entry) {
        Files.createDirectories(dir)
        val file = file(script)
        if (Files.isRegularFile(file) && Files.size(file) > MAX_FILE_BYTES) Files.move(file, oldFile(script), StandardCopyOption.REPLACE_EXISTING)
        Files.writeString(file, format(entry), StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    companion object {
        const val MAX_RECENT = 300
        const val MAX_FILE_BYTES = 1L shl 20
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** `2026-10-09 14:03:12 ERROR #3 text`, then the detail indented. */
        fun format(entry: Entry): String = buildString {
            append(TIME.format(entry.time)).append(' ').append(entry.level.tag.padEnd(5))
            entry.run?.let { append(" #").append(it) }
            append(' ').append(entry.text).append('\n')
            entry.detail?.lineSequence()?.forEach { append("    ").append(it).append('\n') }
        }
    }
}
