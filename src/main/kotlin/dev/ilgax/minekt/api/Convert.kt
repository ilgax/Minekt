package dev.ilgax.minekt.api

import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.shapes.CollisionContext

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
    gameMode = (this as? ServerPlayer)?.gameMode()?.serializedName,
    isOp = (this as? ServerPlayer)?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER),
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

/** Everything about the block at [pos], for `blockAt`. */
internal fun Level.blockInfo(pos: BlockPos): BlockInfo {
    val state = getBlockState(pos)
    val sign = getBlockEntity(pos) as? SignBlockEntity
    fun lines(slot: SignTextSlot) = sign?.getText(slot)?.getMessages(false)?.map { it.string }
    return BlockInfo(
        pos = pos,
        id = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
        properties = state.values.toList().associate { it.property().name to it.valueName() },
        biome = getBiome(pos).registeredName,
        skyLight = getBrightness(LightLayer.SKY, pos),
        blockLight = getBrightness(LightLayer.BLOCK, pos),
        light = getMaxLocalRawBrightness(pos),
        isAir = state.isAir,
        isSolid = state.isSolid,
        hardness = state.getDestroySpeed(this, pos),
        lightEmission = state.lightEmission,
        signFront = lines(SignTextSlot.FRONT),
        signBack = lines(SignTextSlot.BACK),
    )
}

/** The first block or entity from [from] to [to] (see `raycast`), never [exclude]; [info] makes the entity's snapshot. */
internal fun Level.raycast(
    from: Vec3,
    to: Vec3,
    blocks: Boolean,
    entities: Boolean,
    fluids: Boolean,
    solidOnly: Boolean,
    exclude: Entity?,
    info: (Entity) -> EntityInfo,
): RayHit? {
    var best: RayHit? = null
    if (blocks) {
        val shape = if (solidOnly) ClipContext.Block.COLLIDER else ClipContext.Block.OUTLINE
        val fluid = if (fluids) ClipContext.Fluid.ANY else ClipContext.Fluid.NONE
        val view = if (this is ServerLevel) LoadedBlockView(this) else this
        val hit = view.clip(ClipContext(from, to, shape, fluid, CollisionContext.empty()))
        if (hit.type == HitResult.Type.BLOCK) {
            val id = BuiltInRegistries.BLOCK.getKey(view.getBlockState(hit.blockPos).block).toString()
            best = RayHit.Block(hit.blockPos.immutable(), id, hit.direction.toApi(), hit.location, from.distanceTo(hit.location))
        }
    }
    if (entities) {
        val limit = best?.distance ?: from.distanceTo(to)
        for (entity in getEntities(exclude, AABB(from, to).inflate(1.0)) { !it.isSpectator && it.isPickable }) {
            val box = entity.boundingBox.inflate(entity.pickRadius.toDouble())
            val point = if (box.contains(from)) from else box.clip(from, to).orElse(null) ?: continue
            val distance = from.distanceTo(point)
            if (distance < limit && (best == null || distance < best.distance)) best = RayHit.Entity(info(entity), point, distance)
        }
    }
    return best
}

/** Shape queries may read neighboring blocks too: none of these lookups may load a server chunk. */
private class LoadedBlockView(private val level: ServerLevel) : BlockGetter {
    override fun getBlockState(pos: BlockPos): BlockState =
        level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4)?.getBlockState(pos) ?: Blocks.AIR.defaultBlockState()

    override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState

    override fun getBlockEntity(pos: BlockPos): BlockEntity? =
        level.chunkSource.getChunkNow(pos.x shr 4, pos.z shr 4)?.getBlockEntity(pos)

    override fun getHeight(): Int = level.height

    override fun getMinY(): Int = level.minY
}


internal fun Face.toMc(): Direction = Direction.valueOf(name)
internal fun Direction.toApi(): Face = Face.valueOf(name)

