package dev.ilgax.minekt.api

import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.item.component.SwingAnimation
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

// What the player is looking at, and acting on blocks and entities like a player would (the server
// still applies its usual reach and permission checks).

/** The block under the crosshair, or null. */
val Minekt.targetedBlock: TargetedBlock? get() = game { mc ->
    val hit = mc.hitResult as? BlockHitResult ?: return@game null
    if (hit.type != HitResult.Type.BLOCK) return@game null
    TargetedBlock(hit.blockPos.immutable(), mc.level!!.blockId(hit.blockPos), hit.direction.toApi(), hit.location)
}

/** The entity under the crosshair, or null. */
val Minekt.targetedEntity: EntityInfo? get() = game { mc ->
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
    val center = Vec3(pos.x + 0.5 + side.stepX * 0.5, pos.y + 0.5 + side.stepY * 0.5, pos.z + 0.5 + side.stepZ * 0.5)
    val result = mc.gameMode!!.useItemOn(player, InteractionHand.MAIN_HAND, BlockHitResult(center, side, pos, false))
    if (result.consumesAction()) player.swingMainHand()
    result.consumesAction()
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
