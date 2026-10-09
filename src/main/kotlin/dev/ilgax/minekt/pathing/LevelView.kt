package dev.ilgax.minekt.pathing

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.chunk.EmptyLevelChunk

/** The client's world as the planner sees it. Use on the game thread only. */
internal class LevelView(private val level: ClientLevel, private val avoid: Set<String>) : BlockView {
    private val cursor = BlockPos.MutableBlockPos()

    override fun cell(x: Int, y: Int, z: Int): Cell {
        if (y < level.minY) return Cell.HAZARD // the void
        if (y > level.maxY) return Cell.OPEN
        if (level.getChunk(x shr 4, z shr 4) is EmptyLevelChunk) return Cell.UNKNOWN
        cursor.set(x, y, z)
        val state = level.getBlockState(cursor)
        val fluid = state.fluidState
        if (!fluid.isEmpty) {
            val id = BuiltInRegistries.FLUID.getKey(fluid.type).path
            if ("lava" in id) return Cell.HAZARD
        }
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block).toString()
        if (blockId in HAZARDS || blockId in avoid) return Cell.HAZARD
        if (!fluid.isEmpty && "water" in BuiltInRegistries.FLUID.getKey(fluid.type).path) {
            // Waterlogged blocks with a collision shape (stairs, slabs) are still blocks.
            if (state.getCollisionShape(level, cursor).isEmpty) return Cell.WATER
        }
        val shape = state.getCollisionShape(level, cursor)
        return when {
            shape.isEmpty -> Cell.OPEN
            shape.max(Direction.Axis.Y) > 1.0 -> Cell.TALL
            else -> Cell.SOLID
        }
    }

    companion object {
        /** Blocks that hurt or trap: never walked into or onto. */
        val HAZARDS = setOf(
            "minecraft:fire", "minecraft:soul_fire", "minecraft:cactus", "minecraft:magma_block",
            "minecraft:campfire", "minecraft:soul_campfire", "minecraft:sweet_berry_bush", "minecraft:wither_rose",
            "minecraft:powder_snow", "minecraft:cobweb", "minecraft:lava_cauldron", "minecraft:pointed_dripstone",
        )
    }
}
