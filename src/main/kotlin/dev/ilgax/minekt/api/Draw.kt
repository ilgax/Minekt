package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gizmos.GizmoProperties
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import java.nio.file.Path
import net.minecraft.world.phys.Vec3 as McVec3

// Drawing every frame:
//
//   onHudRender { g ->
//       g.rect(g.width / 2 - 1, g.height / 2 - 1, 2, 2, Color.RED)   // a red dot on the crosshair
//       g.text("Hello", 10, 10)
//   }
//   onWorldRender { w ->
//       for (id in targets) w.entityPosition(id)?.let { w.line(w.cameraPos, it, Color.RED) }   // tracers
//       w.box(Vec3(0.0, 64.0, 0.0), Vec3(1.0, 65.0, 1.0), Color.GREEN, throughWalls = true)
//   }
//
// Handlers run on the game's thread every frame, before it draws, so they must be quick; `game { }`
// inside them runs directly. `g.raw` is the game's own HUD drawing context, for anything not covered.
// A handler that throws is removed and its error shown. They keep the script alive.

/** Run [handler] every frame while the HUD is drawn (not when it's hidden with F1). */
fun Minekt.onHudRender(handler: (HudCanvas) -> Unit) {
    job.checkAlive()
    job.hudRenderHandlers += handler
}

/** Run [handler] every frame while the world is drawn, to draw lines, boxes and text in it. */
fun Minekt.onWorldRender(handler: (WorldCanvas) -> Unit) {
    job.checkAlive()
    job.worldRenderHandlers += handler
}

/**
 * The HUD, in GUI pixels ([width] × [height], scaled like the game's own HUD; 0, 0 is the top left).
 * Colors are 0xRRGGBB (fully opaque) or 0xAARRGGBB.
 */
class HudCanvas internal constructor(
    /** The game's own drawing context, for anything not covered here. */
    val raw: GuiGraphicsExtractor,
    /** How far the frame is between two ticks (0–1), to move things smoothly. */
    val partialTick: Float,
) {
    internal var job: Job? = null
    private val font get() = Minecraft.getInstance().font

    val width: Int get() = raw.guiWidth()
    val height: Int get() = raw.guiHeight()

    /** A filled rectangle. */
    fun rect(x: Int, y: Int, width: Int, height: Int, color: Int) = raw.fill(x, y, x + width, y + height, opaque(color))

    /** A rectangle's 1-pixel outline. */
    fun outline(x: Int, y: Int, width: Int, height: Int, color: Int) = raw.outline(x, y, width, height, opaque(color))

    /** [text] with its top left at [x], [y]; returns its width. `§` color codes work. */
    fun text(text: String, x: Int, y: Int, color: Int = Color.WHITE, shadow: Boolean = true): Int {
        raw.text(font, text, x, y, opaque(color), shadow)
        return font.width(text)
    }

    /** How wide [text] is, in GUI pixels. */
    fun textWidth(text: String): Int = font.width(text)

    /** The height of a line of text. */
    val lineHeight: Int get() = font.lineHeight

    /** An item icon (16 × 16), like `"minecraft:diamond"`, with [count] drawn on it when above 1. */
    fun item(id: String, x: Int, y: Int, count: Int = 1) {
        val item = BuiltInRegistries.ITEM.getOptional(Identifier.parse(id)).orElseThrow { IllegalArgumentException("Unknown item '$id'") }
        val stack = ItemStack(item, count)
        raw.item(stack, x, y)
        if (count > 1) raw.itemDecorations(font, stack, x, y)
    }

    /** A PNG file (relative paths are inside `minekt/`), stretched to [width] × [height]. */
    fun image(file: String, x: Int, y: Int, width: Int, height: Int) {
        val job = job ?: return
        val path = Path.of(file).let { if (it.isAbsolute) it else job.host.scriptDir.resolve(it) }.normalize()
        HudImages.get(path, reload = false, job)
        val texture = HudImages.loaded(path)
        if (texture != null) raw.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, width, height, width, height)
        else raw.fill(x, y, x + width, y + height, 0x80FF00FF.toInt())
    }

    /**
     * Draw [pixels] with the top left at [x], [y], stretched to [width] × [height] (sharp, without
     * blurring: double the size for each pixel to be 2 × 2).
     */
    fun pixels(pixels: Pixels, x: Int, y: Int, width: Int = pixels.width, height: Int = pixels.height) {
        val id = pixels.texture()
        raw.blit(RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, width, height, pixels.width, pixels.height, pixels.width, pixels.height)
    }
}

/** The world, in block coordinates. Colors are 0xRRGGBB or 0xAARRGGBB. */
class WorldCanvas internal constructor(
    /** How far the frame is between two ticks (0–1). */
    val partialTick: Float,
    /** Where the camera is this frame. */
    val cameraPos: Vec3,
) {
    /** A line; [throughWalls] draws it on top of everything. */
    fun line(from: Vec3, to: Vec3, color: Int, width: Float = 2f, throughWalls: Boolean = false) =
        Gizmos.line(from.mc(), to.mc(), opaque(color), width).onTop(throughWalls)

    fun arrow(from: Vec3, to: Vec3, color: Int, width: Float = 2f, throughWalls: Boolean = false) =
        Gizmos.arrow(from.mc(), to.mc(), opaque(color), width).onTop(throughWalls)

    /** A box from corner [min] to corner [max]: an outline, or [fill]ed see-through. */
    fun box(min: Vec3, max: Vec3, color: Int, fill: Boolean = false, width: Float = 2f, throughWalls: Boolean = false) =
        Gizmos.cuboid(
            AABB(min.mc(), max.mc()),
            if (fill) GizmoStyle.strokeAndFill(opaque(color), width, Color.withAlpha(color, 0x40)) else GizmoStyle.stroke(opaque(color), width),
        ).onTop(throughWalls)

    /** A dot [size] pixels across. */
    fun point(pos: Vec3, color: Int, size: Float = 4f, throughWalls: Boolean = false) =
        Gizmos.point(pos.mc(), opaque(color), size).onTop(throughWalls)

    /** Text that always faces the camera. */
    fun text(pos: Vec3, text: String, color: Int = Color.WHITE, scale: Float = 1f, throughWalls: Boolean = false) =
        Gizmos.billboardText(text, pos.mc(), TextGizmo.Style.forColorAndCentered(opaque(color)).withScale(TextGizmo.Style.DEFAULT_SCALE * scale))
            .onTop(throughWalls)

    /**
     * Where entity [id] is drawn this frame (between its last two ticks), or null if it's gone.
     * Use it to draw on moving entities without jitter; `entities()` gives positions once per tick.
     */
    fun entityPosition(id: Int): Vec3? =
        Minecraft.getInstance().level?.getEntity(id)?.getPosition(partialTick)?.let { Vec3(it.x, it.y, it.z) }

    private fun GizmoProperties.onTop(throughWalls: Boolean) {
        if (throughWalls) setAlwaysOnTop()
    }

    private fun Vec3.mc() = McVec3(x, y, z)
}

/** 0xRRGGBB colors are made opaque; ones with an alpha byte are kept. */
private fun opaque(color: Int) = if (color ushr 24 == 0) color or (0xFF shl 24) else color
