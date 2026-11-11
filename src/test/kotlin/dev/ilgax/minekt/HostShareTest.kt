package dev.ilgax.minekt

import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.api.server.ServerMinekt
import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.TickClock
import dev.ilgax.minekt.script.ServerScriptCompiler
import dev.ilgax.minekt.script.TemplateCompiler
import dev.ilgax.minekt.server.HostShare
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Supplier
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `minekt:host`, used the way another mod would: only through JDK types. */
class HostShareTest {
    private class FakeServerHost : Host {
        override val scriptDir: Path = Files.createTempDirectory("minekt-host-test")
        override val tickClock = TickClock()
        override val compiler: TemplateCompiler get() = ServerScriptCompiler
        override val commandPrefix get() = "/mks "
        override fun receiver(job: Job): ScriptContext = ServerMinekt(job)
        val messages = LinkedBlockingQueue<String>()
        override fun echo(message: String, kind: MessageKind) { messages.put(message) }
        override fun registerCommand(name: String, completions: List<String>, owner: Job): String? = null
        override fun unregisterCommand(name: String, owner: Job) = Unit
        fun next(): String = messages.poll(60, TimeUnit.SECONDS) ?: fail("timed out waiting for script output")
    }

    private val host = FakeServerHost()
    private val jobs = JobManager(host)

    /** What another mod sees: a plain `Map<String, Object>`. */
    private val share: Map<String, Any?> = HostShare.create(host.scriptDir, jobs)

    @Suppress("UNCHECKED_CAST")
    private fun <T> key(name: String): T = share[name] as T

    private fun run(name: String, args: List<String> = emptyList()): Map<String, Any?> =
        key<BiFunction<String, List<String>, CompletableFuture<Map<String, Any?>>>>("run").apply(name, args).get(60, TimeUnit.SECONDS)

    @Suppress("UNCHECKED_CAST")
    private fun ended(result: Map<String, Any?>): Map<String, Any?> =
        (result["ended"] as CompletableFuture<Map<String, Any?>>).get(60, TimeUnit.SECONDS)

    private fun logTail(name: String, lines: Int) = key<BiFunction<String, Int, List<String>>>("logTail").apply(name, lines)

    private fun write(name: String, code: String) = host.scriptDir.resolve("$name.kts").writeText(code)

    @Test
    fun `the shared map has version 1 and only JDK types`() {
        assertEquals(1, share["version"])
        assertEquals(setOf("version", "scriptDir", "scripts", "run", "jobs", "kill", "logTail"), share.keys)
        assertEquals(host.scriptDir.toAbsolutePath().toString(), key<Supplier<String>>("scriptDir").get())
        write("b", "")
        write("a", "")
        assertEquals(listOf("a", "b"), key<Supplier<List<String>>>("scripts").get())
        for (value in share.values) {
            assertTrue(value is Int || value!!.javaClass.interfaces.all { it.name.startsWith("java.") }, "$value")
        }
    }

    @Test
    fun `a good script starts, then ends finished, and its log can be read`() {
        write("good", """echo("hello")""")
        val result = run("good")
        assertEquals(true, result["started"], result.toString())
        assertNotNull(result["job"] as Int?)
        assertNull(result["error"])
        val end = ended(result)
        assertEquals("finished", end["reason"])
        assertNull(end["error"])
        assertTrue((end["seconds"] as Double) >= 0.0)
        assertEquals("hello", host.next())
        jobs.logs.flush()
        val tail = logTail("good", 500)
        assertTrue(tail.any { it.endsWith("ECHO  #${result["job"]} hello") }, tail.toString())
        assertTrue(tail.last().contains("Finished after"), tail.toString())
        assertEquals(1, logTail("good", 1).size)
        assertEquals(emptyList(), logTail("never", 10))
        assertEquals(emptyList(), logTail("../good", 10))
    }

    @Test
    fun `a missing script does not start and says why`() {
        write("present", "")
        val result = run("presnt")
        assertEquals(false, result["started"])
        assertNull(result["job"])
        val error = result["error"] as String
        assertTrue(error.startsWith("No script named 'presnt'"), error)
        assertFalse("ended" in result)
        assertEquals(error, host.next()) // the same text the operators see
        val outside = run("../present")
        assertEquals(false, outside["started"])
        assertTrue((outside["error"] as String).startsWith("No script named"), outside.toString())
    }

    @Test
    fun `a compile error is reported with its line`() {
        write("broken", "val x = 1\nval y: Int = \"text\"")
        val result = run("broken")
        assertEquals(false, result["started"])
        assertNotNull(result["job"])
        val error = result["error"] as String
        assertTrue(error.startsWith("[broken:2:"), error)
        assertFalse("ended" in result)
    }

    @Test
    fun `kill ends a running job as killed and it leaves the job list`() {
        write("waiter", "onEvent<dev.ilgax.minekt.api.server.ServerEvent> { }")
        val result = run("waiter", listOf("x"))
        assertEquals(true, result["started"], result.toString())
        val id = result["job"] as Int
        val listed = key<Supplier<List<Map<String, Any?>>>>("jobs").get().single()
        assertEquals(id, listed["id"])
        assertEquals("waiter", listed["name"])
        assertEquals(listOf("x"), listed["args"])
        assertTrue(listed["startedAt"] as Long > 0)
        val kill = key<Function<Int, Boolean>>("kill")
        assertTrue(kill.apply(id))
        assertEquals("killed", ended(result)["reason"])
        assertTrue(key<Supplier<List<Map<String, Any?>>>>("jobs").get().isEmpty())
        assertFalse(kill.apply(id))
        assertFalse(kill.apply(12345))
    }

    @Test
    fun `stopping the host ends jobs with server_stopping`() {
        write("listener", "onEvent<dev.ilgax.minekt.api.server.ServerEvent> { }")
        val result = run("listener")
        assertEquals(true, result["started"], result.toString())
        jobs.close()
        assertEquals("server_stopping", ended(result)["reason"])
        assertEquals(false, run("listener")["started"])
    }

    @Test
    fun `stop and runtime errors are told apart`() {
        write("stopper", "stop()")
        assertEquals("stopped", ended(run("stopper"))["reason"])
        write("thrower", "error(\"boom\")")
        val end = ended(run("thrower"))
        assertEquals("error", end["reason"])
        assertTrue((end["error"] as String).contains("boom"), end.toString())
    }

    @Test
    fun `args are passed through exactly as given`() {
        write("echoargs", """echo(args.joinToString("|"))""")
        val args = listOf("with space", "\"quoted\"", "", "back\\slash", "tab\tafter")
        val result = run("echoargs", args)
        assertEquals(true, result["started"], result.toString())
        assertEquals("finished", ended(result)["reason"])
        assertEquals(args.joinToString("|"), host.next())
    }
}
