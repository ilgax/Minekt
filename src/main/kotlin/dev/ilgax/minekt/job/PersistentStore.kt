package dev.ilgax.minekt.job

import net.minecraft.core.BlockPos
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Values scripts keep across runs (`by persistent { … }`), one JSON file per store in [dir].
 *
 * Each live value is *bound* with a getter, so in-place changes (`map[k] = v`) are picked up:
 * stores are saved when a `var` is assigned, every [autosaveSeconds], when the owning script ends,
 * and on [saveAll] at shutdown. A file is only rewritten when its content actually changed.
 */
class PersistentStore(private val dir: Path, private val autosaveSeconds: Long = 5) {
    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .enableComplexMapKeySerialization() // maps keyed by BlockPos etc.
        .serializeSpecialFloatingPointValues()
        .disableHtmlEscaping()
        .create()

    private class Binding(val owner: Any, val type: Type, val current: () -> Any?)

    private inner class StoreFile(name: String) {
        val path: Path = dir.resolve("$name.json")
        val data: JsonObject = load()
        val bindings = ConcurrentHashMap<String, Binding>()
        var lastWritten: String? = null

        private fun load(): JsonObject {
            if (!Files.isRegularFile(path)) return JsonObject()
            val text = Files.readString(path)
            return try {
                JsonParser.parseString(text).asJsonObject.also { lastWritten = text }
            } catch (e: Exception) {
                // Keep the broken file for the user to inspect rather than silently losing it.
                Files.move(path, path.resolveSibling("${path.fileName}.broken"), StandardCopyOption.REPLACE_EXISTING)
                LOGGER.warn("Unreadable script data {}, moved aside", path, e)
                JsonObject()
            }
        }
    }

    private val files = ConcurrentHashMap<String, StoreFile>()

    private var closed = false
    private val autosave = lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "minekt-autosave").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay(::saveAll, autosaveSeconds, autosaveSeconds, TimeUnit.SECONDS) }
    }

    private fun file(store: String): StoreFile {
        require(store.matches(STORE_NAME)) { "Invalid store name '$store' (use letters, digits, _ . -)" }
        return files.computeIfAbsent(store) { StoreFile(it) }
    }

    /** The saved value of [key], decoded as [type], or null if there is none. Throws if it doesn't fit [type]. */
    fun <T> read(store: String, key: String, type: Type): T? {
        val file = file(store)
        val element = synchronized(file) { file.data.get(key) } ?: return null
        if (element.isJsonNull) return null
        return gson.fromJson(element, type)
    }

    /** Start tracking a live value; it is written out on every save of [store] until [release]. */
    @Synchronized
    fun bind(store: String, key: String, owner: Any, type: Type, current: () -> Any?) {
        file(store).bindings[key] = Binding(owner, type, current)
        if (!closed) autosave.value // start the autosave timer on first use
    }

    /** Write [store] now if anything changed. */
    fun save(store: String) {
        val file = file(store)
        synchronized(file) {
            for ((key, binding) in file.bindings) file.data.add(key, gson.toJsonTree(binding.current(), binding.type))
            val text = gson.toJson(file.data)
            if (text == file.lastWritten) return
            Files.createDirectories(dir)
            val temp = file.path.resolveSibling("${file.path.fileName}.tmp")
            Files.writeString(temp, text)
            Files.move(temp, file.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            file.lastWritten = text
        }
    }

    /** Save every store with live values. Errors are logged, not thrown: a value being changed mid-save is retried next time. */
    fun saveAll() {
        for ((name, file) in files) {
            if (file.bindings.isEmpty()) continue
            try {
                save(name)
            } catch (e: Exception) {
                LOGGER.warn("Couldn't save script data '{}' (will retry)", name, e)
            }
        }
    }

    /** Write every store with values bound by [owner], including custom/shared stores. */
    fun saveOwned(owner: Any) {
        for ((name, file) in files) {
            if (file.bindings.values.any { it.owner === owner }) save(name)
        }
    }

    /** Stop autosaving and save now; final job cleanup may still save its values afterwards. */
    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        if (autosave.isInitialized()) autosave.value.shutdownNow()
        saveAll()
    }

    /** Final save of everything [owner] bound, then stop tracking it. */
    fun release(owner: Any) {
        for ((name, file) in files) {
            if (file.bindings.values.none { it.owner === owner }) continue
            try {
                save(name)
            } finally {
                file.bindings.values.removeIf { it.owner === owner }
            }
        }
    }

    private companion object {
        val STORE_NAME = Regex("[A-Za-z0-9_.-]+")
        val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
    }
}
