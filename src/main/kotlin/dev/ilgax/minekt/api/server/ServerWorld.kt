package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.BlockInfo
import dev.ilgax.minekt.api.FoundBlock
import dev.ilgax.minekt.api.RayHit
import dev.ilgax.minekt.api.WorldInfo
import dev.ilgax.minekt.api.blockInfo
import dev.ilgax.minekt.api.raycast
import dev.ilgax.minekt.api.toInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.Vec3

// The world on the server: blocks, rays, time and weather.
//
//   findBlocks(BlockPos(0, 64, 0), 16) { it == "minecraft:chest" }.forEach { echo(it.pos) }
//   when (val hit = raycast("Steve", 50.0)) { is RayHit.Block -> echo("Steve looks at", hit.id) … }
//   if (world().isRaining) setWeather("clear")
//   atDaytime(13000) { broadcast("Night is falling") }

/** Time and weather in [dimension]. Time of day is the overworld's in every dimension that follows it. */
fun ServerMinekt.world(dimension: String = "minecraft:overworld"): WorldInfo = server { s ->
    val level = level(s, dimension)
    val time = Math.floorMod(level.defaultClockTime, 24000L)
    WorldInfo(
        dimension = level.dimension().identifier().toString(),
        timeOfDay = time,
        gameTime = level.gameTime,
        isDay = time < 13000,
        // The weather as set, not as it looks: rain fades in and out over a few seconds.
        isRaining = level.canHaveWeather() && level.weatherData.isRaining,
        isThundering = level.canHaveWeather() && level.weatherData.isThundering,
    )
}

/** The time of day, 0–23999 (0 sunrise, 6000 noon, 13000 nightfall, 18000 midnight). */
val ServerMinekt.dayTime: Long get() = world().timeOfDay

/** Set the time of day, like `/time set` ([dayTime] 0–23999: 0 sunrise, 6000 noon, 13000 nightfall, 18000 midnight). */
fun ServerMinekt.setTime(dayTime: Long) {
    require(dayTime in 0..23999) { "dayTime must be 0..23999" }
    runCommand("time set $dayTime")
}

/** `clear`, `rain` or `thunder`. */
val ServerMinekt.weather: String get() = world().let { if (it.isThundering) "thunder" else if (it.isRaining) "rain" else "clear" }

/** Change the weather, like `/weather`: [weather] is `clear`, `rain` or `thunder`, for [seconds] (or the usual random time). */
fun ServerMinekt.setWeather(weather: String, seconds: Int? = null) {
    require(weather in listOf("clear", "rain", "thunder")) { "weather is clear, rain or thunder, not '$weather'" }
    require(seconds == null || seconds > 0) { "seconds must be positive" }
    runCommand("weather $weather" + seconds?.let { " ${it}s" }.orEmpty())
}

/** Everything about the block at [pos] in [dimension]: its state, light, biome, and a sign's text. */
fun ServerMinekt.blockAt(pos: BlockPos, dimension: String = "minecraft:overworld"): BlockInfo = server { s ->
    level(s, dimension).blockInfo(pos.immutable())
}

fun ServerMinekt.blockAt(x: Int, y: Int, z: Int, dimension: String = "minecraft:overworld"): BlockInfo = blockAt(BlockPos(x, y, z), dimension)

/**
 * Every block within [radius] (at most 64) of [center] in [dimension] whose id matches [predicate], nearest first.
 * Only loaded chunks are searched: nothing is loaded for it. [predicate] runs on the server thread, so keep it quick.
 */
fun ServerMinekt.findBlocks(
    center: BlockPos,
    radius: Int,
    dimension: String = "minecraft:overworld",
    predicate: (String) -> Boolean,
): List<FoundBlock> {
    require(radius in 0..64) { "radius must be 0..64" }
    return server { s ->
        val level = level(s, dimension)
        val found = mutableListOf<FoundBlock>()
        val cursor = BlockPos.MutableBlockPos()
        val ids = HashMap<net.minecraft.world.level.block.state.BlockState, String?>()
        for (dx in -radius..radius) for (dz in -radius..radius) {
            val x = center.x + dx
            val z = center.z + dz
            if (!level.hasChunk(x shr 4, z shr 4)) continue
            for (dy in -radius..radius) {
                cursor.set(x, center.y + dy, z)
                val state = level.getBlockState(cursor)
                val id = ids.getOrPut(state) { BuiltInRegistries.BLOCK.getKey(state.block).toString().takeIf(predicate) } ?: continue
                found += FoundBlock(cursor.immutable(), id)
            }
        }
        found.sortedBy { it.pos.distSqr(center) }
    }
}

/**
 * The first block or entity on the line [from] → [to] in [dimension], or null if it's clear. [blocks], [entities],
 * [fluids] and [solidOnly] work as in client scripts' `raycast`. Unloaded chunks count as empty.
 */
fun ServerMinekt.raycast(
    from: Vec3,
    to: Vec3,
    dimension: String = "minecraft:overworld",
    blocks: Boolean = true,
    entities: Boolean = true,
    fluids: Boolean = false,
    solidOnly: Boolean = false,
): RayHit? = server { s ->
    level(s, dimension).raycast(from, to, blocks, entities, fluids, solidOnly, null) { it.toInfo(null, from) }
}

/** What [player] is looking at, up to [distance] blocks from their eyes (they never block their own view). */
fun ServerMinekt.raycast(
    player: String,
    distance: Double = 5.0,
    blocks: Boolean = true,
    entities: Boolean = true,
    fluids: Boolean = false,
    solidOnly: Boolean = false,
): RayHit? = server { s ->
    val p = online(s, player)
    val eyes = p.eyePosition
    p.level().raycast(eyes, eyes.add(p.lookAngle.scale(distance)), blocks, entities, fluids, solidOnly, p) { it.toInfo(null, eyes) }
}
