package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.Blaze3D
import dev.ilgax.minekt.api.ScreenBuilder
import dev.ilgax.minekt.api.ScreenItem
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** Minekt's own settings: from Mod Menu's Configure button, or Minekt settings in `\menu`. */
internal class MinektSettingsView(
    private val scriptDir: Path,
    private val openManager: (Minecraft) -> Unit,
) {
    fun screen(parent: Screen?): Screen = ScriptScreenView("Minekt settings", items(), doneButton = true, runCallback = { it() }, parent = parent)

    fun open(mc: Minecraft, parent: Screen? = mc.gui.screen()) = mc.gui.setScreen(screen(parent))

    private fun items(): List<ScreenItem> = ScreenBuilder().apply {
        text("Command prefix: what chat commands start with (\\menu, \\name…). /mk works as well, always.")
        textField("Prefix", MinektConfig.prefix, maxLength = 4) {
            currentPrefix = it
            MinektConfig.setPrefix(it)
        }
        text { MinektConfig.prefixProblem(currentPrefix)?.let { "§c$it" } ?: "§7Commands: ${MinektConfig.prefix}menu, ${MinektConfig.prefix}help" }

        text("\nEditor: the command that opens scripts for Edit and New script. Empty: your system's default app.")
        textField("Editor", MinektConfig.editor, maxLength = 512) { MinektConfig.setEditor(it) }
        text("§7Like: code   ·   notepad++   ·   \"C:\\Program Files\\Sublime Text\\subl.exe\" {file}")

        text("\nFiles")
        row {
            button("Script manager") { openManager(Minecraft.getInstance()) }
            button("Scripts folder") { Files.createDirectories(scriptDir); Blaze3D.openPath(scriptDir) }
            button("autorun.txt", tooltip = "Commands that run on their own: on joining a world or server, or at startup") {
                val autorun = scriptDir.resolve("autorun.txt")
                if (!Files.exists(autorun)) Files.writeString(autorun, AUTORUN_TEMPLATE)
                MinektConfig.edit(autorun)
            }
            button("Docs", tooltip = DOCS) { Blaze3D.openUri(URI.create(DOCS)) }
        }
    }.items

    /** The prefix being typed, to show its problem (the field only saves valid ones). */
    private var currentPrefix = MinektConfig.prefix

    private companion object {
        const val DOCS = "https://github.com/ilgax/Minekt/tree/master/docs"
        val AUTORUN_TEMPLATE = """
            # Commands to run on their own, one per line, like \name args. Lines up here run on joining any world.
            # Sections limit where the lines below them run: [server play.example.net], [world My World],
            # [dimension the_nether], [singleplayer], [multiplayer], [always], or [startup] (when the game starts).

        """.trimIndent()
    }
}
