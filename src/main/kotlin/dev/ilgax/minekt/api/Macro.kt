package dev.ilgax.minekt.api

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping

// Replaying recorded input. `\record <name>` writes a script made of these; edit it freely.
//
//   macro(
//       step(20, "forward sprint", yaw = 90f, pitch = 10f),
//       step(1, "forward sprint jump", yaw = 90f, pitch = 10f),
//       step(5, "attack", yaw = 90f, pitch = 40f, slot = 2),
//   )
//
// Each step holds exactly the listed keys for [ticks] ticks (20 per second) while looking at
// yaw/pitch. A key that goes down at the start of a step also counts as a click, so "attack"
// hits mobs and "use" places blocks. Steps without yaw/pitch/slot leave them as they are.

/** One stretch of a macro: [keys] held for [ticks] ticks. */
class MacroStep(val ticks: Int, val keys: Set<Key>, val yaw: Float?, val pitch: Float?, val slot: Int?)

/** A macro step; [keys] are names separated by spaces: forward back left right jump sneak sprint attack use drop swap_offhand. */
fun step(ticks: Int, keys: String = "", yaw: Float? = null, pitch: Float? = null, slot: Int? = null): MacroStep {
    require(ticks >= 1) { "A step lasts at least 1 tick" }
    require(slot == null || slot in 0..8) { "slot must be 0..8" }
    val parsed = keys.split(' ', ',').filter { it.isNotBlank() }.map { name ->
        Key.entries.firstOrNull { it.name.equals(name, ignoreCase = true) || it.name.replace("_", "").equals(name, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unknown key '$name' (use: ${Key.entries.joinToString(" ") { it.name.lowercase() }})")
    }
    return MacroStep(ticks, parsed.toSet(), yaw, pitch, slot)
}

/** Play [steps] in order; [speed] 2.0 plays twice as fast. Keys are released at the end. */
fun Minekt.macro(vararg steps: MacroStep, speed: Double = 1.0) = macro(steps.toList(), speed)

fun Minekt.macro(steps: List<MacroStep>, speed: Double = 1.0) {
    require(speed > 0) { "speed must be above 0" }
    var held = emptySet<Key>()
    var owed = 0.0 // fractional ticks carried over at speeds other than 1
    try {
        for (s in steps) {
            val before = held
            game { mc ->
                val player = mc.player ?: error("Not in a world")
                for (key in Key.entries) {
                    val mapping = mc.options.mapping(key)
                    val down = key in s.keys
                    if (down && key !in before) KeyMapping.click(InputConstants.getKey(mapping.saveString()))
                    mapping.setDown(down)
                    if (down) job.heldKeys += mapping else job.heldKeys -= mapping
                }
                s.yaw?.let { player.yRot = it; player.setYHeadRot(it) }
                s.pitch?.let { player.xRot = it.coerceIn(-90f, 90f) }
                s.slot?.let { player.inventory.selectedSlot = it }
            }
            held = s.keys
            owed += s.ticks / speed
            val ticks = owed.toInt()
            owed -= ticks
            if (ticks > 0) waitTicks(ticks)
        }
    } finally {
        runCatching { game { mc -> Key.entries.forEach { mc.options.mapping(it).setDown(false) } } }
    }
}
