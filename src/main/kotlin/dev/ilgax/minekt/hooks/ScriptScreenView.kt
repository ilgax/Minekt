package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.ScreenItem
import dev.ilgax.minekt.job.Job
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractSliderButton
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * The Minecraft screen behind `showScreen`. Lives on the game thread; every change the player
 * makes is stored in its [ScreenItem] and handed to the script with [Job.runCallback].
 */
internal class ScriptScreenView(
    title: String,
    private var items: List<ScreenItem>,
    private val doneButton: Boolean,
    private val job: Job,
    private val onRemoved: () -> Unit,
) : Screen(Component.literal(title)) {

    /** Text drawn by [extractRenderState]: static lines, live text and text-field labels. */
    private class TextSlot(val item: ScreenItem.Text?, val x: Int, val y: Int, val centered: Boolean, var lines: List<String>)

    private val texts = mutableListOf<TextSlot>()

    override fun init() {
        texts.clear()
        val columnWidth = minOf(COLUMN_WIDTH, width - 2 * MARGIN)
        val left = (width - columnWidth) / 2
        var y = TOP
        for (item in items) y = place(item, left, y, columnWidth) + GAP
        if (doneButton) {
            val buttonY = (y + GAP).coerceAtMost(height - ROW - GAP).coerceAtLeast(y)
            addRenderableWidget(Button.builder(CommonComponents.GUI_DONE) { onClose() }.bounds(width / 2 - 100, buttonY, 200, ROW).build())
        }
    }

    /** Lay out [item] in the box starting at ([x], [y]) with width [w]; returns its bottom edge. */
    private fun place(item: ScreenItem, x: Int, y: Int, w: Int): Int = when (item) {
        is ScreenItem.Text -> {
            val lines = textOf(item)
            texts += TextSlot(item, x + w / 2, y, centered = true, lines)
            y + lines.size * font.lineHeight
        }
        is ScreenItem.Button -> {
            val button = Button.builder(Component.literal(item.label)) { job.runCallback(item.onClick) }.bounds(x, y, w, ROW)
            item.tooltip?.let { button.tooltip(Tooltip.create(Component.literal(it))) }
            addRenderableWidget(button.build())
            y + ROW
        }
        is ScreenItem.Toggle -> {
            addRenderableWidget(
                CycleButton.onOffBuilder(item.value).create(x, y, w, ROW, Component.literal(item.label)) { _, value ->
                    item.value = value
                    job.runCallback { item.onChange(value) }
                },
            )
            y + ROW
        }
        is ScreenItem.IntSlider -> {
            val span = (item.range.last - item.range.first).coerceAtLeast(1)
            addRenderableWidget(
                Slider(x, y, w, item.label, (item.value - item.range.first).toDouble() / span,
                    toValue = { item.range.first + (it * span).roundToInt() },
                    format = { it.toString() },
                    current = { item.value },
                    set = { value: Int -> item.value = value; job.runCallback { item.onChange(value) } }),
            )
            y + ROW
        }
        is ScreenItem.DoubleSlider -> {
            val (start, end) = item.range.start to item.range.endInclusive
            val decimals = BigDecimal.valueOf(item.step).stripTrailingZeros().scale().coerceAtLeast(0)
            addRenderableWidget(
                Slider(x, y, w, item.label, (item.value - start) / (end - start),
                    toValue = { (start + ((it * (end - start)) / item.step).roundToInt() * item.step).coerceIn(start, end) },
                    format = { "%.${decimals}f".format(it) },
                    current = { item.value },
                    set = { value: Double -> item.value = value; job.runCallback { item.onChange(value) } }),
            )
            y + ROW
        }
        is ScreenItem.TextField -> {
            val labelWidth = (font.width(item.label) + 6).coerceAtMost(w / 2)
            texts += TextSlot(null, x, y + (ROW - font.lineHeight) / 2 + 1, centered = false, listOf(item.label))
            val box = EditBox(font, x + labelWidth, y, w - labelWidth, ROW, Component.literal(item.label))
            box.setMaxLength(item.maxLength)
            box.setValue(item.value)
            box.setResponder { value ->
                if (value != item.value) {
                    item.value = value
                    job.runCallback { item.onChange(value) }
                }
            }
            addRenderableWidget(box)
            y + ROW
        }
        is ScreenItem.Choice -> {
            // Cycles over option indices: the options themselves may be null, which CycleButton can't hold.
            addRenderableWidget(
                CycleButton.builder<Int>({ Component.literal(item.display(item.options[it])) }, item.options.indexOf(item.value).coerceAtLeast(0))
                    .withValues(item.options.indices.toList())
                    .create(x, y, w, ROW, Component.literal(item.label)) { _, index ->
                        val value = item.options[index]
                        item.value = value
                        job.runCallback { item.onChange(value) }
                    },
            )
            y + ROW
        }
        is ScreenItem.Row -> {
            val n = item.items.size.coerceAtLeast(1)
            val cellWidth = (w - GAP * (n - 1)) / n
            item.items.withIndex().maxOfOrNull { (i, child) -> place(child, x + i * (cellWidth + GAP), y, cellWidth) } ?: y
        }
    }

    /** Text lines of [item]; live text runs script code here, on the game thread. */
    private fun textOf(item: ScreenItem.Text): List<String> = try {
        item.text().split('\n')
    } catch (t: Throwable) {
        listOf("⚠ ${t.message ?: t.javaClass.simpleName}")
    }

    /** Swap in a rebuilt layout (from `refresh()`). */
    fun replaceItems(newItems: List<ScreenItem>) {
        items = newItems
        rebuildWidgets()
    }

    override fun tick() {
        super.tick()
        var relayout = false
        for (slot in texts) {
            val item = slot.item ?: continue
            if (!item.live) continue
            val lines = textOf(item)
            if (lines.size != slot.lines.size) relayout = true
            slot.lines = lines
        }
        if (relayout) rebuildWidgets()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        graphics.centeredText(font, title, width / 2, TITLE_Y, WHITE)
        for (slot in texts) {
            slot.lines.forEachIndexed { i, line ->
                val y = slot.y + i * font.lineHeight
                if (slot.centered) graphics.centeredText(font, line, slot.x, y, WHITE) else graphics.text(font, line, slot.x, y, LABEL)
            }
        }
    }

    override fun isPauseScreen() = false

    override fun removed() {
        super.removed()
        onRemoved()
    }

    /** A slider over 0..1 that reports its value as [T], firing only when that value changes. */
    private inner class Slider<T>(
        x: Int, y: Int, w: Int,
        private val label: String,
        initial: Double,
        private val toValue: (Double) -> T,
        private val format: (T) -> String,
        private val current: () -> T,
        private val set: (T) -> Unit,
    ) : AbstractSliderButton(x, y, w, ROW, Component.empty(), initial.coerceIn(0.0, 1.0)) {
        init {
            updateMessage()
        }

        override fun updateMessage() {
            message = Component.literal("$label: ${format(current())}")
        }

        override fun applyValue() {
            val value = toValue(this.value)
            if (value != current()) set(value)
            updateMessage()
        }
    }

    private companion object {
        const val COLUMN_WIDTH = 310
        const val MARGIN = 10
        const val TOP = 32
        const val TITLE_Y = 15
        const val ROW = 20
        const val GAP = 4
        const val WHITE = -0x1 // 0xFFFFFFFF
        const val LABEL = -0x5f5f60 // 0xFFA0A0A0
    }
}
