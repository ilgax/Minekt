package dev.ilgax.minekt.job

import net.minecraft.client.Minecraft
import java.nio.file.Path

/** The environment jobs run in. Abstracted so the engine can be exercised without a running game. */
interface Host {
    /** Directory scripts are loaded from (`.minecraft/minekt`). */
    val scriptDir: Path

    /** Show a message to the player. */
    fun echo(message: String, kind: MessageKind = MessageKind.SCRIPT)

    /** Run [block] on the client thread and wait for its result. */
    fun <T> onGameThread(block: (Minecraft) -> T): T

    /**
     * Make `/name` a client-side command owned by [owner]; invocations arrive as [Job.Event.Command].
     * Returns an error message if the name is taken, else null.
     */
    fun registerCommand(name: String, completions: List<String>, owner: Job): String?

    fun unregisterCommand(name: String, owner: Job)

    /** Display name of the key bound to script keybind [slot] (1-based), e.g. "K" or "Not Bound". */
    fun bindingKeyName(slot: Int): String

    /** Show a script error. Defaults to its one-line [ErrorReport.headline]. */
    fun showError(report: ErrorReport) = echo(report.headline, MessageKind.ERROR)
}

enum class MessageKind { SCRIPT, INFO, ERROR }
