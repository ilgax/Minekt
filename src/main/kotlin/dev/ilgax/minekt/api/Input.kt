package dev.ilgax.minekt.api

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.hooks.InputHooks
import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonInfo
import org.lwjgl.sdl.SDLKeyboard

// Raw keyboard and mouse input, both ways:
//
//   onRawKey { if (it.key == "key.keyboard.q" && it.action == InputAction.PRESS) it.cancel() }   // the game never sees it
//   onRawMouse { if (it is RawScroll) echo("scrolled ${it.y}") }
//   onMouseMove { if (frozen) it.cancel() }
//
//   tapKey("e")                    // press and release: opens the inventory, like the real key
//   keyDown("w"); sleep(1000); keyUp("w")
//   typeText("hello")              // into the open screen (chat, a sign, a text box)
//   clickMouse(); mouseDown(1); mouseUp(1); scrollMouse(-1.0); moveMouse(40.0, 0.0)
//
// Listeners run on the game thread, before the game handles the input: keep them quick. Sent input
// goes through the same game code as real input (so it also reaches listeners, marked `synthetic`).
// Keys and buttons a script leaves down are released when it ends.

enum class InputAction {
    RELEASE, PRESS, REPEAT;

    internal companion object {
        /** From the game's (SDL) action: 1 press, 0 release, -1 repeat. */
        fun of(action: Int) = when (action) {
            InputConstants.RELEASE -> RELEASE
            InputConstants.REPEAT -> REPEAT
            else -> PRESS
        }
    }
}

/** Something from the keyboard or mouse. [cancel] stops the game (and later listeners) from seeing it. */
sealed class InputEvent(
    /** Sent by a script rather than the player. */
    val synthetic: Boolean,
) {
    var isCancelled: Boolean = false
        private set

    fun cancel() {
        isCancelled = true
    }
}

/** A key, by name: `key.keyboard.q`, `key.keyboard.left.shift`, … [modifiers] are the game's bits (`InputConstants.MOD_SHIFT`, `MOD_CONTROL`, …). */
class RawKey internal constructor(val key: String, val action: InputAction, val modifiers: Int, synthetic: Boolean) : InputEvent(synthetic) {
    override fun toString() = "RawKey($key $action)"
}

/** A mouse button: 0 left, 1 right, 2 middle, 3+ extra buttons. */
class RawMouseButton internal constructor(val button: Int, val action: InputAction, val modifiers: Int, synthetic: Boolean) : InputEvent(synthetic) {
    override fun toString() = "RawMouseButton($button $action)"
}

/** The scroll wheel: [y] is up/down (positive is away from you), [x] sideways. */
class RawScroll internal constructor(val x: Double, val y: Double, synthetic: Boolean) : InputEvent(synthetic) {
    override fun toString() = "RawScroll($x, $y)"
}

/** The mouse moved to [x], [y] (window pixels) by [dx], [dy]. In game, that turns the camera. */
class RawMouseMove internal constructor(val x: Double, val y: Double, val dx: Double, val dy: Double, synthetic: Boolean) : InputEvent(synthetic) {
    override fun toString() = "RawMouseMove($x, $y)"
}

/** See every key press, release and repeat before the game does; [RawKey.cancel] hides it from the game. */
fun Minekt.onRawKey(handler: (RawKey) -> Unit) = addInput(RawKey::class.java, handler)

/** See mouse buttons and the scroll wheel before the game does. */
fun Minekt.onRawMouse(handler: (InputEvent) -> Unit) {
    addInput(RawMouseButton::class.java, handler)
    addInput(RawScroll::class.java, handler)
}

/** See mouse movement before the game does: many events per frame, so keep it very quick. */
fun Minekt.onMouseMove(handler: (RawMouseMove) -> Unit) = addInput(RawMouseMove::class.java, handler)

@Suppress("UNCHECKED_CAST")
private fun <T : InputEvent> Minekt.addInput(type: Class<T>, handler: (T) -> Unit) {
    job.checkAlive()
    job.inputHandlers += Job.InputHandler(type) { handler(it as T) }
    if (type == RawMouseMove::class.java) InputHooks.listeningToMoves = true else InputHooks.listening = true
}

/** Press [key] (`"e"`, `"f3"`, `"left.shift"`, `"key.keyboard.e"`) as if the player did, until [keyUp]. */
fun Minekt.keyDown(key: String) = sendKey(key, InputConstants.PRESS)

fun Minekt.keyUp(key: String) = sendKey(key, InputConstants.RELEASE)

/** Press and release [key]. */
fun Minekt.tapKey(key: String) {
    keyDown(key)
    keyUp(key)
}

/** Type [text] into whatever has focus (chat, a sign, a text box), one character at a time. */
fun Minekt.typeText(text: String) = game { mc ->
    InputHooks.sending { text.codePoints().forEach { mc.keyboardHandler.charTyped(mc.window.handle(), CharacterEvent(it)) } }
}

/** Press mouse [button] (0 left, 1 right, 2 middle) until [mouseUp]. */
fun Minekt.mouseDown(button: Int = 0) = sendButton(button, InputConstants.PRESS)

fun Minekt.mouseUp(button: Int = 0) = sendButton(button, InputConstants.RELEASE)

/** Click mouse [button] where the cursor is. */
fun Minekt.clickMouse(button: Int = 0) {
    mouseDown(button)
    mouseUp(button)
}

/** Turn the scroll wheel: positive is away from you (up), [x] is sideways. */
fun Minekt.scrollMouse(y: Double, x: Double = 0.0) = game { mc ->
    InputHooks.sending { mc.mouseHandler.onScroll(mc.window.handle(), x, y) }
}

/** Move the mouse by [dx], [dy] pixels: turns the camera in game, moves the cursor in screens. */
fun Minekt.moveMouse(dx: Double, dy: Double) = game { mc ->
    val mouse = mc.mouseHandler
    InputHooks.sending {
        // The game ignores the first movement after it captures the mouse: give it an empty one to ignore.
        mouse.onMove(mc.window.handle(), mouse.xpos(), mouse.ypos(), 0.0, 0.0)
        mouse.onMove(mc.window.handle(), mouse.xpos() + dx, mouse.ypos() + dy, dx, dy)
    }
}

private fun Minekt.sendKey(name: String, action: Int) {
    val key = game { _ ->
        runCatching { InputConstants.getKey(keyName(name)) }.getOrNull()
            ?.takeIf { it != InputConstants.UNKNOWN && it.type == InputConstants.Type.KEYBOARD }
            ?: throw IllegalArgumentException("Unknown key '$name' (use names like \"e\", \"f3\", \"left.shift\")")
    }
    held(Release(job.host, key.value, button = false), action)
    game { mc -> keyEvent(mc, key.value, action) }
}

private fun Minekt.sendButton(button: Int, action: Int) {
    require(button in 0..7) { "Mouse buttons are 0 to 7" }
    held(Release(job.host, button, button = true), action)
    game { mc -> buttonEvent(mc, button, action) }
}

/** Keys and buttons a script holds down are let go when it ends. */
private fun Minekt.held(release: Release, action: Int) {
    if (action == InputConstants.PRESS) job.cleanups.addIfAbsent(release) else job.cleanups.remove(release)
}

private data class Release(val host: Host, val code: Int, val button: Boolean) : () -> Unit {
    override fun invoke() {
        host.onGameThread { mc -> if (button) buttonEvent(mc, code, InputConstants.RELEASE) else keyEvent(mc, code, InputConstants.RELEASE) }
    }
}

/** A key's value is its SDL scancode; the event also carries the key code for that layout. */
private fun keyEvent(mc: Minecraft, scancode: Int, action: Int) {
    val keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, 0, false)
    InputHooks.sending { mc.keyboardHandler.keyPress(mc.window.handle(), action, KeyEvent(scancode, keycode, 0)) }
}

private fun buttonEvent(mc: Minecraft, button: Int, action: Int) {
    InputHooks.sending { mc.mouseHandler.onButton(mc.window.handle(), MouseButtonInfo(toGameButton(button), 0), action) }
}

/** Scripts number buttons 0 left, 1 right, 2 middle; the game (SDL) uses 1 left, 2 middle, 3 right. */
internal fun toGameButton(button: Int) = when (button) {
    0 -> InputConstants.MOUSE_BUTTON_LEFT
    1 -> InputConstants.MOUSE_BUTTON_RIGHT
    2 -> InputConstants.MOUSE_BUTTON_MIDDLE
    else -> button + 1
}

internal fun fromGameButton(button: Int) = when (button) {
    InputConstants.MOUSE_BUTTON_LEFT -> 0
    InputConstants.MOUSE_BUTTON_RIGHT -> 1
    InputConstants.MOUSE_BUTTON_MIDDLE -> 2
    else -> button - 1
}
