@file:OptIn(dev.ilgax.minekt.api.ExperimentalVisuals::class)

package dev.ilgax.minekt.hooks

import net.minecraft.core.BlockPos
import dev.ilgax.minekt.api.FakeBlock
import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import java.util.Optional

/**
 * Blocks that scripts show only on this client (`fakeBlock`). Game thread only.
 *
 * The fake state is set in the client's world, so it looks and collides like the real thing (the
 * server doesn't know: walking on a fake block gets you pulled back). The real state is kept and put
 * back when the fake is removed. Server updates to a faked block only change that kept state, and
 * [tick] puts fakes back after anything else replaced them (a chunk loading again, a client-side
 * prediction). With several fakes on one block, the newest shows.
 */
object FakeBlocks {
    private data class Key(val dimension: String, val pos: BlockPos)
    private class RealBlock(val state: BlockState, val entity: BlockEntity?)
    private var currentLevel: ClientLevel? = null

    /** Fakes per block, newest last. */
    private val fakes = LinkedHashMap<Key, ArrayDeque<FakeBlock>>()

    /** What the server says is really there. */
    private val real = HashMap<Key, RealBlock>()

    /** Where each fake is. */
    private val keys = java.util.IdentityHashMap<FakeBlock, Key>()

    /** How many fakes each script shows. */
    private val perJob = java.util.concurrent.ConcurrentHashMap<Job, Int>()

    /** Set while Minekt itself changes a block, so it isn't reported to scripts as a block change. */
    @JvmField var applying = false

    @JvmField @Volatile var active = false

    // Set the block only in this client's world: no neighbor updates, no changing neighbors' shapes, no drops.
    private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE or Block.UPDATE_SUPPRESS_DROPS

    internal fun has(job: Job) = (perJob[job] ?: 0) > 0

    /** Show [fake]; returns false if it was this script's first. */
    internal fun add(level: ClientLevel, fake: FakeBlock): Boolean {
        useLevel(level)
        val key = Key(dimension(level), fake.mcPos)
        if (key !in real) real[key] = capture(level, fake.mcPos)
        fakes.getOrPut(key) { ArrayDeque() } += fake
        keys[fake] = key
        active = true
        set(level, fake.mcPos, fake.state)
        return perJob.merge(fake.job, 1, Int::plus) == 1
    }

    internal fun remove(fake: FakeBlock) {
        val level = Minecraft.getInstance().level
        useLevel(level)
        val key = keys.remove(fake) ?: return
        perJob.computeIfPresent(fake.job) { _, n -> (n - 1).takeIf { it > 0 } }
        val stack = fakes[key] ?: return
        stack.removeIf { it === fake }
        val here = level != null && dimension(level) == key.dimension && level.isLoaded(key.pos)
        if (stack.isEmpty()) {
            fakes.remove(key)
            val original = real.remove(key)
            if (here && original != null) restore(level, key.pos, original)
        } else if (here) {
            set(level, key.pos, stack.last().state)
        }
        active = fakes.isNotEmpty()
    }

    internal fun removeAll(job: Job) {
        if (has(job)) keys.keys.filter { it.job === job }.forEach(::remove)
    }

    /**
     * A block update from the server (from the mixin): for a faked block, remember it as the real state
     * and keep showing the fake. Returns whether the update was kept from the world.
     */
    @JvmStatic
    fun serverUpdate(level: ClientLevel, pos: BlockPos, state: BlockState): Boolean {
        if (!active) return false
        useLevel(level)
        val key = Key(dimension(level), pos)
        if (key !in fakes) return false
        val previous = real.getValue(key)
        val entity = if (previous.state.block === state.block) previous.entity
            else (state.block as? EntityBlock)?.newBlockEntity(pos, state)
        entity?.let { it.blockState = state; it.setLevel(level) }
        real[key] = RealBlock(state, entity)
        return true
    }

    /** Server data belongs to the real block entity, even when a fake hides it. */
    @JvmStatic
    @Suppress("UNCHECKED_CAST")
    fun <T : BlockEntity> blockEntityForUpdate(level: ClientLevel, pos: BlockPos, type: BlockEntityType<T>): Optional<T> {
        useLevel(level)
        val original = real[Key(dimension(level), pos)] ?: return level.getBlockEntity(pos, type)
        val entity = original.entity
        return if (entity != null && entity.type === type) Optional.of(entity as T) else Optional.empty()
    }

    /** Chunk packets replace both states and block entities, including ones with the fake's state. */
    fun chunkLoaded(level: ClientLevel, chunk: LevelChunk) {
        useLevel(level)
        if (!active) return
        for ((key, stack) in fakes) {
            if (key.pos.x shr 4 != chunk.pos.x() || key.pos.z shr 4 != chunk.pos.z()) continue
            real[key] = capture(level, key.pos)
            set(level, key.pos, stack.last().state)
        }
    }

    /** Put fakes back where something else replaced them. Every tick. */
    fun tick(level: ClientLevel?) {
        useLevel(level)
        if (level == null || !active) return
        val dimension = dimension(level)
        for ((key, stack) in fakes) {
            if (key.dimension != dimension || !level.isLoaded(key.pos)) continue
            val fake = stack.last().state
            val now = level.getBlockState(key.pos)
            if (now != fake) {
                real[key] = capture(level, key.pos)
                set(level, key.pos, fake)
            }
        }
    }

    /** The real state at [pos], ignoring fakes (null if not faked). */
    internal fun realState(level: ClientLevel, pos: BlockPos): BlockState? =
        if (level === currentLevel) real[Key(dimension(level), pos)]?.state else null

    private fun capture(level: ClientLevel, pos: BlockPos) = RealBlock(level.getBlockState(pos), level.getBlockEntity(pos))

    private fun restore(level: ClientLevel, pos: BlockPos, original: RealBlock) {
        set(level, pos, original.state)
        original.entity?.let { entity ->
            entity.blockState = original.state
            level.setBlockEntity(entity)
        }
    }

    /** ClientLevel identity changes across connections and dimensions; old handles become inert. */
    private fun useLevel(level: ClientLevel?) {
        if (currentLevel === level) return
        fakes.clear()
        real.clear()
        keys.clear()
        perJob.clear()
        active = false
        currentLevel = level
    }

    private fun set(level: ClientLevel, pos: BlockPos, state: BlockState) {
        applying = true
        try {
            level.setBlock(pos, state, FLAGS)
        } finally {
            applying = false
        }
    }

    private fun dimension(level: ClientLevel) = level.dimension().identifier().toString()
}
