package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.script.TemplateCompiler
import net.minecraft.client.Minecraft

/** The client: scripts get a [dev.ilgax.minekt.api.Minekt] and can reach the game through [onGameThread]. */
interface ClientHost : Host {
    override val compiler: TemplateCompiler get() = dev.ilgax.minekt.script.ScriptCompiler

    override fun receiver(job: Job): ScriptContext = dev.ilgax.minekt.api.Minekt(job)

    /** Run [block] on the client thread and wait for its result. */
    fun <T> onGameThread(block: (Minecraft) -> T): T

    /**
     * Add a keybind to Options → Controls for [owner], shown as [label], bound to [defaultKey] (a
     * `key.keyboard.…` / `key.mouse.…` name, or null for none) unless the player chose another key
     * before. Presses arrive as [Job.Event.Keybind] with [id]. Throws if another script owns [id].
     */
    fun addKeybind(owner: Job, id: String, label: String, defaultKey: String?): KeybindHandle
}

/** This job's host as the client, for API calls that only exist there. */
internal val Job.clientHost: ClientHost
    get() = host as? ClientHost ?: error("This only works in client scripts")
