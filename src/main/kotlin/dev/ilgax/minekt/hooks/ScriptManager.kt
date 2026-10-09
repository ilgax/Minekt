package dev.ilgax.minekt.hooks

import com.mojang.blaze3d.Blaze3D
import dev.ilgax.minekt.api.ScreenBuilder
import dev.ilgax.minekt.api.ScreenItem
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.splitArgs
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path

/**
 * The script manager screen (`\menu` or its key in Controls): running scripts with their CPU use
 * and a Stop button, every script with Run / Edit, arguments, installing from a link, recent errors.
 * Built from the same widgets scripts use; it rebuilds itself when scripts start or stop.
 */
internal class ScriptManager(
    private val jobs: JobManager,
    private val scriptDir: Path,
    private val install: (String) -> Unit,
    private val recentErrors: () -> List<String>,
) {
    private var args = ""
    private var link = ""

    fun open(mc: Minecraft) {
        mc.gui.setScreen(ScriptScreenView("Minekt scripts", items(), doneButton = true, runCallback = { it() }, rebuild = ::items, signature = ::signature))
    }

    /** What the screen shows, minus live numbers: when this changes, the screen is rebuilt. */
    private fun signature() = Triple(jobs.jobs.map { it.id to it.state }, jobs.availableScripts(), recentErrors())

    private fun items(): List<ScreenItem> = ScreenBuilder().apply {
        val running = jobs.jobs.toList()
        text(if (running.isEmpty()) "No scripts running" else "Running")
        for (job in running) row {
            text { "[${job.id}] ${job.name} ${job.args.joinToString(" ")} · ${job.state.name.lowercase()}" + (job.cpuPercent?.let { " · $it% CPU" } ?: "") }
            button("Stop", tooltip = "\\kill ${job.id}") { jobs.kill(job.id) }
        }

        text("\nScripts in ${scriptDir.fileName}/")
        textField("Arguments", args) { args = it }
        val scripts = jobs.availableScripts()
        if (scripts.isEmpty()) text("No scripts yet: put .kts files in the folder")
        for (name in scripts) row {
            button("▶ $name", tooltip = "Run \\$name with the arguments above") { jobs.runScript(name, splitArgs(args)) }
            button("Edit", tooltip = "Open $name.kts in your editor") { Blaze3D.openPath(scriptDir.resolve("$name.kts")) }
        }
        row {
            button("Open folder") { Files.createDirectories(scriptDir); Blaze3D.openPath(scriptDir) }
            button("New script") { newScript()?.let { Blaze3D.openPath(it) } }
        }

        text("\nInstall from a link")
        textField("Link", link, maxLength = 2048) { link = it }
        button("Install", tooltip = "Shows a preview in chat first") {
            if (link.isNotBlank()) {
                install(link.trim())
                link = ""
                Minecraft.getInstance().gui.setScreen(null) // the preview and its buttons are in chat
            }
        }

        val errors = recentErrors()
        if (errors.isNotEmpty()) {
            text("\nRecent errors")
            errors.forEach { text(it) }
        }
    }.items

    /** Create `script<N>.kts` with a starter line and return it. */
    private fun newScript(): Path? {
        Files.createDirectories(scriptDir)
        val name = generateSequence(1) { it + 1 }.map { "script$it" }.first { !Files.exists(scriptDir.resolve("$it.kts")) }
        return Files.writeString(scriptDir.resolve("$name.kts"), "// \\$name — what it does\necho(\"Hello from $name\")\n")
    }
}
