package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.EntityInfo
import dev.ilgax.minekt.api.toInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySpawnReason
import net.minecraft.world.entity.Mob
import net.minecraft.world.phys.Vec3

// Entities on the server: every loaded one, in every dimension.
//
//   entities(type = "minecraft:zombie", near = Vec3(0.0, 64.0, 0.0), radius = 32.0).forEach { killEntity(it.id) }
//   val cow = spawn("minecraft:cow", Vec3(0.5, 80.0, 0.5))
//   onEvent<ServerEvent.EntitySpawn> { (entity) -> if (entity.type == "minecraft:phantom") removeEntity(entity.id) }

/**
 * Loaded entities (players too): in [dimension] (default: every dimension), of [type] (an id like
 * `minecraft:zombie`, or just `zombie`), within [radius] blocks of [near]. Nearest to [near] first when it's given.
 */
fun ServerMinekt.entities(
    dimension: String? = null,
    near: Vec3? = null,
    radius: Double = Double.MAX_VALUE,
    type: String? = null,
): List<EntityInfo> = server { s ->
    val typeId = type?.let { identifier(it).toString() }
    val levels = if (dimension == null) s.allLevels.toList() else listOf(level(s, dimension))
    levels.flatMap { level -> level.allEntities.filterNotNull() }
        .filter { typeId == null || BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() == typeId }
        .filter { near == null || it.position().distanceTo(near) <= radius }
        .map { it.toInfo(null, near) }
        .let { list -> if (near != null) list.sortedBy { it.distance } else list }
}

/** The loaded entity with [id] (from [entities] or an event), in any dimension, or null if it's gone. */
fun ServerMinekt.entity(id: Int): EntityInfo? = server { s -> find(s, id)?.toInfo(null) }

/**
 * Spawn a [type] (like `minecraft:zombie`) at [pos] in [dimension], like `/summon`: mobs get their usual random gear
 * and looks. Returns the new entity.
 */
fun ServerMinekt.spawn(type: String, pos: Vec3, dimension: String = "minecraft:overworld"): EntityInfo = server { s ->
    val level = level(s, dimension)
    val entityType = BuiltInRegistries.ENTITY_TYPE.getOptional(identifier(type)).orElseThrow { IllegalArgumentException("No entity type '$type'") }
    val entity = entityType.create(level, EntitySpawnReason.COMMAND) ?: throw IllegalArgumentException("$type can't be spawned")
    entity.snapTo(pos.x, pos.y, pos.z, entity.yRot, entity.xRot)
    if (entity is Mob) entity.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(pos)), EntitySpawnReason.COMMAND, null)
    check(level.tryAddFreshEntityWithPassengers(entity)) { "Couldn't spawn $type (is that place loaded?)" }
    entity.toInfo(null)
}

/** Kill the entity with [id], like `/kill`: it dies (drops its loot, counts as a death). False if it's gone. */
fun ServerMinekt.killEntity(id: Int): Boolean = server { s ->
    val entity = find(s, id) ?: return@server false
    entity.kill(entity.level() as net.minecraft.server.level.ServerLevel)
    true
}

/** Remove the entity with [id] without it dying: no loot, no death. Players can't be removed. False if it's gone. */
fun ServerMinekt.removeEntity(id: Int): Boolean = server { s ->
    val entity = find(s, id) ?: return@server false
    require(entity !is net.minecraft.world.entity.player.Player) { "Players can't be removed; use killEntity or kick them" }
    entity.discard()
    true
}

private fun find(server: MinecraftServer, id: Int): Entity? = server.allLevels.firstNotNullOfOrNull { it.getEntity(id) }
