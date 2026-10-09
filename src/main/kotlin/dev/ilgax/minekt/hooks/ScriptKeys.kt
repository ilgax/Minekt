package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.api.SCRIPT_KEY_SLOTS
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.resources.Identifier

/**
 * Keybind slots that show up under "Minekt" in Controls. Players bind them like any other key;
 * scripts listen with `onBinding(n) { }`. Fabric only allows registering keybinds at startup,
 * hence a fixed number of generic slots rather than per-script bindings.
 */
internal object ScriptKeys {
    private lateinit var mappings: List<KeyMapping>
    private lateinit var managerKey: KeyMapping

    fun register() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minekt", "scripts"))
        mappings = (1..SCRIPT_KEY_SLOTS).map { slot ->
            KeyMappingHelper.registerKeyMapping(
                KeyMapping("key.minekt.script_$slot", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.value, category),
            )
        }
        managerKey = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.minekt.manager", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.value, category),
        )
    }

    /** Called every client tick: turn presses into events for listening scripts. */
    fun dispatch(jobs: JobManager) {
        mappings.forEachIndexed { index, mapping ->
            while (mapping.consumeClick()) jobs.dispatch(Job.Event.Binding(index + 1))
        }
    }

    /** Whether the "Script manager" key was pressed since the last call. */
    fun consumeManagerKey(): Boolean {
        var pressed = false
        while (managerKey.consumeClick()) pressed = true
        return pressed
    }

    fun keyName(slot: Int): String {
        require(slot in 1..SCRIPT_KEY_SLOTS) { "Keybind slot must be 1..$SCRIPT_KEY_SLOTS" }
        return mappings[slot - 1].translatedKeyMessage.string
    }
}
