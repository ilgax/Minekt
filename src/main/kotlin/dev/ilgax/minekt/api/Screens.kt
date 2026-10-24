package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.clientHost
import dev.ilgax.minekt.hooks.ScriptScreenView
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KMutableProperty0

// Real Minecraft screens built by scripts: settings menus, pickers, dashboards.
//
//   var autoEat by persistent { true }
//   var range by persistent { 32 }
//   keybind("Miner settings").onPress {
//       showScreen("Miner settings") {
//           text("Changes apply right away")
//           toggle("Auto eat", ::autoEat)
//           slider("Range", 8..64, ::range)
//           row {
//               button("Start") { start() }
//               button("Stop") { stop() }
//           }
//       }
//   }
//
// Widgets bound to a property (`::autoEat`) update it as the player clicks; the other forms take
// the current value and a callback. Callbacks run on the script's side, one at a time, alongside
// the script body, so they can use the whole API. A script stays alive while its screen is open.

/**
 * Open a screen titled [title] with the widgets [build] adds. Returns a handle to close or rebuild
 * it. A "Done" button is added at the bottom unless [doneButton] is false; Esc always closes it.
 */
fun Minekt.showScreen(title: String, doneButton: Boolean = true, build: ScreenBuilder.() -> Unit): ScriptScreen {
    job.checkAlive()
    val screen = ScriptScreen(this, title, doneButton, build)
    screen.open()
    return screen
}

@DslMarker
annotation class ScreenDsl

/** What goes on a script screen, top to bottom. See [showScreen]. */
@ScreenDsl
class ScreenBuilder internal constructor() {
    internal val items = mutableListOf<ScreenItem>()

    /** A line of text (`\n` for more lines). */
    fun text(text: String) {
        items += ScreenItem.Text({ text }, live = false)
    }

    /** Text that is recomputed every tick while the screen is open, e.g. `text { "HP ${player.health}" }`. */
    fun text(live: () -> String) {
        items += ScreenItem.Text(live, live = true)
    }

    /** Left-aligned text that wraps; `§` color codes work. Used by Minekt's own screens. */
    internal fun leftText(text: String) {
        items += ScreenItem.Text({ text }, live = false, left = true)
    }

    fun button(label: String, tooltip: String? = null, onClick: () -> Unit) {
        items += ScreenItem.Button(label, tooltip, onClick)
    }

    /** An on/off switch. */
    fun toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        items += ScreenItem.Toggle(label, value, onChange)
    }

    fun toggle(label: String, property: KMutableProperty0<Boolean>) = toggle(label, property.get(), property::set)

    /** A whole-number slider. */
    fun slider(label: String, range: IntRange, value: Int, onChange: (Int) -> Unit) {
        require(!range.isEmpty()) { "Empty slider range" }
        items += ScreenItem.IntSlider(label, range, value.coerceIn(range), onChange)
    }

    fun slider(label: String, range: IntRange, property: KMutableProperty0<Int>) = slider(label, range, property.get(), property::set)

    /** A decimal slider, snapping to multiples of [step]. */
    fun slider(label: String, range: ClosedFloatingPointRange<Double>, value: Double, step: Double = 0.1, onChange: (Double) -> Unit) {
        require(step > 0 && range.endInclusive > range.start) { "Slider needs a range and a step above 0" }
        items += ScreenItem.DoubleSlider(label, range, step, value.coerceIn(range), onChange)
    }

    fun slider(label: String, range: ClosedFloatingPointRange<Double>, property: KMutableProperty0<Double>, step: Double = 0.1) =
        slider(label, range, property.get(), step, property::set)

    /** A text box. [onChange] gets the text after every edit. */
    fun textField(label: String, value: String, maxLength: Int = 256, onChange: (String) -> Unit) {
        items += ScreenItem.TextField(label, value, maxLength, onChange)
    }

    fun textField(label: String, property: KMutableProperty0<String>, maxLength: Int = 256) =
        textField(label, property.get(), maxLength, property::set)

    /** A button that cycles through [options] on click. */
    fun <T> choice(label: String, options: List<T>, value: T, display: (T) -> String = { it.toString() }, onChange: (T) -> Unit) {
        require(options.isNotEmpty()) { "choice needs at least one option" }
        @Suppress("UNCHECKED_CAST")
        items += ScreenItem.Choice(label, options as List<Any?>, if (value in options) value else options.first(), display as (Any?) -> String, onChange as (Any?) -> Unit)
    }

    fun <T> choice(label: String, options: List<T>, property: KMutableProperty0<T>, display: (T) -> String = { it.toString() }) =
        choice(label, options, property.get(), display, property::set)

    /** Put widgets side by side, sharing the width equally. */
    fun row(build: ScreenBuilder.() -> Unit) {
        items += ScreenItem.Row(ScreenBuilder().apply(build).items)
    }
}

/** Widget descriptions. Values are updated in place as the player edits them, so rebuilding a layout keeps them. */
internal sealed interface ScreenItem {
    /** [left]: left-aligned and wrapped instead of centered and cut (Minekt's log viewer). */
    class Text(val text: () -> String, val live: Boolean, val left: Boolean = false) : ScreenItem
    class Button(val label: String, val tooltip: String?, val onClick: () -> Unit) : ScreenItem
    class Toggle(val label: String, var value: Boolean, val onChange: (Boolean) -> Unit) : ScreenItem
    class IntSlider(val label: String, val range: IntRange, var value: Int, val onChange: (Int) -> Unit) : ScreenItem
    class DoubleSlider(val label: String, val range: ClosedFloatingPointRange<Double>, val step: Double, var value: Double, val onChange: (Double) -> Unit) : ScreenItem
    class TextField(val label: String, var value: String, val maxLength: Int, val onChange: (String) -> Unit) : ScreenItem
    class Choice(val label: String, val options: List<Any?>, var value: Any?, val display: (Any?) -> String, val onChange: (Any?) -> Unit) : ScreenItem
    class Row(val items: List<ScreenItem>) : ScreenItem
}

/** A screen opened by [showScreen]. */
class ScriptScreen internal constructor(
    private val api: Minekt,
    private val title: String,
    private val doneButton: Boolean,
    private val build: ScreenBuilder.() -> Unit,
) {
    private val job = api.job
    private val closeHandlers = CopyOnWriteArrayList<() -> Unit>()
    private val closed = CompletableDeferred<Unit>()
    @Volatile private var view: ScriptScreenView? = null

    val isOpen: Boolean get() = !closed.isCompleted

    internal fun open() {
        val items = ScreenBuilder().apply(build).items
        job.openScreens.incrementAndGet()
        job.cleanups += ::closeNow
        try {
            api.game { mc ->
                val screen = ScriptScreenView(title, items, doneButton, job::runCallback, ::removed)
                view = screen
                mc.gui.setScreen(screen)
            }
        } catch (t: Throwable) {
            removed()
            throw t
        }
    }

    /** Close it (if the player hasn't already). */
    fun close() {
        job.checkAlive()
        closeNow()
    }

    /** Run the builder again and show the result, e.g. after the list it shows has changed. */
    fun refresh() {
        job.checkAlive()
        if (!isOpen) return
        val items = ScreenBuilder().apply(build).items
        api.game { view?.replaceItems(items) }
    }

    /** Call [handler] once the screen is closed, by the player or by [close]. */
    fun onClose(handler: () -> Unit) {
        if (isOpen) closeHandlers += handler else job.runCallback(handler)
    }

    /** Suspend until the screen is closed. */
    suspend fun awaitClose() = closed.await()

    /** Block the script until the screen is closed. */
    fun waitForClose() {
        while (isOpen) api.sleep(50)
    }

    private fun closeNow() {
        val screen = view ?: return
        runCatching { job.clientHost.onGameThread { mc -> if (mc.gui.screen() === screen) mc.gui.setScreen(null) } }
    }

    /** Game thread, when the screen goes away for any reason. */
    private fun removed() {
        if (!closed.complete(Unit)) return
        // Queue the handlers before letting go of the job, so it stays alive to run them.
        closeHandlers.forEach(job::runCallback)
        job.openScreens.decrementAndGet()
        view = null
    }
}
