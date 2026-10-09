package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.Blaze3D
import dev.ilgax.minekt.api.ScreenBuilder
import dev.ilgax.minekt.api.ScreenItem
import dev.ilgax.minekt.job.ScriptLogs
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.time.format.DateTimeFormatter

/** `\log <script>`: the script's log in a scrolling screen, newest first, errors with their stack traces. */
internal class LogViewer(private val logs: ScriptLogs) {
    fun open(mc: Minecraft, script: String, back: (() -> Unit)? = null) {
        mc.gui.setScreen(
            ScriptScreenView(
                "Log of $script", items(script, back), doneButton = true, runCallback = { it() },
                rebuild = { items(script, back) }, signature = { logs.recent(script).let { it.size to it.lastOrNull() } }, wide = true,
            ),
        )
    }

    private fun items(script: String, back: (() -> Unit)?): List<ScreenItem> = ScreenBuilder().apply {
        val entries = logs.recent(script)
        row {
            button("Open log file", tooltip = logs.file(script).toString()) {
                logs.flush()
                Blaze3D.openPath(logs.file(script).takeIf { Files.isRegularFile(it) } ?: logs.dir.also { Files.createDirectories(it) })
            }
            button("Copy", tooltip = "Copy this session's log to the clipboard") {
                Minecraft.getInstance().keyboardHandler.clipboard = entries.joinToString("") { ScriptLogs.format(it) }
            }
            button("Clear", tooltip = "Delete $script's log") { logs.clear(script) }
            if (back != null) button("Back") { back() }
        }
        if (entries.isEmpty()) {
            val earlier = logs.tail(script)
            if (earlier.isEmpty()) text("Nothing logged yet. Scripts log when they start and stop, what they echo, writeLog(…) and errors.")
            else {
                leftText("§7Nothing this session. The end of the log file:")
                leftText(earlier.joinToString("\n") { it.replace("\t", "    ") })
            }
        } else {
            leftText("§7Newest first. ${entries.size} entries this session" + if (entries.size >= ScriptLogs.MAX_RECENT) " (older ones are in the file)" else "")
            for (entry in entries.asReversed()) leftText(format(entry))
        }
    }.items

    private fun format(entry: ScriptLogs.Entry): String = buildString {
        append("§8").append(TIME.format(entry.time)).append(' ')
        append(COLORS.getValue(entry.level)).append(entry.level.tag)
        entry.run?.let { append(" §8#").append(it) }
        append(if (entry.level == ScriptLogs.Level.ERROR) " §c" else " §f").append(entry.text)
        entry.detail?.lineSequence()?.forEach { append("\n§7    ").append(it.replace("\t", "    ")) }
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val COLORS = mapOf(
            ScriptLogs.Level.INFO to "§7", ScriptLogs.Level.ECHO to "§f", ScriptLogs.Level.LOG to "§b",
            ScriptLogs.Level.WARN to "§6", ScriptLogs.Level.ERROR to "§c",
        )
    }
}
