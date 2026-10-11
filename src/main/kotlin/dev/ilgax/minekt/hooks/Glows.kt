package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.Color
import dev.ilgax.minekt.api.Glow
import dev.ilgax.minekt.job.Job
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.core.BlockPos as McBlockPos

/**
 * Glow outlines from scripts (`glow`, `glowBlock`). Entities get the game's own glowing outline
 * (through [dev.ilgax.minekt.mixin.EntityRendererMixin]); blocks get their shape drawn through walls
 * every frame. The newest glow on an entity wins.
 */
object Glows {
    /** Whether any glow exists, so the mixin does nothing otherwise. */
    @JvmField @Volatile var active = false

    private val glows = CopyOnWriteArrayList<Glow>()

    internal fun add(glow: Glow) {
        glows += glow
        active = true
    }

    internal fun remove(glow: Glow) {
        glows -= glow
        active = glows.isNotEmpty()
    }

    internal fun removeAll(job: Job) {
        glows.removeIf { it.job === job }
        active = glows.isNotEmpty()
    }

    internal fun has(job: Job) = glows.any { it.job === job }

    /** The outline color for entity [id] (opaque ARGB), or 0 for none. */
    @JvmStatic
    fun entityColor(id: Int): Int {
        for (i in glows.indices.reversed()) {
            val glow = glows.getOrNull(i) ?: continue
            if (glow.entity == id) return glow.color or (0xFF shl 24)
        }
        return 0
    }

    internal val hasBlocks get() = active && glows.any { it.block != null }

    /** Draw block glows; called while the frame's gizmos are being collected. */
    internal fun renderBlocks(level: ClientLevel) {
        for (glow in glows) {
            val pos = glow.block ?: continue
            val at = McBlockPos(pos.x, pos.y, pos.z)
            val shape = level.getBlockState(at).getShape(level, at)
            val boxes = if (shape.isEmpty) listOf(AABB(at)) else shape.toAabbs().map { it.move(at) }
            val color = glow.color or (0xFF shl 24)
            val style = if (glow.fill) GizmoStyle.strokeAndFill(color, 2f, Color.withAlpha(color, 0x40)) else GizmoStyle.stroke(color, 2f)
            for (box in boxes) Gizmos.cuboid(box.inflate(0.002), style).setAlwaysOnTop()
        }
    }
}
