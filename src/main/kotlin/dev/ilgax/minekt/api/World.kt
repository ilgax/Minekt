package dev.ilgax.minekt.api

import net.minecraft.core.BlockPos
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.chunk.EmptyLevelChunk
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.max
import kotlin.math.min

// World state: time, weather, loaded chunks, and scanning regions without freezing the game.

private const val TICKS_PER_DAY = 24000L
private const val NIGHT_START = 13000L

val Minekt.world: WorldInfo
    get() = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val time = Math.floorMod(level.defaultClockTime, TICKS_PER_DAY)
        WorldInfo(
            dimension = level.dimension().identifier().toString(),
            timeOfDay = time,
            gameTime = level.gameTime,
            isDay = time < NIGHT_START,
            isRaining = level.isRaining,
            isThundering = level.isThundering,
        )
    }

/** Whether the chunk containing block x/z is loaded on the client. */
fun Minekt.isLoaded(x: Int, z: Int): Boolean = game { mc -> mc.level?.isChunkLoaded(x shr 4, z shr 4) == true }

/** Client chunk caches hand back a placeholder [EmptyLevelChunk] for missing chunks, so `hasChunk` is always true. */
private fun ClientLevel.isChunkLoaded(chunkX: Int, chunkZ: Int) = getChunk(chunkX, chunkZ) !is EmptyLevelChunk

/**
 * Every block in the box [from]–[to] (inclusive) whose id matches [predicate], e.g.
 * `scanRegion(BlockPos(-64, -64, -64), BlockPos(64, 64, 64)) { it == "minecraft:diamond_ore" }`.
 *
 * Unlike `findBlocks`, this handles big regions: unloaded chunks and all-air chunk sections are
 * skipped, and the work is split across frames ([blocksPerFrame] per batch) so the game keeps
 * running smoothly. Stops early once [limit] matches are found. [predicate] runs on the game
 * thread, so keep it cheap.
 */
fun Minekt.scanRegion(
    from: BlockPos,
    to: BlockPos,
    limit: Int = Int.MAX_VALUE,
    blocksPerFrame: Int = 262_144,
    predicate: (String) -> Boolean,
): List<FoundBlock> {
    val minX = min(from.x, to.x); val maxX = max(from.x, to.x)
    val minZ = min(from.z, to.z); val maxZ = max(from.z, to.z)
    val (minY, maxY) = game { mc ->
        val level = mc.level ?: error("Not in a world")
        max(min(from.y, to.y), level.minY) to min(max(from.y, to.y), level.maxY)
    }
    val found = mutableListOf<FoundBlock>()
    // Cache id lookups per block state: the predicate runs once per distinct state, not per block.
    val matches = HashMap<BlockState, String?>()
    val chunks = ((minX shr 4)..(maxX shr 4)).flatMap { cx -> ((minZ shr 4)..(maxZ shr 4)).map { cz -> cx to cz } }
    var next = 0
    while (next < chunks.size && found.size < limit) {
        game { mc ->
            val level = mc.level ?: error("Not in a world")
            var budget = blocksPerFrame
            while (budget > 0 && next < chunks.size && found.size < limit) {
                val (cx, cz) = chunks[next++]
                val chunk = level.getChunk(cx, cz)
                if (chunk is EmptyLevelChunk) continue // not loaded
                val x0 = max(minX, cx shl 4); val x1 = min(maxX, (cx shl 4) + 15)
                val z0 = max(minZ, cz shl 4); val z1 = min(maxZ, (cz shl 4) + 15)
                for (sy in (minY shr 4)..(maxY shr 4)) {
                    val section = chunk.getSection(sy - (level.minY shr 4))
                    if (section.hasOnlyAir()) continue
                    for (y in max(minY, sy shl 4)..min(maxY, (sy shl 4) + 15)) {
                        for (x in x0..x1) for (z in z0..z1) {
                            val state = section.getBlockState(x and 15, y and 15, z and 15)
                            val id = matches.getOrPut(state) {
                                BuiltInRegistries.BLOCK.getKey(state.block).toString().takeIf(predicate)
                            }
                            if (id != null && found.size < limit) found += FoundBlock(BlockPos(x, y, z), id)
                        }
                    }
                    budget -= (x1 - x0 + 1) * (z1 - z0 + 1) * 16
                }
            }
        }
    }
    return found
}
