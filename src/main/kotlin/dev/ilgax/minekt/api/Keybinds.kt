package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.KeybindHandle
import java.util.concurrent.CopyOnWriteArrayList

// Keybinds the player can change in Options → Controls → Minekt:
//
//   val fly = keybind("Toggle fly", default = "f")
//   fly.onPress { flying = !flying }          // once per press, even when held
//   fly.onRelease { echo("let go") }
//   onTick { if (fly.isDown) … }
//   echo("Press ${fly.key} to fly")
//
// A keybind exists while its script runs. The key the player picks is remembered for the next time
// the script makes the same keybind (`minekt/settings/keybinds.json`).

/**
 * Add a keybind called [name] to Options → Controls → Minekt, bound to [default] (`"f"`, `"f6"`,
 * `"left.shift"`, `"mouse.middle"`, or null for none) until the player picks another key there.
 * Calling it again with the same name returns the same keybind.
 */
fun Minekt.keybind(name: String, default: String? = null): Keybind {
    job.checkAlive()
    require(name.isNotBlank()) { "A keybind needs a name" }
    val id = "${job.name}/$name"
    val handle = job.host.addKeybind(job, id, name, default?.let(::keyName))
    job.cleanups.addIfAbsent(job.keybindCleanup(id, handle))
    return Keybind(job, id, name, handle)
}

/** A keybind made by [keybind]. */
class Keybind internal constructor(private val job: Job, private val id: String, val name: String, private val handle: KeybindHandle) {
    /** The key it's bound to, as the player sees it: `"F"`, `"Left Shift"`, `"Not Bound"`. */
    val key: String get() = handle.keyName

    /** Whether the key is held down right now. */
    val isDown: Boolean get() = handle.isDown

    /** Run [handler] (on the script's thread) each time the key goes down: once per press, however long it's held. Keeps the script alive. */
    fun onPress(handler: () -> Unit): Handler = add(true, handler)

    /** Run [handler] each time the key is let go. Keeps the script alive. */
    fun onRelease(handler: () -> Unit): Handler = add(false, handler)

    private fun add(press: Boolean, handler: () -> Unit): Handler {
        job.checkAlive()
        return addHandler(job.keybindHandlers.computeIfAbsent(id) { CopyOnWriteArrayList() }, Job.KeybindHandler(press, handler))
    }

    /** Suspend until the key is pressed. */
    suspend fun awaitPress() {
        job.awaitEvent { it is Job.Event.Keybind && it.id == id && it.pressed }
    }

    /** Suspend until the key is let go. */
    suspend fun awaitRelease() {
        job.awaitEvent { it is Job.Event.Keybind && it.id == id && !it.pressed }
    }

    /** Take it out of Controls now (it also goes when the script ends). */
    fun remove() {
        job.keybindHandlers.remove(id)
        handle.remove()
    }

    override fun toString() = "Keybind($name: $key)"
}

/** `"f"` → `key.keyboard.f`, `"mouse.middle"` → `key.mouse.middle`; full names pass through. */
internal fun keyName(name: String): String = when {
    name.startsWith("key.") -> name
    name.startsWith("mouse.") -> "key.$name"
    else -> "key.keyboard.${name.lowercase()}"
}

private fun Job.keybindCleanup(id: String, handle: KeybindHandle): () -> Unit = KeybindCleanup(id, handle)

/** Equal per keybind, so calling `keybind` twice doesn't queue the cleanup twice. */
private data class KeybindCleanup(val id: String, val handle: KeybindHandle) : () -> Unit {
    override fun invoke() = handle.remove()
}
