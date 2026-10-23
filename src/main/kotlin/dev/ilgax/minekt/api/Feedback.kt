package dev.ilgax.minekt.api

import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.components.toasts.SystemToast
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

// Getting the player's attention: sounds, toasts, action-bar text, titles, screenshots.
// All of these are local: nobody else hears or sees them.

/**
 * Play a sound only you hear, e.g. `playSound("minecraft:entity.experience_orb.pickup")`. Any
 * sound id works (`/playsound` lists them); [pitch] 0.5–2.
 */
fun Minekt.playSound(id: String = "minecraft:block.note_block.pling", volume: Float = 1f, pitch: Float = 1f) = game { mc ->
    val sound = BuiltInRegistries.SOUND_EVENT.getOptional(Identifier.parse(id)).orElseThrow { IllegalArgumentException("Unknown sound '$id'") }
    mc.soundManager.play(SimpleSoundInstance.forUI(sound, pitch, volume))
    Unit
}

/** Show a pop-up in the top-right corner for [duration]. */
fun Minekt.toast(title: String, message: String = "", duration: Duration = 5.seconds) = game { mc ->
    SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId(duration.inWholeMilliseconds), Component.literal(title), Component.literal(message))
}

/** Show text above the hotbar (where "Now playing" and bed messages appear). */
fun Minekt.actionBar(text: String) = game { mc ->
    mc.gui.hud.setOverlayMessage(Component.literal(text), false)
}

/** Show a big title (and optional subtitle) in the middle of the screen, timed in game ticks (20 a second). */
fun Minekt.title(title: String, subtitle: String = "", fadeInTicks: Int = 10, stayTicks: Int = 60, fadeOutTicks: Int = 10) = game { mc ->
    val hud = mc.gui.hud
    hud.setTimes(fadeInTicks, stayTicks, fadeOutTicks)
    hud.setSubtitle(Component.literal(subtitle))
    hud.setTitle(Component.literal(title))
}

/**
 * Save a screenshot to `.minecraft/screenshots/` and return its path. [name] defaults to the
 * game's usual date-stamped name. Waits until the file is written.
 */
fun Minekt.screenshot(name: String? = null): Path {
    checkCanWait("screenshot") // it waits for the game to save the picture
    val fileName = name?.let { (if (it.endsWith(".png")) it else "$it.png").replace(Regex("[^A-Za-z0-9_.-]"), "_") }
    val done = CompletableFuture<Component>()
    val dir = game { mc ->
        Screenshot.grab(mc.gameDirectory, fileName, mc.gameRenderer.mainRenderTarget(), 1) { message -> done.complete(message) }
        mc.gameDirectory.toPath().resolve(Screenshot.SCREENSHOT_DIR)
    }
    val message = try {
        done.get(10, TimeUnit.SECONDS)
    } catch (e: Exception) {
        error("Screenshot wasn't saved in time")
    }
    // The game reports the saved file's name in its message; fall back to the newest file.
    return fileName?.let { dir.resolve(it) }
        ?: dir.toFile().listFiles()?.maxByOrNull { it.lastModified() }?.toPath()
        ?: error("Screenshot failed: ${message.string}")
}
