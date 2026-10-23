package dev.ilgax.minekt.api

import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext

// The first block or entity along any line:
//
//   val p = player
//   when (val hit = raycast(p.eyePosition, p.eyePosition + p.lookDirection * 50.0)) {
//       is RayHit.Block -> echo("${hit.id} ${hit.distance.toInt()} blocks away, ${hit.face} face")
//       is RayHit.Entity -> echo("${hit.entity.name} in the way")
//       null -> echo("nothing within 50 blocks")
//   }
//   val canSee = raycast(mobEyes, myEyes, entities = false) == null     // line of sight
//
// Only what's loaded on your client is seen. Unloaded chunks count as empty.

/** What [raycast] hit first: [pos] is the exact point, [distance] how far it is from the start. */
sealed interface RayHit {
    val pos: Vec3
    val distance: Double

    /** A block at [block], [id] like `minecraft:stone`, hit on its [face]. */
    data class Block(val block: BlockPos, val id: String, val face: Face, override val pos: Vec3, override val distance: Double) : RayHit

    data class Entity(val entity: EntityInfo, override val pos: Vec3, override val distance: Double) : RayHit
}

/**
 * The first block or entity on the straight line [from] → [to], or null if it's clear.
 * - [blocks], [entities]: what can stop the line.
 * - [fluids]: water and lava surfaces stop it too.
 * - [solidOnly]: only blocks you'd bump into stop it (grass, flowers, open doors and signs don't); otherwise
 *   anything you could point at does, like the crosshair.
 * - You yourself never stop it, unless [includeSelf].
 */
fun Minekt.raycast(
    from: Vec3,
    to: Vec3,
    blocks: Boolean = true,
    entities: Boolean = true,
    fluids: Boolean = false,
    solidOnly: Boolean = false,
    includeSelf: Boolean = false,
): RayHit? = game { mc ->
    val level = mc.level ?: error("Not in a world")
    val start = from
    val end = to
    var best: RayHit? = null

    if (blocks) {
        val shape = if (solidOnly) ClipContext.Block.COLLIDER else ClipContext.Block.OUTLINE
        val fluid = if (fluids) ClipContext.Fluid.ANY else ClipContext.Fluid.NONE
        val hit = level.clip(ClipContext(start, end, shape, fluid, CollisionContext.empty()))
        if (hit.type == HitResult.Type.BLOCK) {
            best = RayHit.Block(hit.blockPos.immutable(), level.blockId(hit.blockPos), hit.direction.toApi(), hit.location, start.distanceTo(hit.location))
        }
    }

    if (entities) {
        val self = mc.player
        val limit = best?.distance ?: start.distanceTo(end)
        val area = AABB(start, end).inflate(1.0)
        for (entity in level.getEntities(if (includeSelf) null else self, area) { !it.isSpectator && it.isPickable }) {
            val box = entity.boundingBox.inflate(entity.pickRadius.toDouble())
            val point = if (box.contains(start)) start else box.clip(start, end).orElse(null) ?: continue
            val distance = start.distanceTo(point)
            if (distance < limit && (best == null || distance < best.distance)) {
                best = RayHit.Entity(entity.toInfo(self), point, distance)
            }
        }
    }
    best
}
