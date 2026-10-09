package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.Blaze3D
import dev.ilgax.minekt.api.ScreenBuilder
import dev.ilgax.minekt.api.ScreenItem
import dev.ilgax.minekt.job.ScriptSettings
import net.minecraft.client.Minecraft
import java.nio.file.Files

/** `\settings <script>`: a script's `by setting(…)` values as toggles, sliders, choices and text boxes. */
internal class SettingsView(private val settings: ScriptSettings) {
    fun open(mc: Minecraft, script: String, back: (() -> Unit)? = null) {
        mc.gui.setScreen(
            ScriptScreenView(
                "$script settings", items(mc, script, back), doneButton = true, runCallback = { it() },
                // Rebuilt when the script declares different settings, not on value changes (that would reset a dragged slider).
                rebuild = { items(mc, script, back) }, signature = { settings.list(script).map { Triple(it.key, it.label, it.kind) } },
            ),
        )
    }

    private fun items(mc: Minecraft, script: String, back: (() -> Unit)?): List<ScreenItem> = ScreenBuilder().apply {
        val list = settings.list(script)
        if (list.isEmpty()) text("$script has no settings yet. Settings show up here once the script has run.")
        for (setting in list) {
            fun change(value: Any) {
                settings.set(script, setting.key, value)
            }
            when (val kind = setting.kind) {
                ScriptSettings.Kind.Toggle -> toggle(setting.label, setting.value as Boolean, ::change)
                is ScriptSettings.Kind.Whole -> slider(setting.label, kind.min..kind.max, setting.value as Int, ::change)
                is ScriptSettings.Kind.Decimal -> slider(setting.label, kind.min..kind.max, setting.value as Double, kind.step, ::change)
                is ScriptSettings.Kind.Text -> textField(setting.label, setting.value as String, kind.maxLength, ::change)
                is ScriptSettings.Kind.Choice -> choice(setting.label, kind.options, setting.value as String, onChange = ::change)
            }
        }
        row {
            if (list.isNotEmpty()) button("Reset to defaults") {
                settings.reset(script)
                open(mc, script, back)
            }
            button("Open file", tooltip = settings.path(script).toString()) {
                val file = settings.path(script)
                Blaze3D.openPath(if (Files.isRegularFile(file)) file else file.parent.also { Files.createDirectories(it) })
            }
            if (back != null) button("Back") { back() }
        }
    }.items
}
