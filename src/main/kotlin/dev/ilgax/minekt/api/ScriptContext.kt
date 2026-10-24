package dev.ilgax.minekt.api

import kotlin.time.Duration
import net.minecraft.world.phys.Vec3
import net.minecraft.core.BlockPos
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobKilledException
import dev.ilgax.minekt.job.JobStopException
import dev.ilgax.minekt.job.ScriptLogs
import net.fabricmc.loader.api.FabricLoader

/**
 * What every script can use, on the client and on a server: output, waiting, stopping, ticks. Client
 * scripts get a [Minekt], server scripts a [dev.ilgax.minekt.api.server.ServerMinekt]; helpers declared
 * on [ScriptContext] (messages, settings, timers, coroutines, web requests, persistent values, eval…)
 * work in both.
 *
 * Calls run on the script's own thread. Every call checks whether the job was killed, so loops that
 * use the API stay killable.
 */
abstract class ScriptContext internal constructor(internal val job: Job) {
    /** Arguments passed after the script name: `\hello a b` → `["a", "b"]`. */
    val args: List<String> get() = job.args

    val jobId: Int get() = job.id

    // ---- output -------------------------------------------------------------------------------

    /** Print a message: to your chat in a client script, to the operators online and the server log in a server script. */
    fun echo(vararg parts: Any?) {
        job.checkAlive()
        if (parts.none { it is ChatText }) return job.output(plain(parts))
        job.output(styled(parts), plain(parts))
    }

    /** [parts] as the game's text: [ChatText] keeps its styles, the rest is plain, joined with spaces. */
    internal fun styled(parts: Array<out Any?>): net.minecraft.network.chat.MutableComponent {
        val text = net.minecraft.network.chat.Component.empty()
        parts.forEachIndexed { i, part ->
            if (i > 0) text.append(" ")
            if (part is ChatText) text.append(part.toComponent(job)) else text.append(display(part))
        }
        return text
    }

    internal fun plain(parts: Array<out Any?>): String = parts.joinToString(" ", transform = ::display)

    /** How [echo] and [writeLog] show a value: positions as `(x, y, z)`, also inside lists and maps. */
    internal fun display(value: Any?): String = when (value) {
        is Vec3 -> value.short()
        is BlockPos -> value.short()
        is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> display(k) + "=" + display(v) }
        is Iterable<*> -> value.joinToString(", ", "[", "]", transform = ::display)
        else -> value.toString()
    }

    /**
     * Write to this script's log only, not chat: `minekt/logs/<script>.log`, also shown by
     * `\log <script>` and the Log button in the script manager.
     */
    fun writeLog(vararg parts: Any?) {
        job.checkAlive()
        job.logs.add(job.name, ScriptLogs.Level.LOG, parts.joinToString(" ", transform = ::display), run = job.id)
    }

    // ---- timing / control ---------------------------------------------------------------------

    /**
     * Wait [ms] milliseconds. While the script's main code waits, its handlers (`onChat`, `onTick`, keys…)
     * run for events that arrive, so a loop can react to them. Not allowed on the game's thread.
     */
    fun sleep(ms: Long) {
        checkCanWait("sleep")
        val deadline = System.nanoTime() + ms * 1_000_000
        waiting {
            job.waitDelivering({ ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 20) }) { System.nanoTime() >= deadline }
        }
    }

    /** [sleep] for [duration]. */
    fun sleep(duration: Duration) = sleep(duration.inWholeMilliseconds)

    /** Wait for [ticks] game ticks (20 per second: the client's, or the server's), running handlers like [sleep] does. */
    fun waitTicks(ticks: Int = 1) {
        checkCanWait("waitTicks")
        val clock = job.host.tickClock
        val target = clock.ticks + ticks
        waiting { job.waitDelivering({ 5 }) { clock.ticks >= target } }
    }

    private inline fun waiting(block: () -> Unit) {
        try {
            block()
        } catch (_: InterruptedException) {
            throw JobKilledException()
        }
    }

    /**
     * Refuse to wait on the game's thread, where `onHudRender`, `onWorldRender`, the raw input handlers,
     * hooks and Fabric listeners run: the game would freeze until the wait ended (for good, for a tick).
     */
    internal fun checkCanWait(what: String) = job.checkCanWait(what)

    /** End the script now. Works from inside event handlers too. */
    fun stop(): Nothing = throw JobStopException()

    fun isModLoaded(id: String): Boolean = FabricLoader.getInstance().isModLoaded(id)

    fun modVersion(id: String): String? =
        FabricLoader.getInstance().getModContainer(id).map { it.metadata.version.friendlyString }.orElse(null)

    // ---- events -------------------------------------------------------------------------------

    /**
     * Call [handler] every game tick: the client's in a client script, the server's in a server script.
     * Handlers run on the script thread; the script stays alive until `stop()`/`\kill`.
     */
    fun onTick(handler: () -> Unit): Handler {
        job.checkAlive()
        return addHandler(job.tickHandlers, handler)
    }

    /** Stable id of the current world, for `persistent(perWorld = true)`. */
    internal abstract fun worldKey(): String

    /** The time of day (0–23999) for `atDaytime`, or null when there's no world. */
    internal abstract fun currentDayTime(): Long?
}
