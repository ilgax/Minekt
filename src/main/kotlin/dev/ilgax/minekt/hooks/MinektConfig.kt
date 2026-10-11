package dev.ilgax.minekt.hooks

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.mojang.blaze3d.Blaze3D
import dev.ilgax.minekt.splitArgs
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** Minekt's own settings, in `minekt/config.json`; changed in its settings screen (Mod Menu or `\menu`). */
object MinektConfig {
    private val logger = LoggerFactory.getLogger("minekt")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    const val DEFAULT_PREFIX = "\\"

    @Volatile
    var file: Path? = null

    /** What chat commands start with. `\` by default; `/mk` always works too. */
    @Volatile
    var prefix: String = DEFAULT_PREFIX
        private set

    /** The command that opens scripts for editing, like `code` or `notepad++ -n1`; empty: the system's default app. */
    @Volatile
    var editor: String = ""
        private set

    /** Why [prefix] can't be [value], or null if it can. */
    fun prefixProblem(value: String): String? = when {
        value.isEmpty() -> "The prefix can't be empty"
        value.any(Char::isWhitespace) -> "The prefix can't have spaces"
        value.startsWith("/") -> "/ is for the game's commands: use /mk instead"
        value.length > 4 -> "Keep the prefix short (up to 4 characters)"
        else -> null
    }

    fun setPrefix(value: String): Boolean {
        if (prefixProblem(value) != null) return false
        prefix = value
        save()
        return true
    }

    fun setEditor(value: String) {
        editor = value.trim()
        save()
    }

    fun load() {
        val path = file ?: return
        if (!Files.isRegularFile(path)) return
        try {
            val json = gson.fromJson(Files.readString(path), JsonObject::class.java) ?: return
            json.get("prefix")?.asString?.takeIf { prefixProblem(it) == null }?.let { prefix = it }
            json.get("editor")?.asString?.let { editor = it.trim() }
        } catch (e: Exception) {
            logger.warn("Couldn't read {}: {}", path, e.message)
        }
    }

    private fun save() {
        val path = file ?: return
        try {
            Files.createDirectories(path.parent)
            val json = JsonObject().apply {
                addProperty("prefix", prefix)
                addProperty("editor", editor)
            }
            Files.writeString(path, gson.toJson(json) + "\n")
        } catch (e: Exception) {
            logger.warn("Couldn't save {}: {}", path, e.message)
        }
    }

    /** Open [file] for editing: with [editor] if set (the file is added at the end, or where `{file}` is), else the system's default app. */
    fun edit(file: Path) {
        val command = editor
        if (command.isNotEmpty()) {
            val parts = splitArgs(command)
            val args = if (parts.any { "{file}" in it }) parts.map { it.replace("{file}", file.toString()) } else parts + file.toString()
            try {
                ProcessBuilder(args).start()
                return
            } catch (e: Exception) {
                logger.warn("Couldn't start editor '{}': {}", command, e.message)
            }
        }
        Blaze3D.openPath(file)
    }
}
