package dev.ilgax.minekt.job

import net.minecraft.client.Minecraft
import java.nio.file.Path

/** The environment jobs run in. Abstracted so the engine can be exercised without a running game. */
interface Host {
    /** Directory scripts are loaded from (`.minecraft/minekt`). */
    val scriptDir: Path

    /** Show a message to the player. */
    fun echo(message: String, kind: MessageKind = MessageKind.SCRIPT)

    /** Show styled text (colors, hover, clicks) to the player; [plain] is the same without styles. */
    fun echo(message: net.minecraft.network.chat.Component, plain: String) = echo(plain)

    /** Run [block] on the client thread and wait for its result. */
    fun <T> onGameThread(block: (Minecraft) -> T): T

    /** Whether this is the game's own thread, where scripts must never wait. */
    fun isGameThread(): Boolean = false

    /**
     * Make `/name` a client-side command owned by [owner]; invocations arrive as [Job.Event.Command].
     * Returns an error message if the name is taken, else null.
     */
    fun registerCommand(name: String, completions: List<String>, owner: Job): String?

    fun unregisterCommand(name: String, owner: Job)

    /**
     * Add a keybind to Options → Controls for [owner], shown as [label], bound to [defaultKey] (a
     * `key.keyboard.…` / `key.mouse.…` name, or null for none) unless the player chose another key
     * before. Presses arrive as [Job.Event.Keybind] with [id]. Throws if another script owns [id].
     */
    fun addKeybind(owner: Job, id: String, label: String, defaultKey: String?): KeybindHandle

    /** Show a script error. Defaults to its one-line [ErrorReport.headline]. */
    fun showError(report: ErrorReport) = echo(report.headline, MessageKind.ERROR)
}

/** A keybind made by [Host.addKeybind]. */
interface KeybindHandle {
    /** The bound key's name as the player sees it, e.g. "F" or "Not Bound". */
    val keyName: String
    val isDown: Boolean
    fun remove()
}

enum class MessageKind { SCRIPT, INFO, ERROR }
