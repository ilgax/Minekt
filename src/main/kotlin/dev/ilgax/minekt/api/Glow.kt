package dev.ilgax.minekt.api

import dev.ilgax.minekt.hooks.Glows
import dev.ilgax.minekt.job.Job

// Glow outlines, seen through walls, only on your screen:
//
//   for (e in entities(32.0)) if (e.type == "minecraft:zombie") glow(e, Color.RED)
//   val chest = glowBlock(12, 64, -30, Color.YELLOW)
//   chest.color = Color.GREEN        // change it later
//   chest.remove()
//
// Entities get the same outline as the Glowing effect; blocks get their own shape (a slab is a
// slab). Glows come off when the script ends; like shapes, they don't keep it running.

/** A glow outline from [glow] or [glowBlock]. */
class Glow internal constructor(
    internal val job: Job,
    /** The glowing entity's id, or null for a block. */
    val entity: Int?,
    /** The glowing block, or null for an entity. */
    val block: BlockPos?,
    color: Int,
    internal val fill: Boolean,
) {
    /** The outline's color, 0xRRGGBB. Can be changed while it shows. */
    @Volatile
    var color: Int = color and 0xFFFFFF

    /** Take the outline off. */
    fun remove() = Glows.remove(this)
}

/** Outline entity [entity] (an id, from `entities()` or `targetedEntity()`) in [color]. */
fun Minekt.glow(entity: Int, color: Int = Color.WHITE): Glow = addGlow(Glow(job, entity, null, color, fill = false))

/** Outline [entity] in [color]. */
fun Minekt.glow(entity: EntityInfo, color: Int = Color.WHITE): Glow = glow(entity.id, color)

/** Outline the block at [pos] in [color], through walls; [fill] also tints it see-through. */
fun Minekt.glowBlock(pos: BlockPos, color: Int = Color.WHITE, fill: Boolean = false): Glow =
    addGlow(Glow(job, null, pos, color, fill))

fun Minekt.glowBlock(x: Int, y: Int, z: Int, color: Int = Color.WHITE, fill: Boolean = false): Glow = glowBlock(BlockPos(x, y, z), color, fill)

private fun Minekt.addGlow(glow: Glow): Glow {
    job.checkAlive()
    if (!Glows.has(job)) job.cleanups += { Glows.removeAll(job) }
    Glows.add(glow)
    return glow
}
