package dev.ilgax.minekt.api

import dev.ilgax.minekt.hooks.Glows
import dev.ilgax.minekt.job.Job

// The Glowing effect's outline on any entity, in any color, seen through walls, only on your screen:
//
//   for (e in entities(32.0)) if (e.type == "minecraft:zombie") glow(e.id, Color.RED)
//   val g = glow(entities(8.0).first().id, Color.YELLOW)
//   g.color = Color.GREEN        // change it later
//   g.remove()
//
// For blocks, `highlight(pos)` outlines them in their own shape. Glows come off when the script
// ends or the client changes worlds or dimensions; like shapes, they don't keep it running.

/** A glow outline from [glow]. */
class Glow internal constructor(
    internal val job: Job,
    /** The glowing entity's id. */
    val entity: Int,
    color: Int,
) {
    /** The outline's color, 0xRRGGBB. Can be changed while it shows. */
    @Volatile
    var color: Int = color and 0xFFFFFF

    /** Take the outline off. */
    fun remove() = Glows.remove(this)
}

/** Outline entity [entity] (an id, from `entities()` or an event) in [color]. */
fun Minekt.glow(entity: Int, color: Int = Color.WHITE): Glow = addGlow(Glow(job, entity, color))

private fun Minekt.addGlow(glow: Glow): Glow {
    job.checkAlive()
    if (!Glows.has(job)) job.cleanups += { Glows.removeAll(job) }
    Glows.add(glow)
    return glow
}
