package dev.ilgax.minekt.server

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier

/**
 * `minekt:host`: what other server-side mods get through Fabric's object share to list, run, watch and
 * stop this server's scripts without depending on Minekt's classes. Only JDK types cross; the keys and
 * their types are documented in `docs/server.md` and version 1 is frozen (changes only add keys).
 */
internal object HostShare {
    const val KEY = "minekt:host"
    const val VERSION = 1
    const val MAX_LOG_LINES = 200

    /** Names `/mks run` accepts (Brigadier's unquoted word), so no name can reach outside the folder. */
    private val NAME = Regex("[0-9A-Za-z_.+-]+")

    fun create(scriptDir: Path, jobs: JobManager): Map<String, Any> {
        val values = LinkedHashMap<String, Any>()
        values["version"] = Integer.valueOf(VERSION)
        values["scriptDir"] = Supplier<String> { scriptDir.toAbsolutePath().toString() }
        values["scripts"] = Supplier<List<String>> { Collections.unmodifiableList(ArrayList(jobs.availableScripts())) }
        values["run"] = BiFunction<String, List<String>, CompletableFuture<Map<String, Any?>>> { name, args -> run(jobs, name, args) }
        values["jobs"] = Supplier<List<Map<String, Any?>>> { Collections.unmodifiableList(jobs.jobs.map(::describe)) }
        values["kill"] = Function<Int, Boolean> { id -> id != null && jobs.kill(id) }
        values["logTail"] = BiFunction<String, Int, List<String>> { name, lines -> logTail(jobs, name, lines) }
        return Collections.unmodifiableMap(values)
    }

    private fun run(jobs: JobManager, name: String?, args: List<String>?): CompletableFuture<Map<String, Any?>> {
        val job = try {
            // Not a name `/mks run` accepts: nothing to start, and nothing to tell the operators.
            if (name == null || !NAME.matches(name)) return CompletableFuture.completedFuture(result(false, null, "No script named '$name'", null))
            jobs.runScript(name, args?.toList().orEmpty())
                ?: return CompletableFuture.completedFuture(result(false, null, jobs.missingScript(name), null))
        } catch (e: Exception) {
            // The server is stopping (the job manager is closed) or the folder can't be read.
            return CompletableFuture.completedFuture(result(false, null, "Couldn't start $name: ${e.message}", null))
        }
        return job.began.thenApply { error ->
            if (error != null) result(false, job.id, error, null)
            else result(true, job.id, null, job.ended.thenApply { end ->
                unmodifiable("reason" to end.reason, "error" to end.error, "seconds" to end.seconds)
            })
        }
    }

    private fun result(started: Boolean, job: Int?, error: String?, ended: CompletableFuture<Map<String, Any?>>?): Map<String, Any?> {
        val pairs = mutableListOf<Pair<String, Any?>>("started" to started, "job" to job, "error" to error)
        if (ended != null) pairs += "ended" to ended
        return unmodifiable(*pairs.toTypedArray())
    }

    private fun describe(job: Job): Map<String, Any?> = unmodifiable(
        "id" to job.id,
        "name" to job.name,
        "args" to Collections.unmodifiableList(ArrayList(job.args)),
        "startedAt" to job.startedAt,
    )

    private fun logTail(jobs: JobManager, name: String?, lines: Int?): List<String> {
        if (name == null || !NAME.matches(name) || lines == null || lines <= 0) return emptyList()
        return Collections.unmodifiableList(jobs.logs.tail(name, minOf(lines, MAX_LOG_LINES)))
    }

    /** A map that keeps null values (Map.of doesn't) and its order. */
    private fun unmodifiable(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
        Collections.unmodifiableMap(LinkedHashMap<String, Any?>().apply { pairs.forEach { put(it.first, it.second) } })
}
