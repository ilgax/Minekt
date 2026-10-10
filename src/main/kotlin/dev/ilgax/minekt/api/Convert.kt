package dev.ilgax.minekt.api

import net.minecraft.client.KeyMapping
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.core.BlockPos as McBlockPos
import net.minecraft.world.phys.Vec3 as McVec3

// Conversions from live game objects to the plain snapshot types scripts see. Client thread only.

internal fun Entity.toInfo(self: Entity?) = EntityInfo(
    id = id,
    uuid = uuid.toString(),
    name = name.string,
    type = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
    position = Vec3(x, y, z),
    health = (this as? LivingEntity)?.health,
    distance = self?.distanceTo(this)?.toDouble() ?: 0.0,
    maxHealth = (this as? LivingEntity)?.maxHealth,
    customName = customName?.string,
    isBaby = (this as? LivingEntity)?.isBaby == true,
    equipment = (this as? LivingEntity)?.let { living ->
        EquipmentSlot.VALUES.mapNotNull { slot ->
            living.getItemBySlot(slot).takeUnless { it.isEmpty }?.let { slot.getName() to BuiltInRegistries.ITEM.getKey(it.item).toString() }
        }.toMap()
    }.orEmpty(),
    effects = (this as? LivingEntity)?.activeEffects?.map { EffectInfo(it.effect.registeredName, it.amplifier, it.duration) }.orEmpty(),
    velocity = deltaMovement.toApi(),
    yaw = yRot,
    pitch = xRot,
    sneaking = isShiftKeyDown,
    invisible = isInvisible,
    glowing = isCurrentlyGlowing,
    onFire = isOnFire,
    vehicleId = vehicle?.id,
)

internal fun ItemStack.toInfo(slot: Int) = ItemStackInfo(slot, BuiltInRegistries.ITEM.getKey(item).toString(), count)

internal fun Level.blockId(pos: McBlockPos): String = BuiltInRegistries.BLOCK.getKey(getBlockState(pos).block).toString()

internal fun McBlockPos.toApi() = BlockPos(x, y, z)
internal fun BlockPos.toMc() = McBlockPos(x, y, z)
internal fun Vec3.toMc() = McVec3(x, y, z)
internal fun McVec3.toApi() = Vec3(x, y, z)

internal fun Face.toMc(): Direction = Direction.valueOf(name)
internal fun Direction.toApi(): Face = Face.valueOf(name)

/** The game's key binding behind a Minekt [Key]. */
internal fun net.minecraft.client.Options.mapping(key: Key): KeyMapping = when (key) {
    Key.FORWARD -> keyUp
    Key.BACK -> keyDown
    Key.LEFT -> keyLeft
    Key.RIGHT -> keyRight
    Key.JUMP -> keyJump
    Key.SNEAK -> keyShift
    Key.SPRINT -> keySprint
    Key.ATTACK -> keyAttack
    Key.USE -> keyUse
    Key.DROP -> keyDrop
    Key.SWAP_OFFHAND -> keySwapOffhand
}
