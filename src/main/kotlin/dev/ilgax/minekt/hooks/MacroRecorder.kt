package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.Key
import dev.ilgax.minekt.api.mapping
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale

/**
 * `\record <name>` … `\record stop`: samples the player's keys, camera and hotbar slot every tick
 * and saves them as `<name>.kts`, a readable script of `step(...)`s that replays them with `macro`.
 * Runs on the client thread.
 */
class MacroRecorder(private val scriptDir: Path) {
    internal class Frame(val keys: Set<Key>, val yaw: Float, val pitch: Float, val slot: Int)

    private class Recording(val name: String) {
        val frames = ArrayList<Frame>()
    }

    private var recording: Recording? = null

    val isRecording get() = recording != null

    fun start(name: String): String {
        recording?.let { return "Already recording '${it.name}': \\record stop saves it" }
        recording = Recording(name)
        return "Recording '$name'. Play, then \\record stop to save (or \\record cancel)"
    }

    fun cancel(): String {
        val r = recording ?: return "Not recording"
        recording = null
        return "Dropped the recording of '${r.name}'"
    }

    /** Save the recording as a script; returns what to tell the player. */
    fun stop(): String {
        val r = recording ?: return "Not recording"
        recording = null
        if (r.frames.isEmpty()) return "Nothing was recorded"
        val file = scriptDir.resolve("${r.name}.kts")
        val replaced = Files.exists(file)
        Files.createDirectories(scriptDir)
        Files.writeString(file, script(r.name, r.frames))
        val seconds = r.frames.size / 20.0
        return "Saved ${r.name}.kts (%.1f s)%s. Run it with \\${r.name}".format(Locale.ROOT, seconds, if (replaced) ", replacing the old one" else "")
    }

    /** Client thread, every tick. */
    fun tick(mc: Minecraft) {
        val r = recording ?: return
        val player = mc.player ?: return
        val keys = if (mc.gui.screen() == null) Key.entries.filter { mc.options.mapping(it).isDown }.toSet() else emptySet()
        r.frames += Frame(keys, player.yRot, player.xRot, player.inventory.selectedSlot)
        if (r.frames.size % 10 == 1) {
            mc.gui.hud.setOverlayMessage(Component.literal("● REC %s  %.1f s".format(Locale.ROOT, r.name, r.frames.size / 20.0)), false)
        }
    }

    companion object {
        private const val STEPS_PER_PART = 250

        /** Merge equal consecutive frames into steps and write them as a script. */
        internal fun script(name: String, frames: List<Frame>): String {
            data class Step(val ticks: Int, val frame: Frame)
            val steps = ArrayList<Step>()
            for (frame in frames) {
                val last = steps.lastOrNull()
                if (last != null && same(last.frame, frame)) steps[steps.size - 1] = Step(last.ticks + 1, last.frame)
                else steps += Step(1, frame)
            }
            var slot: Int? = null
            val lines = steps.map { (ticks, f) ->
                val args = mutableListOf(ticks.toString())
                if (f.keys.isNotEmpty()) args += "\"" + f.keys.joinToString(" ") { it.name.lowercase() } + "\""
                args += "yaw = ${angle(f.yaw)}f"
                args += "pitch = ${angle(f.pitch)}f"
                if (f.slot != slot) args += "slot = ${f.slot}"
                slot = f.slot
                "    step(${args.joinToString(", ")}),"
            }
            val parts = lines.chunked(STEPS_PER_PART)
            return buildString {
                appendLine("// \\$name — macro recorded with \\record on ${LocalDate.now()}: ${frames.size} ticks (%.1f s), ${steps.size} steps.".format(Locale.ROOT, frames.size / 20.0))
                appendLine("// Each step holds the listed keys for some ticks while looking at yaw/pitch; see `macro` in the")
                appendLine("// README. It replays your inputs, not positions: start where you started recording.")
                appendLine("// Edit freely, e.g. macro(…, speed = 2.0) plays it twice as fast.")
                appendLine()
                if (parts.size == 1) {
                    appendLine("macro(")
                    parts.single().forEach(::appendLine)
                    appendLine(")")
                } else {
                    // Long recordings are split into functions: a single one would be too big for the JVM.
                    parts.forEachIndexed { i, part ->
                        appendLine("fun part${i + 1}() = listOf(")
                        part.forEach(::appendLine)
                        appendLine(")")
                        appendLine()
                    }
                    appendLine("macro(" + parts.indices.joinToString(" + ") { "part${it + 1}()" } + ")")
                }
            }
        }

        private fun same(a: Frame, b: Frame) = a.keys == b.keys && a.slot == b.slot && angle(a.yaw) == angle(b.yaw) && angle(a.pitch) == angle(b.pitch)

        /** Angles to a tenth of a degree: enough for aiming, and it lets still camera frames merge. */
        private fun angle(value: Float) = "%.1f".format(Locale.ROOT, value)
    }
}
