@file:OptIn(dev.ilgax.minekt.api.ExperimentalVisuals::class)

package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.clientHost
import net.minecraft.core.BlockPos
import dev.ilgax.minekt.hooks.FakeBlocks
import dev.ilgax.minekt.job.Job
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState

// Blocks only you can see:
//
//   val marker = fakeBlock(10, 64, 10, "minecraft:glowstone")
//   fakeBlock(player.position.blockPos.offset(dy = -1), "minecraft:oak_stairs[facing=east]")
//   marker.remove()                                         // the real block comes back
//   fakeBlocks(plan.associate { it.pos to it.block })      // many at once, one game-thread hop
//
// The server doesn't know about them: they look and feel solid to you, but standing or walking on
// one gets you pulled back, and breaking one only shows the real block again. They come off when the
// script ends (like shapes, they don't keep it running). `blockAt` and `getBlock` see the fake.

/** A block shown only on this client, from [fakeBlock]. */
@ExperimentalVisuals
class FakeBlock internal constructor(
    internal val job: Job,
    val pos: BlockPos,
    /** The block shown, like `minecraft:oak_stairs[facing=east]`. */
    val block: String,
    internal val state: BlockState,
) {
    internal val mcPos: BlockPos = pos.immutable()

    /** Show the real block again. */
    fun remove() = job.clientHost.onGameThread { FakeBlocks.remove(this) }
}

/**
 * Show [block] at [pos], only on this client. [block] is an id like `minecraft:glass`, with block
 * state properties if you want them: `minecraft:oak_stairs[facing=east,half=top]`.
 */
@ExperimentalVisuals
fun Minekt.fakeBlock(pos: BlockPos, block: String): FakeBlock = fakeBlocks(mapOf(pos to block)).single()

@ExperimentalVisuals
fun Minekt.fakeBlock(x: Int, y: Int, z: Int, block: String): FakeBlock = fakeBlock(BlockPos(x, y, z), block)

/** Many fake blocks at once (faster than one at a time: one trip to the game thread). */
@ExperimentalVisuals
fun Minekt.fakeBlocks(blocks: Map<BlockPos, String>): List<FakeBlock> {
    job.checkAlive()
    val parsed = blocks.map { (pos, block) -> FakeBlock(job, pos, block, parseBlockState(block)) }
    return game { mc ->
        val level = mc.level ?: error("Not in a world")
        for (fake in parsed) {
            // One cleanup per script, however many fakes it shows.
            if (FakeBlocks.add(level, fake)) job.cleanups += { job.clientHost.onGameThread { FakeBlocks.removeAll(job) } }
        }
        parsed
    }
}

/** Remove every fake block this script shows. */
@ExperimentalVisuals
fun Minekt.clearFakeBlocks() {
    game { FakeBlocks.removeAll(job) }
}

internal fun parseBlockState(block: String): BlockState = try {
    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, block, false).blockState()
} catch (e: Exception) {
    throw IllegalArgumentException("'$block' isn't a block: ${e.message}")
}
