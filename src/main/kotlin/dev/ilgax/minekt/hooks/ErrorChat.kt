package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.job.ErrorReport
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name

/** Underlined chat text that does [click] and explains itself on hover. */
internal fun chatButton(label: String, color: ChatFormatting, hover: String, click: ClickEvent): MutableComponent =
    Component.literal(label).withStyle { style ->
        style.withColor(color).withUnderlined(true).withClickEvent(click).withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
    }

/**
 * Script errors in chat: the summary (hover for where in the script, click for the log), `[log]`
 * and `[edit]` buttons, and the offending source line with the error marked.
 */
internal object ErrorChat {
    private const val MAX_SOURCE = 90

    fun lines(report: ErrorReport, scriptDir: Path): List<Component> {
        val hover = buildList {
            add(report.summary)
            report.scriptStack.forEach { add("  at $it") }
            add("")
            add("Click for the full log")
        }.joinToString("\n")
        val head = Component.literal(report.headline).withStyle { style ->
            style.withColor(ChatFormatting.RED)
                .withHoverEvent(HoverEvent.ShowText(Component.literal(hover)))
                .withClickEvent(ClickEvent.RunCommand("minekt log ${report.script}"))
        }
        head.append(" ").append(chatButton("[log]", ChatFormatting.GRAY, "Show ${report.script}'s log", ClickEvent.RunCommand("minekt log ${report.script}")))
        findFile(scriptDir, report)?.let { file ->
            head.append(" ").append(chatButton("[edit]", ChatFormatting.GRAY, "Open ${file.name} in your editor", ClickEvent.OpenFile(file)))
        }
        val source = report.sourceLine?.takeIf { it.isNotBlank() } ?: return listOf(head)
        return listOf(head, sourceLine(report.line, source, report.highlight))
    }

    /** `  12 │ val n = args[0].toInt()`, with [highlight] in red, cut to fit around it. */
    fun sourceLine(line: Int?, source: String, highlight: IntRange?): Component {
        val indent = source.length - source.trimStart().length
        var text = source.trimStart()
        var mark = highlight?.let { (it.first - indent).coerceAtLeast(0) until (it.last + 1 - indent).coerceIn(0, text.length) }
        if (text.length > MAX_SOURCE) {
            val from = mark?.let { (it.first - MAX_SOURCE / 3).coerceIn(0, text.length - MAX_SOURCE) } ?: 0
            val cut = text.substring(from, from + MAX_SOURCE)
            mark = mark?.let { (it.first - from).coerceAtLeast(0) until (it.last + 1 - from).coerceIn(0, cut.length) }
            text = (if (from > 0) "…" else "") + cut + (if (from + MAX_SOURCE < source.trimStart().length) "…" else "")
            if (from > 0) mark = mark?.let { it.first + 1 until it.last + 2 }
        }
        val out = Component.literal("  ${line ?: "?"} │ ").withStyle(ChatFormatting.DARK_GRAY)
        if (mark == null || mark.isEmpty()) return out.append(Component.literal(text).withStyle(ChatFormatting.GRAY))
        return out.append(Component.literal(text.substring(0, mark.first)).withStyle(ChatFormatting.GRAY))
            .append(Component.literal(text.substring(mark.first, mark.last + 1)).withStyle(ChatFormatting.RED, ChatFormatting.UNDERLINE))
            .append(Component.literal(text.substring(mark.last + 1)).withStyle(ChatFormatting.GRAY))
    }

    /** The file the error is in: the script itself, or an imported one somewhere under [scriptDir]. */
    private fun findFile(scriptDir: Path, report: ErrorReport): Path? {
        val name = report.file ?: "${report.script}.kts"
        val direct = scriptDir.resolve(name)
        if (Files.isRegularFile(direct)) return direct
        return runCatching { Files.walk(scriptDir, 4).use { paths -> paths.filter { it.name == name && Files.isRegularFile(it) }.findFirst().orElse(null) } }.getOrNull()
    }
}
