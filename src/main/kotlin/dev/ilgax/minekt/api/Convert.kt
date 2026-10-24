package dev.ilgax.minekt.api

import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

// Conversions from live game objects to the plain snapshot types scripts see, on the game's thread: the
// client's or the server's. Used by both sides, so nothing here may touch client classes.

/** [self]: whose distance [EntityInfo.distance] is, or [origin]: from where (else 0). */
internal fun Entity.toInfo(self: Entity?, origin: Vec3? = null) = EntityInfo(
    id = id,
    uuid = uuid.toString(),
    name = name.string,
    type = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
    position = Vec3(x, y, z),
    health = (this as? LivingEntity)?.health,
    distance = origin?.let { position().distanceTo(it) } ?: self?.distanceTo(this)?.toDouble() ?: 0.0,
    maxHealth = (this as? LivingEntity)?.maxHealth,
    customName = customName?.string,
    isBaby = (this as? LivingEntity)?.isBaby == true,
    equipment = (this as? LivingEntity)?.let { living ->
        EquipmentSlot.VALUES.mapNotNull { slot ->
            living.getItemBySlot(slot).takeUnless { it.isEmpty }?.let { slot.getName() to BuiltInRegistries.ITEM.getKey(it.item).toString() }
        }.toMap()
    }.orEmpty(),
    effects = (this as? LivingEntity)?.activeEffects?.map { EffectInfo(it.effect.registeredName, it.amplifier, it.duration) }.orEmpty(),
    velocity = deltaMovement,
    yaw = yRot,
    pitch = xRot,
    sneaking = isShiftKeyDown,
    invisible = isInvisible,
    glowing = isCurrentlyGlowing,
    onFire = isOnFire,
    vehicleId = vehicle?.id,
    dimension = level().dimension().identifier().toString(),
)

internal fun ItemStack.toInfo(slot: Int): ItemStackInfo {
    val enchantments = (get(DataComponents.ENCHANTMENTS)?.entrySet().orEmpty() + get(DataComponents.STORED_ENCHANTMENTS)?.entrySet().orEmpty())
        .associate { it.key.registeredName to it.intValue }
    return ItemStackInfo(
        slot = slot,
        id = BuiltInRegistries.ITEM.getKey(item).toString(),
        count = count,
        name = hoverName.string,
        customName = get(DataComponents.CUSTOM_NAME)?.string,
        damage = if (isDamageableItem) damageValue else 0,
        maxDamage = if (isDamageableItem) maxDamage else 0,
        unbreakable = has(DataComponents.UNBREAKABLE),
        enchantments = enchantments,
        lore = get(DataComponents.LORE)?.lines()?.map { it.string }.orEmpty(),
        maxStackSize = get(DataComponents.MAX_STACK_SIZE) ?: 1,
        nutrition = get(DataComponents.FOOD)?.nutrition(),
    )
}

internal fun Level.blockId(pos: BlockPos): String = BuiltInRegistries.BLOCK.getKey(getBlockState(pos).block).toString()


internal fun Face.toMc(): Direction = Direction.valueOf(name)
internal fun Direction.toApi(): Face = Face.valueOf(name)

