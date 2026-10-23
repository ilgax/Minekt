@file:OptIn(dev.ilgax.minekt.api.ExperimentalBaritone::class)

package dev.ilgax.minekt.api

import kotlin.time.Duration
import java.lang.reflect.Method

/**
 * Thin wrapper over Baritone's public API (`baritone.api.*`), accessed reflectively so Minekt
 * works whether or not Baritone is installed. For anything not covered here, scripts can import
 * `baritone.api.BaritoneAPI` directly — every installed mod is on the script classpath.
 */
@ExperimentalBaritone
class Baritone internal constructor(private val api: Minekt) {
    private val apiClass: Class<*>? by lazy { load("baritone.api.BaritoneAPI") }

    val isAvailable: Boolean get() = apiClass != null

    /** Run any Baritone command, e.g. `command("goto 100 64 -20")`. Returns Baritone's success flag. */
    fun command(command: String): Boolean = api.game {
        val manager = call(primary(), "baritone.api.IBaritone", "getCommandManager")
        call(manager, "baritone.api.command.manager.ICommandManager", "execute", command) as Boolean
    }

    fun goto(x: Int, y: Int, z: Int) = command("goto $x $y $z")
    fun goto(x: Int, z: Int) = command("goto $x $z")
    fun mine(vararg blocks: String) = command("mine ${blocks.joinToString(" ")}")
    fun follow(player: String) = command("follow player $player")
    fun explore() = command("explore")
    fun cancel() = command("stop")

    /** True while Baritone is pathing or any of its processes (mine, follow, ...) is in control. */
    val isActive: Boolean
        get() = api.game {
            val baritone = primary()
            val pathing = call(baritone, "baritone.api.IBaritone", "getPathingBehavior")
            val isPathing = call(pathing, "baritone.api.behavior.IPathingBehavior", "isPathing") as Boolean
            val control = call(baritone, "baritone.api.IBaritone", "getPathingControlManager")
            val inControl = call(control, "baritone.api.pathing.calc.IPathingControlManager", "mostRecentInControl") as java.util.Optional<*>
            isPathing || inControl.isPresent
        }

    /** Wait until Baritone finishes (or [timeout] passes). Returns false on timeout. */
    fun await(timeout: Duration = Duration.INFINITE): Boolean {
        val deadline = if (timeout == Duration.INFINITE) Long.MAX_VALUE else System.currentTimeMillis() + timeout.inWholeMilliseconds
        api.waitTicks(5) // give a freshly issued command a moment to take control
        while (isActive) {
            if (System.currentTimeMillis() >= deadline) return false
            api.waitTicks(5)
        }
        return true
    }

    private fun primary(): Any {
        val cls = apiClass ?: error("Baritone is not installed")
        val provider = cls.getMethod("getProvider").invoke(null)
        return call(provider, "baritone.api.IBaritoneProvider", "getPrimaryBaritone")
    }

    private fun call(target: Any, iface: String, name: String, vararg args: Any): Any {
        val method: Method = load(iface)!!.methods.first { m ->
            m.name == name && m.parameterCount == args.size &&
                m.parameterTypes.zip(args).all { (type, arg) -> type.isInstance(arg) }
        }
        return method.invoke(target, *args)
    }

    private fun load(name: String): Class<*>? =
        runCatching { Class.forName(name, true, Baritone::class.java.classLoader) }.getOrNull()
}
