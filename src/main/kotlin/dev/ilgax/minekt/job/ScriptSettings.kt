package dev.ilgax.minekt.job

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * Settings scripts declare with `by setting(…)`, one JSON file per script in [dir]
 * (`minekt/settings/<script>.json`). Each entry keeps what the setting is (so the script manager
 * can show it even when the script isn't running) and its current value. The file is readable and
 * can be edited by hand; values that don't fit any more are reset to the default.
 */
class ScriptSettings(private val dir: Path) {
    sealed interface Kind {
        /** [value] made to fit this kind, or null if it can't be. */
        fun fit(value: Any?): Any?

        data object Toggle : Kind {
            override fun fit(value: Any?) = value as? Boolean
        }

        data class Whole(val min: Int, val max: Int) : Kind {
            override fun fit(value: Any?) = (value as? Number)?.toDouble()?.let { Math.round(it).toInt().coerceIn(min, max) }
        }

        data class Decimal(val min: Double, val max: Double, val step: Double) : Kind {
            override fun fit(value: Any?) = (value as? Number)?.toDouble()?.takeIf { !it.isNaN() }?.coerceIn(min, max)
        }

        data class Text(val maxLength: Int) : Kind {
            override fun fit(value: Any?) = (value as? String)?.take(maxLength)
        }

        data class Choice(val options: List<String>) : Kind {
            override fun fit(value: Any?) = (value as? String)?.takeIf { it in options }
        }
    }

    class Setting(val key: String, val label: String, val kind: Kind, val default: Any, @Volatile var value: Any)

    private class ScriptFile(val settings: LinkedHashMap<String, Setting>)

    private val files = ConcurrentHashMap<String, ScriptFile>()
    private val changes = AtomicLong()
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    /** Told about every change made with [set]: script, key, new value. */
    val listeners = CopyOnWriteArrayList<(String, String, Any) -> Unit>()

    /** Goes up with every change, so screens showing settings know when to refresh. */
    val version: Long get() = changes.get()

    /**
     * Declare [key] for [script] (called when the script runs `by setting(…)`) and return its
     * value: the saved one if it still fits [kind], else [default].
     */
    fun declare(script: String, key: String, label: String, kind: Kind, default: Any): Any {
        val fittedDefault = requireNotNull(kind.fit(default)) { "Default $default doesn't fit $kind" }
        val file = file(script)
        synchronized(file) {
            val saved = file.settings[key]?.value
            val value = kind.fit(saved) ?: fittedDefault
            val setting = Setting(key, label, kind, fittedDefault, value)
            val before = file.settings[key]
            file.settings[key] = setting
            if (before == null || before.kind != kind || before.label != label || before.default != fittedDefault || before.value != value) write(script, file)
            changes.incrementAndGet()
            return value
        }
    }

    fun get(script: String, key: String): Any? = file(script).settings[key]?.value

    /**
     * Change a setting. Returns the value actually stored. [notify]: tell [listeners] (and so the
     * running script); off when the script changes its own setting.
     */
    fun set(script: String, key: String, value: Any, notify: Boolean = true): Any {
        val file = file(script)
        val stored = synchronized(file) {
            val setting = file.settings[key] ?: throw IllegalArgumentException("No setting '$key' in $script")
            val fitted = setting.kind.fit(value) ?: throw IllegalArgumentException("$value doesn't fit setting '$key'")
            if (fitted == setting.value) return fitted
            setting.value = fitted
            write(script, file)
            fitted
        }
        changes.incrementAndGet()
        if (notify) listeners.forEach { it(script, key, stored) }
        return stored
    }

    fun list(script: String): List<Setting> = file(script).let { synchronized(it) { it.settings.values.toList() } }

    fun has(script: String): Boolean = list(script).isNotEmpty()

    /** Put every setting of [script] back to its default. */
    fun reset(script: String) {
        list(script).forEach { set(script, it.key, it.default) }
    }

    /** Forget settings a script no longer declares (called when a run that declared some ends). */
    fun keepOnly(script: String, keys: Set<String>) {
        val file = file(script)
        synchronized(file) {
            if (file.settings.keys.retainAll(keys)) write(script, file)
        }
        changes.incrementAndGet()
    }

    private fun file(script: String): ScriptFile {
        require(script.matches(NAME)) { "Invalid script name '$script'" }
        return files.computeIfAbsent(script) { ScriptFile(load(it)) }
    }

    fun path(script: String): Path = dir.resolve("$script.json")

    // ---- files ------------------------------------------------------------------------------------

    private fun load(script: String): LinkedHashMap<String, Setting> {
        val result = LinkedHashMap<String, Setting>()
        val path = path(script)
        if (!Files.isRegularFile(path)) return result
        val root = try {
            JsonParser.parseString(Files.readString(path)).asJsonObject
        } catch (e: Exception) {
            Files.move(path, path.resolveSibling("${path.fileName}.broken"), StandardCopyOption.REPLACE_EXISTING)
            return result
        }
        for ((key, element) in root.entrySet()) {
            runCatching {
                val obj = element.asJsonObject
                val kind = kindOf(obj)
                val default = kind.fit(plain(obj.get("default"))) ?: return@runCatching
                val value = kind.fit(plain(obj.get("value"))) ?: default
                result[key] = Setting(key, obj.get("label")?.asString ?: key, kind, default, value)
            }
        }
        return result
    }

    private fun write(script: String, file: ScriptFile) {
        val root = JsonObject()
        for (setting in file.settings.values) {
            val obj = JsonObject()
            obj.addProperty("label", setting.label)
            when (val kind = setting.kind) {
                Kind.Toggle -> obj.addProperty("type", "toggle")
                is Kind.Whole -> { obj.addProperty("type", "whole"); obj.addProperty("min", kind.min); obj.addProperty("max", kind.max) }
                is Kind.Decimal -> { obj.addProperty("type", "decimal"); obj.addProperty("min", kind.min); obj.addProperty("max", kind.max); obj.addProperty("step", kind.step) }
                is Kind.Text -> { obj.addProperty("type", "text"); obj.addProperty("maxLength", kind.maxLength) }
                is Kind.Choice -> { obj.addProperty("type", "choice"); obj.add("options", gson.toJsonTree(kind.options)) }
            }
            obj.add("default", json(setting.default))
            obj.add("value", json(setting.value))
            root.add(setting.key, obj)
        }
        Files.createDirectories(dir)
        val path = path(script)
        val temp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temp, gson.toJson(root))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun kindOf(obj: JsonObject): Kind = when (obj.get("type").asString) {
        "toggle" -> Kind.Toggle
        "whole" -> Kind.Whole(obj.get("min").asInt, obj.get("max").asInt)
        "decimal" -> Kind.Decimal(obj.get("min").asDouble, obj.get("max").asDouble, obj.get("step").asDouble)
        "text" -> Kind.Text(obj.get("maxLength").asInt)
        "choice" -> Kind.Choice(obj.getAsJsonArray("options").map { it.asString })
        else -> error("unknown setting type")
    }

    private fun plain(element: JsonElement?): Any? {
        val primitive = element as? JsonPrimitive ?: return null
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asDouble
            else -> primitive.asString
        }
    }

    private fun json(value: Any): JsonElement = when (value) {
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private companion object {
        val NAME = Regex("[A-Za-z0-9_.-]+")
    }
}
