package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.FakeBlock
import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.BlockPos as McBlockPos

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
    private data class Key(val dimension: String, val pos: McBlockPos)

    /** Fakes per block, newest last. */
    private val fakes = LinkedHashMap<Key, ArrayDeque<FakeBlock>>()

    /** What the server says is really there. */
    private val real = HashMap<Key, BlockState>()

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
        val key = Key(dimension(level), fake.mcPos)
        if (key !in real) real[key] = level.getBlockState(fake.mcPos)
        fakes.getOrPut(key) { ArrayDeque() } += fake
        keys[fake] = key
        active = true
        set(level, fake.mcPos, fake.state)
        return perJob.merge(fake.job, 1, Int::plus) == 1
    }

    internal fun remove(fake: FakeBlock) {
        val level = Minecraft.getInstance().level
        val key = keys.remove(fake) ?: return
        perJob.computeIfPresent(fake.job) { _, n -> (n - 1).takeIf { it > 0 } }
        val stack = fakes[key] ?: return
        stack.removeIf { it === fake }
        val here = level != null && dimension(level) == key.dimension && level.isLoaded(key.pos)
        if (stack.isEmpty()) {
            fakes.remove(key)
            val original = real.remove(key)
            if (here && original != null) set(level, key.pos, original)
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
    fun serverUpdate(level: ClientLevel, pos: McBlockPos, state: BlockState): Boolean {
        if (!active) return false
        val key = Key(dimension(level), pos)
        if (key !in fakes) return false
        real[key] = state
        return true
    }

    /** Put fakes back where something else replaced them. Every tick. */
    fun tick(level: ClientLevel) {
        if (!active) return
        val dimension = dimension(level)
        for ((key, stack) in fakes) {
            if (key.dimension != dimension || !level.isLoaded(key.pos)) continue
            val fake = stack.last().state
            val now = level.getBlockState(key.pos)
            if (now != fake) {
                real[key] = now
                set(level, key.pos, fake)
            }
        }
    }

    /** The real state at [pos], ignoring fakes (null if not faked). */
    internal fun realState(level: ClientLevel, pos: McBlockPos): BlockState? = real[Key(dimension(level), pos)]

    private fun set(level: ClientLevel, pos: McBlockPos, state: BlockState) {
        applying = true
        try {
            level.setBlock(pos, state, FLAGS)
        } finally {
            applying = false
        }
    }

    private fun dimension(level: ClientLevel) = level.dimension().identifier().toString()
}
