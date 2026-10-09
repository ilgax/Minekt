package dev.ilgax.minekt.job

import java.nio.file.Files
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

    fun start(name: String, args: List<String>, source: SourceCode): Job {
        val job = Job(nextId.getAndIncrement(), name, args, source, host, storage) { running.remove(it.id) }
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
}
