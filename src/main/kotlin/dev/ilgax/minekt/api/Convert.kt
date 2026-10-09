package dev.ilgax.minekt.api

import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
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
)

internal fun ItemStack.toInfo(slot: Int) = ItemStackInfo(slot, BuiltInRegistries.ITEM.getKey(item).toString(), count)

internal fun Level.blockId(pos: McBlockPos): String = BuiltInRegistries.BLOCK.getKey(getBlockState(pos).block).toString()

internal fun McBlockPos.toApi() = BlockPos(x, y, z)
internal fun BlockPos.toMc() = McBlockPos(x, y, z)
internal fun Vec3.toMc() = McVec3(x, y, z)
internal fun McVec3.toApi() = Vec3(x, y, z)

internal fun Face.toMc(): Direction = Direction.valueOf(name)
internal fun Direction.toApi(): Face = Face.valueOf(name)
