package dev.ilgax.minekt.hooks

import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import dev.ilgax.minekt.api.Color
import dev.ilgax.minekt.api.Shape
import dev.ilgax.minekt.api.ShapeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoProperties
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.AABB

/**
 * Draws script shapes with Minecraft's gizmo system. Must be called from the end of the client
 * tick: `Minecraft.tick()` runs inside the per-tick gizmo collector, and the game renders those
 * gizmos every frame until the next tick, so re-submitting each tick keeps them on screen.
 */
internal object ScriptRenderer {
    private const val FILL_ALPHA = 0x40
    private const val BOX_STROKE_WIDTH = 2f

    fun submitTick(registry: ShapeRegistry = ShapeRegistry.global) {
        for (shape in registry.all()) {
            val props: GizmoProperties = when (shape) {
                is Shape.Box -> Gizmos.cuboid(
                    AABB(shape.min, shape.max),
                    if (shape.fill) GizmoStyle.strokeAndFill(shape.color, BOX_STROKE_WIDTH, Color.withAlpha(shape.color, FILL_ALPHA))
                    else GizmoStyle.stroke(shape.color, BOX_STROKE_WIDTH),
                )
                is Shape.Line ->
                    if (shape.arrow) Gizmos.arrow(shape.from, shape.to, shape.color, shape.width)
                    else Gizmos.line(shape.from, shape.to, shape.color, shape.width)
                is Shape.BlockOutline -> {
                    val level = Minecraft.getInstance().level ?: continue
                    val at = shape.pos
                    val blockShape = level.getBlockState(at).getShape(level, at)
                    val boxes = if (blockShape.isEmpty) listOf(AABB(at)) else blockShape.toAabbs().map { it.move(at) }
                    val style = if (shape.fill) GizmoStyle.strokeAndFill(shape.color, BOX_STROKE_WIDTH, Color.withAlpha(shape.color, FILL_ALPHA))
                    else GizmoStyle.stroke(shape.color, BOX_STROKE_WIDTH)
                    // Each box of the shape; only the last one's properties are set below, so set them here.
                    for (box in boxes.dropLast(1)) Gizmos.cuboid(box.inflate(0.002), style).also { if (shape.throughWalls) it.setAlwaysOnTop() }
                    Gizmos.cuboid(boxes.last().inflate(0.002), style)
                }
                is Shape.Text -> Gizmos.billboardText(
                    shape.text,
                    shape.pos,
                    TextGizmo.Style.forColorAndCentered(shape.color).withScale(TextGizmo.Style.DEFAULT_SCALE * shape.scale),
                )
            }
            if (shape.throughWalls) props.setAlwaysOnTop()
        }
    }
}
