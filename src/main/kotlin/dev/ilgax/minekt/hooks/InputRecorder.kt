package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.InputAction
import dev.ilgax.minekt.api.InputEvent
import dev.ilgax.minekt.api.RawKey
import dev.ilgax.minekt.api.RawMouseButton
import dev.ilgax.minekt.api.RawScroll
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale

/**
 * `\record <name>` … `\record stop`: records the player's keys, mouse buttons, scroll wheel and camera
 * and saves them as `<name>.kts`, an ordinary script of `keyDown`, `mouseDown`, `setOrientation`,
 * `waitTicks`, … that sends the same input again. Runs on the client thread.
 */
class InputRecorder(private val scriptDir: Path, private val notify: (String) -> Unit) {
    /** One line of the script, [tick] ticks after the recording started. */
    internal class Action(val tick: Int, val code: String, val chatKey: Boolean = false, val initial: Boolean = false)

    /**
     * [limit]: ticks to record before saving on its own, or null to record until `\record stop`.
     * [countdown]: ticks left before recording starts; input isn't recorded until then.
     */
    private class Recording(val name: String, val limit: Int?, var countdown: Int) {
        val actions = ArrayList<Action>()
        var ticks = 0
        val keys = HashSet<String>()
        val buttons = HashSet<Int>()
        var yaw: String? = null
        var pitch: String? = null
    }

    private var recording: Recording? = null

    val isRecording get() = recording != null

    /**
     * Start recording into `<name>.kts` after a [countdown] (ticks, shown as a big 3, 2, 1); with
     * [ticks], it stops and saves by itself after that long.
     */
    fun start(name: String, ticks: Int? = null, countdown: Int = COUNTDOWN): String {
        recording?.let { return "Already recording '${it.name}': \\record stop saves it" }
        require(ticks == null || ticks > 0) { "The time must be above 0" }
        recording = Recording(name, ticks, countdown)
        InputHooks.listening = true
        val starts = if (countdown > 0) "Recording '$name' in ${countdown / 20} s" else "Recording '$name'"
        return if (ticks == null) "$starts. Play, then \\record stop to save (or \\record cancel)"
        else "$starts, for %s. It saves by itself (\\record stop saves early, \\record cancel drops it)".format(seconds(ticks))
    }

    fun cancel(): String {
        val r = recording ?: return "Not recording"
        recording = null
        return "Dropped the recording of '${r.name}'"
    }

    /** Save the recording as a script; returns what to tell the player. */
    fun stop(): String = save(typed = true)

    /** [typed]: stopped by typing `\record stop` in chat, so the key that opened chat isn't part of the recording. */
    private fun save(typed: Boolean): String {
        val r = recording ?: return "Not recording"
        recording = null
        val chat = r.actions.indexOfLast { it.chatKey }.takeIf { typed && it >= 0 }
        val actions = if (chat == null) r.actions else r.actions.subList(0, chat)
        val length = if (chat == null) r.ticks else r.actions[chat].tick
        if (actions.all { it.initial }) return "Nothing was recorded"
        val file = scriptDir.resolve("${r.name}.kts")
        val replaced = Files.exists(file)
        Files.createDirectories(scriptDir)
        Files.writeString(file, script(r.name, actions, length))
        return "Saved ${r.name}.kts (%s)%s. Run it with \\${r.name}".format(seconds(length), if (replaced) ", replacing the old one" else "")
    }

    /** Client thread, every tick: the camera and the hotbar slot are sampled once per tick. */
    fun tick(mc: Minecraft) {
        val r = recording ?: return
        val player = mc.player ?: return
        if (r.countdown > 0) {
            countdown(mc, r)
            return
        }
        val yaw = angle(player.yRot)
        val pitch = angle(player.xRot)
        if (r.ticks == 0) r.actions += Action(0, "selectSlot(${player.inventory.selectedSlot})", initial = true)
        if (yaw != r.yaw || pitch != r.pitch) {
            r.actions += Action(r.ticks, "setOrientation(${yaw}f, ${pitch}f)", initial = r.ticks == 0)
            r.yaw = yaw
            r.pitch = pitch
        }
        r.ticks++
        if (r.limit != null && r.ticks >= r.limit) {
            notify(save(typed = false))
            mc.gui.hud.setOverlayMessage(Component.literal("■ Saved ${r.name}"), false)
            return
        }
        if (r.ticks % 10 == 1) {
            val left = r.limit?.let { "  (%s left)".format(seconds(it - r.ticks)) }.orEmpty()
            mc.gui.hud.setOverlayMessage(Component.literal("● REC %s  %s%s".format(r.name, seconds(r.ticks), left)), false)
        }
    }

    /** A big 3, 2, 1 with a click on each, then "REC" as recording starts on the next tick. */
    private fun countdown(mc: Minecraft, r: Recording) {
        val hud = mc.gui.hud
        if (r.countdown % 20 == 0) {
            hud.setTimes(0, 25, 5)
            hud.setSubtitle(Component.literal("Recording ${r.name}"))
            hud.setTitle(Component.literal("${r.countdown / 20}"))
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), 1f))
        }
        r.countdown--
        if (r.countdown == 0) {
            hud.setTimes(0, 20, 10)
            hud.setTitle(Component.literal("● REC"))
            mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 2f))
        }
    }

    /**
     * Client thread, for input the game is about to handle. Keys count unless chat is open (typing
     * isn't recorded); mouse buttons and the wheel only outside screens, where the cursor doesn't matter.
     * A release only counts if its press did.
     */
    internal fun input(mc: Minecraft, event: InputEvent) {
        val r = recording ?: return
        if (event.synthetic || r.countdown > 0) return
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
        /** Ticks before a recording starts, so the player can get ready after typing the command. */
        const val COUNTDOWN = 3 * 20

        /**
         * A recording length in ticks: `30` or `30s` seconds, `2m` minutes, `1.5s`, `100t` ticks.
         * Null if it isn't one.
         */
        internal fun parseDuration(text: String): Int? {
            val match = Regex("""(\d+(?:\.\d+)?)([smt]?)""").matchEntire(text.trim().lowercase()) ?: return null
            val amount = match.groupValues[1].toDouble()
            val ticks = when (match.groupValues[2]) {
                "t" -> amount
                "m" -> amount * 60 * 20
                else -> amount * 20
            }
            return if (ticks < 1 || ticks > Int.MAX_VALUE) null else Math.round(ticks).toInt()
        }

        private fun seconds(ticks: Int) = "%.1f s".format(Locale.ROOT, ticks / 20.0)

        /** Script lines per function: one function can't hold a long recording (the JVM limits method size). */
        internal const val LINES_PER_PART = 500

        /** Write [actions] as a script, waiting between them; keys still down at [length] ticks are released then. */
        internal fun script(name: String, actions: List<Action>, length: Int = actions.lastOrNull()?.tick ?: 0): String {
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
            if (held.isNotEmpty() && length > tick) lines += "waitTicks(${length - tick})"
            lines += held
            val parts = lines.chunked(LINES_PER_PART)
            return buildString {
                appendLine("// \\$name, recorded with \\record on ${LocalDate.now()}: ${seconds(maxOf(tick, length))}.")
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
