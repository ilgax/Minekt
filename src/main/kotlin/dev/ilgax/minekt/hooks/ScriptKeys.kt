package dev.ilgax.minekt.hooks

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.KeybindHandle
import dev.ilgax.minekt.mixin.KeyMappingAccessor
import dev.ilgax.minekt.mixin.OptionsAccessor
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Keybinds in Options → Controls → Minekt: the script manager key (registered with Fabric at startup)
 * and the ones scripts make with `keybind(…)`, added to and removed from the game's list while it
 * runs. The game only remembers keys it knows at startup, so the keys players choose for script
 * keybinds are kept in [file] (`minekt/settings/keybinds.json`) and put back when a script makes
 * the same keybind again.
 */
internal object ScriptKeys {
    private lateinit var category: KeyMapping.Category
    private lateinit var managerKey: KeyMapping

    private class Dynamic(val id: String, val owner: Job, val mapping: KeyMapping) : KeybindHandle {
        /** Whether it was down at the last tick, to turn changes into press and release events. */
        var wasDown = false

        override val keyName: String get() = onGameThread { mapping.translatedKeyMessage.string }
        override val isDown: Boolean get() = mapping.isDown
        override fun remove() = ScriptKeys.remove(this)
    }

    private val dynamic = ConcurrentHashMap<String, Dynamic>()

    @Volatile var file: Path? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()
    /** Keybind id → chosen key (`key.keyboard.f`). */
    private val chosen: MutableMap<String, String> by lazy { load() }

    fun register() {
        category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minekt", "scripts"))
        managerKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.minekt.manager", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.value, category),
        )
    }

    fun add(owner: Job, id: String, label: String, defaultKey: String?): KeybindHandle = onGameThread { mc ->
        dynamic[id]?.let { existing ->
            check(existing.owner === owner) { "The keybind '$label' already belongs to script '${existing.owner.name}' (job ${existing.owner.id})" }
            return@onGameThread existing
        }
        val default = defaultKey?.let { key ->
            runCatching { InputConstants.getKey(key) }.getOrNull()?.takeIf { it != InputConstants.UNKNOWN }
                ?: throw IllegalArgumentException("Unknown key '${key.removePrefix("key.keyboard.").removePrefix("key.")}' (use names like \"f\", \"f6\", \"left.shift\", \"mouse.middle\")")
        } ?: InputConstants.UNKNOWN
        // The name is what Controls shows (it's not a translation key) and must be unique in the game.
        val name = if (KeyMappingAccessor.`minekt$all`().containsKey(label)) "$label (${owner.name})" else label
        val mapping = KeyMapping(name, default.type, default.value, category)
        chosen[id]?.let { saved -> runCatching { mapping.setKey(InputConstants.getKey(saved)) } }
        val options = mc.options
        (options as OptionsAccessor).`minekt$setKeyMappings`(options.keyMappings + mapping)
        KeyMapping.resetMapping()
        Dynamic(id, owner, mapping).also { dynamic[id] = it }
    }

    private fun remove(handle: Dynamic) {
        if (!dynamic.remove(handle.id, handle)) return
        onGameThread { mc ->
            remember(handle)
            val options = mc.options
            (options as OptionsAccessor).`minekt$setKeyMappings`(options.keyMappings.filterNot { it === handle.mapping }.toTypedArray())
            KeyMappingAccessor.`minekt$all`().remove(handle.mapping.name, handle.mapping)
            KeyMapping.resetMapping()
        }
    }

    /**
     * Called every client tick: a key going down or up becomes one event for the owning script (a
     * held key's repeats don't count; a tap shorter than a tick is a press and a release); rebinds are saved.
     */
    fun tick(mc: Minecraft) {
        for (handle in dynamic.values) {
            var clicked = false
            while (handle.mapping.consumeClick()) clicked = true
            val down = handle.mapping.isDown
            if (!handle.wasDown && (down || clicked)) handle.owner.post(Job.Event.Keybind(handle.id, pressed = true))
            if ((handle.wasDown || clicked) && !down) handle.owner.post(Job.Event.Keybind(handle.id, pressed = false))
            handle.wasDown = down
            if (mc.gui.screen() == null) remember(handle)
        }
    }

    /** Whether the "Script manager" key was pressed since the last call. */
    fun consumeManagerKey(): Boolean {
        var pressed = false
        while (managerKey.consumeClick()) pressed = true
        return pressed
    }

    private fun remember(handle: Dynamic) {
        val key = handle.mapping.saveString()
        if (chosen[handle.id] == key) return
        if (handle.mapping.isDefault && handle.id !in chosen) return
        chosen[handle.id] = key
        save()
    }

    private fun load(): MutableMap<String, String> {
        val path = file ?: return ConcurrentHashMap()
        return runCatching {
            ConcurrentHashMap(gson.fromJson<Map<String, String>>(Files.readString(path), object : TypeToken<Map<String, String>>() {}.type))
        }.getOrElse { ConcurrentHashMap() }
    }

    private fun save() {
        val path = file ?: return
        runCatching {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(chosen.toSortedMap()))
        }
    }

    private fun <T> onGameThread(block: (Minecraft) -> T): T {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) return block(mc)
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync({ block(mc) }, mc).join()
        } catch (e: java.util.concurrent.CompletionException) {
            throw e.cause ?: e
        }
    }
}
