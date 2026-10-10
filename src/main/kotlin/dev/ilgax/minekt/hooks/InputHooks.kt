package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.api.InputAction
import dev.ilgax.minekt.api.InputEvent
import dev.ilgax.minekt.api.RawKey
import dev.ilgax.minekt.api.RawMouseButton
import dev.ilgax.minekt.api.RawMouseMove
import dev.ilgax.minekt.api.RawScroll
import dev.ilgax.minekt.api.fromGameButton

/**
 * Entry points for the keyboard and mouse mixins, on the game thread. [filter] (the job manager)
 * runs scripts' input listeners and says whether to cancel. The `listening…` flags keep the mixins
 * idle while no script listens; moves are separate because there are so many of them.
 */
object InputHooks {
    @JvmField @Volatile var listening = false
    @JvmField @Volatile var listeningToMoves = false
    @Volatile internal var filter: (InputEvent) -> Boolean = { false }

    /** True while Minekt itself is sending input, so listeners can tell real input from sent. */
    private var sending = false

    internal fun <T> sending(block: () -> T): T {
        val before = sending
        sending = true
        try {
            return block()
        } finally {
            sending = before
        }
    }

    @JvmStatic
    fun key(key: Int, action: Int, modifiers: Int): Boolean =
        filter(RawKey(InputConstants.Type.KEYBOARD.getOrCreate(key).name, InputAction.of(action), modifiers, sending))

    @JvmStatic
    fun button(button: Int, action: Int, modifiers: Int): Boolean = filter(RawMouseButton(fromGameButton(button), InputAction.of(action), modifiers, sending))

    @JvmStatic
    fun scroll(x: Double, y: Double): Boolean = filter(RawScroll(x, y, sending))

    @JvmStatic
    fun move(x: Double, y: Double, dx: Double, dy: Double): Boolean = filter(RawMouseMove(x, y, dx, dy, sending))
}
