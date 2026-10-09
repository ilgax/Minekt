package dev.ilgax.minekt.api

import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.world.phys.Vec3 as McVec3

// What the player is looking at, and acting on blocks and entities like a player would (the server
// still applies its usual reach and permission checks).

/** The block under the crosshair, or null. */
fun Minekt.targetedBlock(): TargetedBlock? = game { mc ->
    val hit = mc.hitResult as? BlockHitResult ?: return@game null
    if (hit.type != HitResult.Type.BLOCK) return@game null
    TargetedBlock(hit.blockPos.toApi(), mc.level!!.blockId(hit.blockPos), hit.direction.toApi(), hit.location.toApi())
}

/** The entity under the crosshair, or null. */
fun Minekt.targetedEntity(): EntityInfo? = game { mc ->
    (mc.hitResult as? EntityHitResult)?.entity?.toInfo(mc.player)
}

/** Use the held item (right-click in the air: eat, throw, draw a bow...). */
fun Minekt.useItem() = game { mc ->
    val player = mc.player ?: error("Not in a world")
    mc.gameMode!!.useItem(player, InteractionHand.MAIN_HAND).consumesAction()
}

/** Right-click a block face with the held item: open a chest, press a button, place against it. */
fun Minekt.useOn(pos: BlockPos, face: Face = Face.UP): Boolean = game { mc ->
    val player = mc.player ?: error("Not in a world")
    val side = face.toMc()
    val center = McVec3(pos.x + 0.5 + side.stepX * 0.5, pos.y + 0.5 + side.stepY * 0.5, pos.z + 0.5 + side.stepZ * 0.5)
    val result = mc.gameMode!!.useItemOn(player, InteractionHand.MAIN_HAND, BlockHitResult(center, side, pos.toMc(), false))
    if (result.consumesAction()) player.swingMainHand()
    result.consumesAction()
}

/**
 * Place the held block at [pos] by clicking a solid neighbour. Returns false if nothing to click
 * against or the placement was refused.
 */
fun Minekt.placeBlock(pos: BlockPos): Boolean {
    val neighbour = game { mc ->
        val level = mc.level ?: error("Not in a world")
        Face.entries.firstNotNullOfOrNull { face ->
            val side = face.toMc()
            val n = pos.toMc().relative(side)
            if (!level.getBlockState(n).canBeReplaced()) n.toApi() to side.opposite.toApi() else null
        }
    } ?: return false
    return useOn(neighbour.first, neighbour.second)
}

/**
 * Mine the block at [pos] (survival takes as long as it normally would). Blocks until it's gone or
 * [timeoutTicks] pass; returns whether it was broken.
 */
fun Minekt.breakBlock(pos: BlockPos, face: Face = Face.UP, timeoutTicks: Int = 200): Boolean {
    val target = pos.toMc()
    val side = face.toMc()
    val air = { game { mc -> mc.level!!.getBlockState(target).isAir } }
    if (air()) return true
    game { mc -> mc.gameMode!!.startDestroyBlock(target, side) }
    try {
        repeat(timeoutTicks) {
            if (air()) return true
            game { mc ->
                mc.gameMode!!.continueDestroyBlock(target, side)
                mc.player!!.swingMainHand()
            }
            waitTicks(1)
        }
        return air()
    } finally {
        runCatching { game { mc -> mc.gameMode?.stopDestroyBlock() } }
    }
}

/** Attack (left-click) an entity by id, e.g. one from `entities()` or `targetedEntity()`. */
fun Minekt.attack(entityId: Int) = game { mc ->
    val player = mc.player ?: error("Not in a world")
    mc.gameMode!!.attack(player, entity(mc, entityId))
    player.swingMainHand()
}

/** Interact (right-click) with an entity: trade with a villager, mount a horse, ... */
fun Minekt.interact(entityId: Int): Boolean = game { mc ->
    val player = mc.player ?: error("Not in a world")
    val target = entity(mc, entityId)
    mc.gameMode!!.interact(player, target, EntityHitResult(target), InteractionHand.MAIN_HAND).consumesAction()
}

private fun LocalPlayer.swingMainHand() = swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false)

private fun entity(mc: Minecraft, id: Int): Entity =
    mc.level?.getEntity(id) ?: error("No entity with id $id nearby")

/** Turn smoothly to [yaw]/[pitch] over [ticks] ticks (instead of snapping like setOrientation). */
fun Minekt.turnTo(yaw: Float, pitch: Float, ticks: Int = 10) {
    val start = player
    // Turn the short way round.
    val deltaYaw = ((yaw - start.yaw) % 360f + 540f) % 360f - 180f
    val deltaPitch = pitch.coerceIn(-90f, 90f) - start.pitch
    for (step in 1..ticks.coerceAtLeast(1)) {
        val t = step.toFloat() / ticks.coerceAtLeast(1)
        val eased = t * t * (3 - 2 * t) // smoothstep
        setOrientation(start.yaw + deltaYaw * eased, start.pitch + deltaPitch * eased)
        if (step < ticks) waitTicks(1)
    }
}

/** Turn smoothly to look at a point. */
fun Minekt.turnToward(x: Double, y: Double, z: Double, ticks: Int = 10) {
    val (yaw, pitch) = game { mc ->
        val p = mc.player ?: error("Not in a world")
        val dx = x - p.x
        val dy = y - p.eyeY
        val dz = z - p.z
        Math.toDegrees(atan2(-dx, dz)).toFloat() to (-Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()
    }
    turnTo(yaw, pitch, ticks)
}

fun Minekt.turnToward(pos: Vec3, ticks: Int = 10) = turnToward(pos.x, pos.y, pos.z, ticks)
