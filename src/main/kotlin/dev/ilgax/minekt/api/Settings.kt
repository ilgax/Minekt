package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.ScriptSettings
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Settings: values the player can change without editing the script.
//
//   var range by setting(16, 1..64)
//   var autoEat by setting(true)
//   var speed by setting(1.0, 0.5..3.0, step = 0.25)
//   var mode by setting("walk", listOf("walk", "sprint"))
//   var greeting by setting("Hello", label = "Greeting text")
//
// They show up in the script manager (the script's Settings button, or `\settings <script>`), as
// toggles, sliders, choices and text boxes, also while the script isn't running. Changes are saved
// in `minekt/settings/<script>.json` and reach a running script right away: the property always
// reads the current value. `onSettingChange` tells the script when one changes.

/** An on/off setting. */
fun ScriptContext.setting(default: Boolean, label: String? = null): SettingProperty<Boolean> =
    SettingProperty(this, label, ScriptSettings.Kind.Toggle, default)

/** A whole number in [range]. */
fun ScriptContext.setting(default: Int, range: IntRange, label: String? = null): SettingProperty<Int> {
    require(!range.isEmpty()) { "Empty setting range" }
    return SettingProperty(this, label, ScriptSettings.Kind.Whole(range.first, range.last), default)
}

/** A decimal number in [range], adjusted in steps of [step]. */
fun ScriptContext.setting(default: Double, range: ClosedFloatingPointRange<Double>, step: Double = 0.1, label: String? = null): SettingProperty<Double> {
    require(step > 0 && range.endInclusive > range.start) { "A decimal setting needs a range and a step above 0" }
    return SettingProperty(this, label, ScriptSettings.Kind.Decimal(range.start, range.endInclusive, step), default)
}

/** A line of text. */
fun ScriptContext.setting(default: String, label: String? = null, maxLength: Int = 256): SettingProperty<String> =
    SettingProperty(this, label, ScriptSettings.Kind.Text(maxLength), default)

/** One of [options]. */
fun ScriptContext.setting(default: String, options: List<String>, label: String? = null): SettingProperty<String> {
    require(default in options) { "The default '$default' isn't one of the options" }
    return SettingProperty(this, label, ScriptSettings.Kind.Choice(options), default)
}

/** Run [handler] (on the script's thread) when the player changes a setting: its property name and new value. */
fun ScriptContext.onSettingChange(handler: (SettingChange) -> Unit): Handler {
    job.checkAlive()
    return addHandler(job.settingHandlers, handler)
}

class SettingProperty<T : Any> internal constructor(
    private val api: ScriptContext,
    private val label: String?,
    private val kind: ScriptSettings.Kind,
    private val default: T,
) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, T> {
        val job = api.job
        job.checkAlive()
        val key = property.name
        require(job.declaredSettings.add(key)) { "Setting '$key' is declared twice" }
        val initial = job.settings.declare(job.name, key, label ?: humanize(key), kind, default)
        @Suppress("UNCHECKED_CAST")
        fun cast(value: Any?): T = (if (default is Int && value is Number) value.toInt() else value ?: initial) as T
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = cast(job.settings.get(job.name, key))
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                job.settings.set(job.name, key, value, notify = false)
            }
        }
    }
}

/** `autoEat` → `Auto eat`, `max_range` → `Max range`. */
internal fun humanize(name: String): String =
    name.replace('_', ' ').replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase().replaceFirstChar { it.uppercase() }.trim()
