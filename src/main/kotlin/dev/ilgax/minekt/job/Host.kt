package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.script.TemplateCompiler
import java.nio.file.Path

/**
 * The environment jobs run in: the client, or a server. Abstracted so the engine can be exercised
 * without a running game. Nothing here may touch client classes: dedicated servers don't have them.
 */
interface Host {
    /** Directory scripts are loaded from (`.minecraft/minekt`, or a server's `minekt`). */
    val scriptDir: Path

    /** Counts this side's ticks, for `waitTicks`. */
    val tickClock: TickClock

    /** Compiles this side's scripts. */
    val compiler: TemplateCompiler

    /** The implicit receiver of a script run by [job]. */
    fun receiver(job: Job): ScriptContext

    /** Show a message to the player (on a server: to the operators online, and the server log). */
    fun echo(message: String, kind: MessageKind = MessageKind.SCRIPT)

    /** Show styled text (colors, hover, clicks) to the player; [plain] is the same without styles. */
    fun echo(message: net.minecraft.network.chat.Component, plain: String) = echo(plain)

    /** Whether this is the game's own thread, where scripts must never wait. */
    fun isGameThread(): Boolean = false

    /**
     * Make `/name` a command owned by [owner]; invocations arrive as [Job.Event.Command].
     * Returns an error message if the name is taken, else null.
     */
    fun registerCommand(name: String, completions: List<String>, owner: Job): String?

    fun unregisterCommand(name: String, owner: Job)

    /** Show a script error. Defaults to its one-line [ErrorReport.headline]. */
    fun showError(report: ErrorReport) = echo(report.headline, MessageKind.ERROR)

    /** The command (without `/`) that clickable chat text runs to reach `ChatClick.run` action [id]. */
    fun clickCommand(id: Long): String = "minekt click $id"

    /** How players type Minekt's own commands here, in messages: `\` on the client, `/mks ` on a server. */
    val commandPrefix: String get() = "\\"
}

/** A keybind made by [ClientHost.addKeybind]. */
interface KeybindHandle {
    /** The bound key's name as the player sees it, e.g. "F" or "Not Bound". */
    val keyName: String
    val isDown: Boolean
    fun remove()
}

enum class MessageKind { SCRIPT, INFO, ERROR }
