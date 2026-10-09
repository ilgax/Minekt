package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.BlockPos
import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.Vec3
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.core.BlockPos as McBlockPos

/**
 * Entry points for the block and sound mixins. [emit] is set by [GameEventSource]; the `want…`
 * flags are refreshed every tick so the mixins do nothing while no script listens.
 */
object WorldHooks {
    @JvmField @Volatile var wantBlocks = false
    @JvmField @Volatile var wantSounds = false
    @Volatile internal var emit: (GameEvent) -> Unit = {}

    /** Old states of blocks being set; a stack because setting one block can set others. */
    private val pending = ArrayDeque<BlockState>()

    @JvmStatic
    fun beforeSetBlock(level: ClientLevel, pos: McBlockPos) {
        pending.addLast(level.getBlockState(pos))
    }

    /** Compares with what's there now: a predicted or refused change may leave the block as it was. */
    @JvmStatic
    fun afterSetBlock(level: ClientLevel, pos: McBlockPos) {
        val old = pending.removeLastOrNull() ?: return
        val now = level.getBlockState(pos)
        if (old.block === now.block) return
        emit(GameEvent.BlockChange(BlockPos(pos.x, pos.y, pos.z), id(old), id(now)))
    }

    @JvmStatic
    fun sound(sound: SoundInstance) {
        emit(GameEvent.Sound(sound.identifier.toString(), Vec3(sound.x, sound.y, sound.z), sound.source.getName()))
    }

    private fun id(state: BlockState) = BuiltInRegistries.BLOCK.getKey(state.block).toString()
}
