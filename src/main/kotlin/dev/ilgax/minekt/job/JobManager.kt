
package dev.ilgax.minekt.job

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.host.toScriptSource

class JobManager(private val host: Host) {
    private val nextId = AtomicInteger(1)
    private val running = ConcurrentSkipListMap<Int, Job>()

    /** Backing files for `by persistent` values, in `<scriptDir>/data`. */
    val storage = PersistentStore(host.scriptDir.resolve("data"))

    val jobs: Collection<Job> get() = running.values

    /** Per-script logs, in `<scriptDir>/logs`. */
    val logs = ScriptLogs(host.scriptDir.resolve("logs"))

    /** Settings declared with `by setting(…)`, in `<scriptDir>/settings`. Changes reach the running script. */
    val settings = ScriptSettings(host.scriptDir.resolve("settings")).also { settings ->
        settings.listeners += { script, key, value -> running.values.filter { it.name == script }.forEach { it.post(Job.Event.Setting(key, value)) } }
    }

    /** Messages between scripts (`publish` / `subscribe`). */
    val bus = MessageBus { running.values }

    /** Start `<scriptDir>/<name>.kts`; returns null (after telling the player) if it doesn't exist. */
    fun runScript(name: String, args: List<String>): Job? {
        val file = host.scriptDir.resolve("$name.kts")
        if (!file.isRegularFile()) {
            val guess = closest(name, availableScripts())?.let { " Did you mean \\$it?" }.orEmpty()
            host.echo("No script named '$name' (looked for ${file.fileName} in ${host.scriptDir}).$guess", MessageKind.ERROR)
            return null
        }
        return start(name, args, FileScriptSource(file.toFile()))
    }

    fun eval(code: String): Job = start(Job.EVAL_NAME, emptyList(), code.toScriptSource("eval.kts"))

    /** Samples each job's CPU use every few seconds (see [Job.sampleCpu]). */
    private val cpuMonitor by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "minekt-watchdog").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ running.values.forEach { job -> runCatching(job::sampleCpu) } }, 0, CPU_SAMPLE_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun start(name: String, args: List<String>, source: SourceCode): Job {
        cpuMonitor
        val job = Job(nextId.getAndIncrement(), name, args, source, host, storage, bus, logs, settings, this) { running.remove(it.id) }
        running[job.id] = job
        job.start()
        return job
    }

    fun kill(id: Int): Boolean = running[id]?.also(Job::kill) != null

    fun killAll() = running.values.forEach(Job::kill)

    fun availableScripts(): List<String> =
        if (!Files.isDirectory(host.scriptDir)) emptyList()
        else host.scriptDir.listDirectoryEntries("*.kts").map { it.nameWithoutExtension }.sorted()

    fun dispatch(event: Job.Event) = running.values.forEach { it.post(event) }

    /** Whether any script listens for game events of [type]: check before doing work to make one. */
    fun wantsGame(type: Class<out dev.ilgax.minekt.api.MinektEvent>) = running.values.any { it.wantsGame(type) }

    /** Whether any server script listens for server events of [type]. */
    fun wantsServer(type: Class<out dev.ilgax.minekt.api.server.ServerEvent>) =
        running.values.any { job -> job.serverEventTypes.any { it.isAssignableFrom(type) } }


    /**
     * Game thread, for every key, mouse button, scroll and mouse move: every script's input listeners
     * run in turn until one cancels it. Returns whether it was cancelled.
     */
    fun filterInput(event: dev.ilgax.minekt.api.InputEvent): Boolean {
        for (job in running.values) {
            for (listener in job.inputHandlers) {
                if (!listener.type.isInstance(event)) continue
                try {
                    listener.handler(event)
                } catch (t: Throwable) {
                    job.inputHandlers.remove(listener) // a broken listener must not break input
                    job.handleThrowable(t)
                }
                if (event.isCancelled) return true
            }
        }
        return false
    }

    /** Game thread, every frame: each script's `onHudRender` handlers. A handler that throws is removed. */
    fun renderHud(canvas: dev.ilgax.minekt.api.HudCanvas) {
        for (job in running.values) {
            if (job.hudRenderHandlers.isEmpty()) continue
            canvas.job = job
            for (handler in job.hudRenderHandlers) {
                try {
                    handler(canvas)
                } catch (t: Throwable) {
                    job.hudRenderHandlers.remove(handler)
                    job.handleThrowable(t)
                }
            }
        }
    }

    /** Game thread, every frame: each script's `onWorldRender` handlers. */
    fun renderWorld(canvas: dev.ilgax.minekt.api.WorldCanvas) {
        for (job in running.values) {
            for (handler in job.worldRenderHandlers) {
                try {
                    handler(canvas)
                } catch (t: Throwable) {
                    job.worldRenderHandlers.remove(handler)
                    job.handleThrowable(t)
                }
            }
        }
    }

    fun wantsHudRender() = running.values.any { it.hudRenderHandlers.isNotEmpty() }

    fun wantsWorldRender() = running.values.any { it.worldRenderHandlers.isNotEmpty() }

    /** Whether any script listens for [type] input (the mixins stay idle otherwise). */
    fun wantsInput(type: Class<out dev.ilgax.minekt.api.InputEvent>) = running.values.any { job -> job.inputHandlers.any { it.type == type } }

    /**
     * Network thread, for every packet the client receives ([incoming]) or sends. Every script's
     * packet filters run in turn, then listeners are notified. Returns the packet to use (possibly
     * replaced) or null to drop it.
     */
    fun filterPacket(packet: net.minecraft.network.protocol.Packet<*>, incoming: Boolean): net.minecraft.network.protocol.Packet<*>? {
        var current = packet
        for (job in running.values) {
            for (filter in job.packetFilters) {
                if (!filter.matches(current, incoming)) continue
                val result = try {
                    filter.handler(current)
                } catch (t: Throwable) {
                    job.packetFilters.remove(filter) // a broken filter must not break the connection
                    job.handleThrowable(t)
                    continue
                }
                current = result as? net.minecraft.network.protocol.Packet<*> ?: return null
            }
        }
        val event = Job.Event.Packet(current, incoming)
        for (job in running.values) job.post(event) // cheap when nobody listens; also wakes awaitPacket
        return current
    }

    internal companion object {
        const val CPU_SAMPLE_SECONDS = 5L

        /** The name in [names] nearest to [name] by edit distance, if it's a plausible typo. */
        fun closest(name: String, names: List<String>): String? {
            val (best, distance) = names.map { it to editDistance(name.lowercase(), it.lowercase()) }.minByOrNull { it.second } ?: return null
            return best.takeIf { distance <= maxOf(1, name.length / 3) }
        }

        private fun editDistance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
