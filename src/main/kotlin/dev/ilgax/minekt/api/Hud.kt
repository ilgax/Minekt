package dev.ilgax.minekt.api

import com.mojang.blaze3d.platform.NativeImage
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.ScriptLogs
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

// HUD panels: boxes of text, bars, item icons and images pinned to a corner or edge of the screen.
//
//   val panel = hudPanel(Anchor.TOP_RIGHT) {
//       title("Miner")
//       text { "Ores: $ores" }                        // live: re-read every tick
//       bar("Durability", Color.GREEN) { durability() } // 0.0–1.0
//       row { item("minecraft:diamond") { "$diamonds" }; item("minecraft:iron_ingot") { "$iron" } }
//   }
//   panel.update { title("Done") }   // replace what it shows
//   panel.remove()                   // or let the script end
//
// Live parts (`{ … }`) run on the game thread once per tick, so keep them quick: read variables and
// the player, don't sleep or wait. Text can use `§` color codes (`"§aok §cfail"`).

/** Where on the screen a HUD panel sits. Panels at the same anchor stack instead of overlapping. */
enum class Anchor { TOP_LEFT, TOP, TOP_RIGHT, LEFT, CENTER, RIGHT, BOTTOM_LEFT, BOTTOM, BOTTOM_RIGHT }

/**
 * Show a HUD panel at [anchor], moved by [x], [y] pixels (GUI-scaled). [background] draws a dark box
 * behind it; [scale] makes it bigger or smaller. It stays until [HudPanel.remove] or the script ends.
 */
fun Minekt.hudPanel(
    anchor: Anchor = Anchor.TOP_LEFT,
    x: Int = 0,
    y: Int = 0,
    background: Boolean = true,
    scale: Float = 1f,
    build: HudBuilder.() -> Unit,
): HudPanel {
    job.checkAlive()
    require(scale > 0f) { "scale must be above 0" }
    val nodes = HudBuilder(job.host.scriptDir).apply(build).nodes
    return HudPanel(job, ScriptHud.add(job, nodes, anchor, x, y, background, scale))
}

@DslMarker
annotation class HudDsl

/** What a HUD panel shows, top to bottom. See [hudPanel]. */
@HudDsl
class HudBuilder internal constructor(private val baseDir: Path) {
    internal val nodes = mutableListOf<HudNode>()

    /** A heading, bold. */
    fun title(text: String, color: Int = Color.YELLOW) {
        nodes += HudNode.Text(null, color, bold = true, text)
    }

    /** A line of text (`\n` for more lines). */
    fun text(text: String, color: Int = Color.WHITE) {
        nodes += HudNode.Text(null, color, bold = false, text)
    }

    /** Text recomputed every tick, e.g. `text { "HP ${player.health}" }`. */
    fun text(color: Int = Color.WHITE, live: () -> String) {
        nodes += HudNode.Text(live, color, bold = false, "")
    }

    /** A progress bar, filled to [value] out of [max], with an optional label and percentage above it. */
    fun bar(value: Double, max: Double = 1.0, label: String? = null, color: Int = Color.GREEN, width: Int = 100) {
        require(max > 0) { "max must be above 0" }
        nodes += HudNode.Bar(label, null, max, color, width).also { it.value = value }
    }

    /** A progress bar whose value ([live], out of [max]) is re-read every tick. */
    fun bar(label: String? = null, color: Int = Color.GREEN, width: Int = 100, max: Double = 1.0, live: () -> Double) {
        require(max > 0) { "max must be above 0" }
        nodes += HudNode.Bar(label, live, max, color, width)
    }

    /** An item's icon (with [count] if above 1) and [text] beside it. */
    fun item(id: String, text: String = "", count: Int = 1) {
        nodes += HudNode.Item(itemId(id), count, null, text)
    }

    /** An item's icon with text recomputed every tick. */
    fun item(id: String, live: () -> String) {
        nodes += HudNode.Item(itemId(id), 1, live, "")
    }

    /**
     * A PNG image, drawn [width]×[height] pixels. [path] is relative to the scripts folder
     * (`minekt/`) unless absolute. Changes to the file show up while the script runs.
     */
    fun image(path: String, width: Int, height: Int) {
        require(width > 0 && height > 0) { "image size must be above 0" }
        val file = Path.of(path).let { if (it.isAbsolute) it else baseDir.resolve(it) }.normalize()
        nodes += HudNode.Image(file, width, height)
    }

    /** Empty space. */
    fun space(height: Int = 4) {
        nodes += HudNode.Space(height)
    }

    /** A thin horizontal line. */
    fun separator(color: Int = Color.withAlpha(Color.WHITE, 0x50)) {
        nodes += HudNode.Separator(color)
    }

    /** Put things side by side. */
    fun row(build: HudBuilder.() -> Unit) {
        nodes += HudNode.Row(HudBuilder(baseDir).apply(build).nodes)
    }

    private fun itemId(id: String): Identifier {
        val identifier = Identifier.parse(if (':' in id) id else "minecraft:$id")
        require(BuiltInRegistries.ITEM.containsKey(identifier)) { "Unknown item '$id'" }
        return identifier
    }
}

/** A panel shown by [hudPanel]. */
class HudPanel internal constructor(private val job: Job, private val id: Long) {
    /** Replace what the panel shows. */
    fun update(build: HudBuilder.() -> Unit) {
        job.checkAlive()
        val nodes = HudBuilder(job.host.scriptDir).apply(build).nodes
        ScriptHud.panel(id)?.nodes = nodes
    }

    /** Move it to [anchor], offset by [x], [y]. */
    fun move(anchor: Anchor, x: Int = 0, y: Int = 0) {
        ScriptHud.panel(id)?.let {
            it.anchor = anchor
            it.x = x
            it.y = y
        }
    }

    /** Hide it without removing it. */
    var visible: Boolean
        get() = ScriptHud.panel(id)?.visible == true
        set(value) {
            ScriptHud.panel(id)?.visible = value
        }

    val isRemoved: Boolean get() = ScriptHud.panel(id) == null

    fun remove() = ScriptHud.remove(id)
}

/** Parts of a panel. Live values are refreshed by [ScriptHud.tick] on the game thread. */
internal sealed interface HudNode {
    class Text(val live: (() -> String)?, val color: Int, val bold: Boolean, @Volatile var text: String) : HudNode {
        @Volatile var failed = false
    }

    class Bar(val label: String?, val live: (() -> Double)?, val max: Double, val color: Int, val width: Int) : HudNode {
        @Volatile var value = 0.0
        @Volatile var failed = false
    }

    class Item(val id: Identifier, val count: Int, val live: (() -> String)?, @Volatile var text: String) : HudNode {
        @Volatile var failed = false
        val stack: ItemStack by lazy { ItemStack(BuiltInRegistries.ITEM.getValue(id), count) }
    }

    class Image(val file: Path, val width: Int, val height: Int) : HudNode
    class Space(val height: Int) : HudNode
    class Separator(val color: Int) : HudNode
    class Row(val nodes: List<HudNode>) : HudNode
}

/** Every script's HUD panels, drawn as one Fabric HUD element. */
internal object ScriptHud {
    class Panel(
        val job: Job,
        @Volatile var nodes: List<HudNode>,
        @Volatile var anchor: Anchor,
        @Volatile var x: Int,
        @Volatile var y: Int,
        val background: Boolean,
        val scale: Float,
    ) {
        @Volatile var visible = true
    }

    private val nextId = AtomicLong()
    private val panels = ConcurrentSkipListMap<Long, Panel>()

    fun add(job: Job, nodes: List<HudNode>, anchor: Anchor, x: Int, y: Int, background: Boolean, scale: Float): Long {
        val id = nextId.incrementAndGet()
        panels[id] = Panel(job, nodes, anchor, x, y, background, scale)
        job.cleanups += { remove(id) }
        return id
    }

    fun panel(id: Long): Panel? = panels[id]

    fun remove(id: Long) {
        panels.remove(id)
    }

    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minekt", "script_hud")) { graphics, _ ->
            val mc = Minecraft.getInstance()
            if (!mc.gui.hud.isHidden && panels.isNotEmpty()) render(graphics, mc.font, graphics.guiWidth(), graphics.guiHeight())
        }
    }

    // ---- live values, once per tick on the game thread ----------------------------------------

    private var ticks = 0

    fun tick() {
        val reloadImages = ++ticks % 20 == 0
        for (panel in panels.values) {
            if (!panel.visible) continue
            refresh(panel.job, panel.nodes, reloadImages)
        }
    }

    private fun refresh(job: Job, nodes: List<HudNode>, reloadImages: Boolean) {
        for (node in nodes) when (node) {
            is HudNode.Text -> node.live?.let { live -> node.text = evaluate(job, node.failed, { node.failed = true }, live) { "⚠ $it" } }
            is HudNode.Item -> node.live?.let { live -> node.text = evaluate(job, node.failed, { node.failed = true }, live) { "⚠ $it" } }
            is HudNode.Bar -> node.live?.let { live -> node.value = evaluate(job, node.failed, { node.failed = true }, live) { Double.NaN } }
            is HudNode.Image -> HudImages.get(node.file, reload = reloadImages, job)
            is HudNode.Row -> refresh(job, node.nodes, reloadImages)
            else -> Unit
        }
    }

    /** Run a live part; the first time it throws, log it to the script's log. */
    private inline fun <T> evaluate(job: Job, failedBefore: Boolean, markFailed: () -> Unit, live: () -> T, onError: (String) -> T): T = try {
        live()
    } catch (t: Throwable) {
        if (!failedBefore) {
            markFailed()
            job.logs.add(job.name, ScriptLogs.Level.ERROR, "A live HUD value failed: ${t.message ?: t.javaClass.simpleName}", t.stackTraceToString(), job.id)
        }
        onError(t.message ?: t.javaClass.simpleName)
    }

    // ---- drawing -------------------------------------------------------------------------------

    private const val MARGIN = 4
    private const val PADDING = 3
    private const val LINE_GAP = 1
    private const val ROW_GAP = 6
    private const val BAR_HEIGHT = 5
    /** BOTTOM panels sit above the hotbar, hearts and hunger. */
    private const val HOTBAR_CLEARANCE = 62
    private const val BACKGROUND = 0x90000000.toInt()

    private class Size(val width: Int, val height: Int)

    private fun render(graphics: GuiGraphicsExtractor, font: Font, screenWidth: Int, screenHeight: Int) {
        val byAnchor = panels.values.filter { it.visible && it.nodes.isNotEmpty() }.groupBy { it.anchor }
        for ((anchor, group) in byAnchor) {
            val sizes = group.map { panel ->
                val content = measure(font, panel.nodes)
                val pad = if (panel.background) PADDING else 0
                Size(((content.width + 2 * pad) * panel.scale).roundToInt(), ((content.height + 2 * pad) * panel.scale).roundToInt())
            }
            val total = sizes.sumOf { it.height } + MARGIN * (sizes.size - 1)
            var cursor = when (anchor) {
                Anchor.TOP_LEFT, Anchor.TOP, Anchor.TOP_RIGHT -> MARGIN
                Anchor.LEFT, Anchor.CENTER, Anchor.RIGHT -> (screenHeight - total) / 2
                Anchor.BOTTOM -> screenHeight - HOTBAR_CLEARANCE - total
                Anchor.BOTTOM_LEFT, Anchor.BOTTOM_RIGHT -> screenHeight - MARGIN - total
            }
            group.forEachIndexed { i, panel ->
                val size = sizes[i]
                val left = when (anchor) {
                    Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT -> MARGIN
                    Anchor.TOP, Anchor.CENTER, Anchor.BOTTOM -> (screenWidth - size.width) / 2
                    Anchor.TOP_RIGHT, Anchor.RIGHT, Anchor.BOTTOM_RIGHT -> screenWidth - MARGIN - size.width
                }
                drawPanel(graphics, font, panel, left + panel.x, cursor + panel.y, size)
                cursor += size.height + MARGIN
            }
        }
    }

    private fun drawPanel(graphics: GuiGraphicsExtractor, font: Font, panel: Panel, x: Int, y: Int, size: Size) {
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate(x.toFloat(), y.toFloat())
        pose.scale(panel.scale, panel.scale)
        val width = (size.width / panel.scale).roundToInt()
        val height = (size.height / panel.scale).roundToInt()
        val pad = if (panel.background) PADDING else 0
        if (panel.background) graphics.fill(0, 0, width, height, BACKGROUND)
        drawColumn(graphics, font, panel.nodes, pad, pad, width - 2 * pad)
        pose.popMatrix()
    }

    private fun measure(font: Font, nodes: List<HudNode>): Size {
        var width = 0
        var height = 0
        nodes.forEachIndexed { i, node ->
            val size = measure(font, node)
            width = maxOf(width, size.width)
            height += size.height + if (i > 0) LINE_GAP else 0
        }
        return Size(width, height)
    }

    private fun measure(font: Font, node: HudNode): Size = when (node) {
        is HudNode.Text -> {
            val lines = node.text.split('\n')
            Size(lines.maxOf { font.width(component(it, node.bold)) }, lines.size * font.lineHeight)
        }
        is HudNode.Bar -> Size(node.width, BAR_HEIGHT + if (node.label != null) font.lineHeight + 1 else 0)
        is HudNode.Item -> Size(16 + if (node.text.isEmpty()) 0 else 3 + font.width(node.text), 16)
        is HudNode.Image -> Size(node.width, node.height)
        is HudNode.Space -> Size(0, node.height)
        is HudNode.Separator -> Size(0, 3)
        is HudNode.Row -> {
            val sizes = node.nodes.map { measure(font, it) }
            Size(sizes.sumOf { it.width } + ROW_GAP * (sizes.size - 1).coerceAtLeast(0), sizes.maxOfOrNull { it.height } ?: 0)
        }
    }

    private fun drawColumn(graphics: GuiGraphicsExtractor, font: Font, nodes: List<HudNode>, x: Int, y: Int, width: Int) {
        var top = y
        for (node in nodes) {
            val size = measure(font, node)
            draw(graphics, font, node, x, top, width, size)
            top += size.height + LINE_GAP
        }
    }

    private fun draw(graphics: GuiGraphicsExtractor, font: Font, node: HudNode, x: Int, y: Int, width: Int, size: Size) {
        when (node) {
            is HudNode.Text -> node.text.split('\n').forEachIndexed { i, line ->
                graphics.text(font, component(line, node.bold), x, y + i * font.lineHeight, node.color, true)
            }
            is HudNode.Bar -> {
                var barY = y
                if (node.label != null) {
                    graphics.text(font, node.label, x, y, Color.WHITE, true)
                    val percent = if (node.value.isNaN()) "⚠" else "${(node.value / node.max * 100).roundToInt()}%"
                    graphics.text(font, percent, x + node.width - font.width(percent), y, Color.WHITE, true)
                    barY += font.lineHeight + 1
                }
                val fraction = if (node.value.isNaN()) 0.0 else (node.value / node.max).coerceIn(0.0, 1.0)
                graphics.fill(x, barY, x + node.width, barY + BAR_HEIGHT, 0xFF202020.toInt())
                graphics.fill(x, barY, x + (node.width * fraction).roundToInt(), barY + BAR_HEIGHT, node.color or (0xFF shl 24))
                graphics.outline(x, barY, node.width, BAR_HEIGHT, 0x60FFFFFF)
            }
            is HudNode.Item -> {
                graphics.item(node.stack, x, y)
                graphics.itemDecorations(font, node.stack, x, y)
                if (node.text.isNotEmpty()) graphics.text(font, node.text, x + 19, y + (16 - font.lineHeight) / 2 + 1, Color.WHITE, true)
            }
            is HudNode.Image -> {
                val texture = HudImages.loaded(node.file)
                if (texture != null) graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, node.width, node.height, node.width, node.height)
                else graphics.fill(x, y, x + node.width, y + node.height, 0x80FF00FF.toInt()) // missing: magenta, like the game
            }
            is HudNode.Space -> Unit
            is HudNode.Separator -> graphics.fill(x, y + 1, x + width, y + 2, node.color)
            is HudNode.Row -> {
                var left = x
                for (child in node.nodes) {
                    val childSize = measure(font, child)
                    draw(graphics, font, child, left, y + (size.height - childSize.height) / 2, childSize.width, childSize)
                    left += childSize.width + ROW_GAP
                }
            }
        }
    }

    private fun component(text: String, bold: Boolean): Component =
        Component.literal(text).let { if (bold) it.withStyle { s -> s.withBold(true) } else it }
}

/** PNGs used by HUD panels, as textures. Loaded and reloaded (when the file changes) on the game thread. */
internal object HudImages {
    private class Loaded(val id: Identifier, val modified: Long, val texture: DynamicTexture)

    private val loaded = ConcurrentHashMap<Path, Loaded>()
    private val failed = ConcurrentHashMap.newKeySet<Path>()
    private val nextId = AtomicLong()

    fun loaded(file: Path): Identifier? = loaded[file]?.id

    /** Load [file] if it isn't yet, or [reload] it if it changed. Problems go to [job]'s log, once. */
    fun get(file: Path, reload: Boolean, job: Job) {
        val current = loaded[file]
        if (current != null && !reload) return
        if (current == null && file in failed && !reload) return
        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrNull()
        if (modified == null) {
            if (failed.add(file)) job.logs.add(job.name, ScriptLogs.Level.ERROR, "HUD image not found: $file", run = job.id)
            return
        }
        if (current != null && current.modified == modified) return
        try {
            val image = Files.newInputStream(file).use { NativeImage.read(it) }
            val id = Identifier.fromNamespaceAndPath("minekt", "hud/image_${nextId.incrementAndGet()}")
            val texture = DynamicTexture({ "Minekt HUD image $file" }, image)
            Minecraft.getInstance().textureManager.register(id, texture)
            loaded[file] = Loaded(id, modified, texture)
            failed.remove(file)
            current?.let { Minecraft.getInstance().textureManager.release(it.id) }
        } catch (e: Exception) {
            if (failed.add(file)) job.logs.add(job.name, ScriptLogs.Level.ERROR, "Couldn't load HUD image $file: ${e.message}", run = job.id)
        }
    }
}
