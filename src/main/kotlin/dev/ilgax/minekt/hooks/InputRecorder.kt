package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.InputAction
import dev.ilgax.minekt.api.InputEvent
import dev.ilgax.minekt.api.RawKey
import dev.ilgax.minekt.api.RawMouseButton
import dev.ilgax.minekt.api.RawScroll
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.network.chat.Component
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale

/**
 * `\record <name>` … `\record stop`: records the player's keys, mouse buttons, scroll wheel and camera
 * and saves them as `<name>.kts`, an ordinary script of `keyDown`, `mouseDown`, `setOrientation`,
 * `waitTicks`, … that sends the same input again. Runs on the client thread.
 */
class InputRecorder(private val scriptDir: Path) {
    /** One line of the script, [tick] ticks after the recording started. */
    internal class Action(val tick: Int, val code: String, val chatKey: Boolean = false, val initial: Boolean = false)

    private class Recording(val name: String) {
        val actions = ArrayList<Action>()
        var ticks = 0
        val keys = HashSet<String>()
        val buttons = HashSet<Int>()
        var yaw: String? = null
        var pitch: String? = null
    }

    private var recording: Recording? = null

    val isRecording get() = recording != null

    fun start(name: String): String {
        recording?.let { return "Already recording '${it.name}': \\record stop saves it" }
        recording = Recording(name)
        InputHooks.listening = true
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
        // `\record stop` was typed in chat: the key that opened chat isn't part of the recording.
        val actions = r.actions.indexOfLast { it.chatKey }.let { if (it < 0) r.actions else r.actions.subList(0, it) }
        if (actions.all { it.initial }) return "Nothing was recorded"
        val file = scriptDir.resolve("${r.name}.kts")
        val replaced = Files.exists(file)
        Files.createDirectories(scriptDir)
        Files.writeString(file, script(r.name, actions))
        val seconds = (actions.lastOrNull()?.tick ?: 0) / 20.0
        return "Saved ${r.name}.kts (%.1f s)%s. Run it with \\${r.name}".format(Locale.ROOT, seconds, if (replaced) ", replacing the old one" else "")
    }

    /** Client thread, every tick: the camera and the hotbar slot are sampled once per tick. */
    fun tick(mc: Minecraft) {
        val r = recording ?: return
        val player = mc.player ?: return
        val yaw = angle(player.yRot)
        val pitch = angle(player.xRot)
        if (r.ticks == 0) r.actions += Action(0, "selectSlot(${player.inventory.selectedSlot})", initial = true)
        if (yaw != r.yaw || pitch != r.pitch) {
            r.actions += Action(r.ticks, "setOrientation(${yaw}f, ${pitch}f)", initial = r.ticks == 0)
            r.yaw = yaw
            r.pitch = pitch
        }
        r.ticks++
        if (r.ticks % 10 == 1) {
            mc.gui.hud.setOverlayMessage(Component.literal("● REC %s  %.1f s".format(Locale.ROOT, r.name, r.ticks / 20.0)), false)
        }
    }

    /**
     * Client thread, for input the game is about to handle. Keys count unless chat is open (typing
     * isn't recorded); mouse buttons and the wheel only outside screens, where the cursor doesn't matter.
     * A release only counts if its press did.
     */
    internal fun input(mc: Minecraft, event: InputEvent) {
        val r = recording ?: return
        if (event.synthetic) return
        val screen = mc.gui.screen()
        when (event) {
            is RawKey -> {
                val name = event.key.removePrefix("key.keyboard.")
                if (name.startsWith("key.") || name == "unknown") return
                when (event.action) {
                    InputAction.PRESS -> if (screen !is ChatScreen && r.keys.add(name)) {
                        val chatKey = screen == null && (mc.options.keyChat.saveString() == event.key || mc.options.keyCommand.saveString() == event.key)
                        r.actions += Action(r.ticks, "keyDown(\"$name\")", chatKey)
                    }
                    InputAction.RELEASE -> if (r.keys.remove(name)) r.actions += Action(r.ticks, "keyUp(\"$name\")")
                    InputAction.REPEAT -> Unit
                }
            }
            is RawMouseButton -> when (event.action) {
                InputAction.PRESS -> if (screen == null && r.buttons.add(event.button)) r.actions += Action(r.ticks, "mouseDown(${event.button})")
                InputAction.RELEASE -> if (r.buttons.remove(event.button)) r.actions += Action(r.ticks, "mouseUp(${event.button})")
                InputAction.REPEAT -> Unit
            }
            is RawScroll -> if (screen == null) {
                r.actions += Action(r.ticks, if (event.x == 0.0) "scrollMouse(${event.y})" else "scrollMouse(${event.y}, ${event.x})")
            }
            else -> Unit
        }
    }

    companion object {
        /** Script lines per function: one function can't hold a long recording (the JVM limits method size). */
        internal const val LINES_PER_PART = 500

        /** Write [actions] as a script, waiting between them; keys still down at the end are released. */
        internal fun script(name: String, actions: List<Action>): String {
            val held = LinkedHashSet<String>()
            val lines = ArrayList<String>()
            var tick = 0
            for (action in actions) {
                if (action.tick > tick) lines += "waitTicks(${action.tick - tick})"
                tick = maxOf(tick, action.tick)
                lines += action.code
                when {
                    action.code.startsWith("keyDown(") -> held += action.code.replace("keyDown(", "keyUp(")
                    action.code.startsWith("keyUp(") -> held -= action.code
                    action.code.startsWith("mouseDown(") -> held += action.code.replace("mouseDown(", "mouseUp(")
                    action.code.startsWith("mouseUp(") -> held -= action.code
                }
            }
            lines += held
            val parts = lines.chunked(LINES_PER_PART)
            return buildString {
                appendLine("// \\$name, recorded with \\record on ${LocalDate.now()}: %.1f s.".format(Locale.ROOT, tick / 20.0))
                appendLine("// It sends the same keys, clicks and camera turns again (20 ticks a second), through your key")
                appendLine("// bindings. It replays input, not positions: start where you started recording. Edit freely.")
                appendLine()
                if (parts.size == 1) {
                    parts.single().forEach(::appendLine)
                } else {
                    parts.forEachIndexed { i, part ->
                        appendLine("fun part${i + 1}() {")
                        part.forEach { appendLine("    $it") }
                        appendLine("}")
                        appendLine()
                    }
                    parts.indices.forEach { appendLine("part${it + 1}()") }
                }
            }
        }

        /** Angles to a tenth of a degree: enough for aiming, and a still camera writes nothing. */
        private fun angle(value: Float) = "%.1f".format(Locale.ROOT, value)
    }
}
