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

    /** Messages between scripts (`publish` / `subscribe`). */
    val bus = MessageBus { running.values }

    /** Start `<scriptDir>/<name>.kts`; returns null (after telling the player) if it doesn't exist. */
    fun runScript(name: String, args: List<String>): Job? {
        val file = host.scriptDir.resolve("$name.kts")
        if (!file.isRegularFile()) {
            host.echo("No script named '$name' (looked for ${file.fileName} in ${host.scriptDir})", MessageKind.ERROR)
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
        val job = Job(nextId.getAndIncrement(), name, args, source, host, storage, bus) { running.remove(it.id) }
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

    fun pollKeys(isDown: (Int) -> Boolean) = running.values.forEach { it.pollKeys(isDown) }

    /**
     * Client thread, synchronously, for each chat line received. Every script's interceptors run in
     * turn; returns the (possibly rewritten) text, or null if one of them hid it.
     */
    fun interceptIncoming(text: String): String? = intercept(text, Job::incomingInterceptors)

    /** Same for chat the player sends (not commands). */
    fun interceptOutgoing(text: String): String? = intercept(text, Job::outgoingInterceptors)

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

    private fun intercept(text: String, select: (Job) -> MutableList<(String) -> String?>): String? {
        var current = text
        for (job in running.values) {
            val interceptors = select(job)
            for (interceptor in interceptors) {
                val result = try {
                    interceptor(current)
                } catch (t: Throwable) {
                    interceptors.remove(interceptor) // a broken interceptor must not break chat
                    job.handleThrowable(t)
                    continue
                }
                current = result ?: return null
            }
        }
        return current
    }

    private companion object {
        const val CPU_SAMPLE_SECONDS = 5L
    }
}
