package dev.ilgax.minekt

import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.MessageKind
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Exercises compile → run → events → kill without a running game, via a fake [Host]. */
class ScriptEngineTest {
    private class FakeHost : Host {
        override val scriptDir: Path = Files.createTempDirectory("minekt-test")
        val messages = LinkedBlockingQueue<Pair<MessageKind, String>>()
        override fun echo(message: String, kind: MessageKind) { messages.put(kind to message) }
        override fun <T> onGameThread(block: (Minecraft) -> T): T = error("no game in tests")

        fun next(): Pair<MessageKind, String> =
            messages.poll(60, TimeUnit.SECONDS) ?: fail("timed out waiting for script output")
    }

    private val host = FakeHost()
    private val jobs = JobManager(host)

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} did not finish")
            Thread.sleep(20)
        }
    }

    @Test
    fun `script runs with args and api in scope`() {
        host.scriptDir.resolve("greet.kts").writeText("""echo("hi", args.joinToString("+"), max(2, 7))""")
        val job = assertNotNull(jobs.runScript("greet", listOf("a", "b")))
        assertEquals(MessageKind.SCRIPT to "hi a+b 7", host.next())
        awaitDone(job)
    }

    @Test
    fun `eval echoes its result`() {
        awaitDone(jobs.eval("(1..4).sum()"))
        assertEquals(MessageKind.INFO to "= 10", host.next())
    }

    @Test
    fun `compile errors are reported with location`() {
        awaitDone(jobs.eval("val x: Int = \"nope\""))
        val (kind, text) = host.next()
        assertEquals(MessageKind.ERROR, kind)
        assertTrue(text.startsWith("[eval:1:"), text)
    }

    @Test
    fun `runtime errors are reported`() {
        awaitDone(jobs.eval("error(\"boom\")"))
        val (kind, text) = host.next()
        assertEquals(MessageKind.ERROR, kind)
        assertTrue("boom" in text, text)
    }

    @Test
    fun `file imports are resolved relative to the script`() {
        Files.createDirectories(host.scriptDir.resolve("lib"))
        host.scriptDir.resolve("lib/util.kts").writeText("fun shout(s: String) = s.uppercase() + \"!\"")
        host.scriptDir.resolve("main.kts").writeText("@file:Import(\"lib/util.kts\")\n\necho(shout(\"hey\"))")
        awaitDone(jobs.runScript("main", emptyList())!!)
        assertEquals(MessageKind.SCRIPT to "HEY!", host.next())
    }

    @Test
    fun `imported declarations are visible inside lambdas and use api types`() {
        Files.createDirectories(host.scriptDir.resolve("lib"))
        host.scriptDir.resolve("lib/fmt.kts").writeText("fun fmt(v: Vec3) = \"%.1f\".format(v.x)")
        host.scriptDir.resolve("uses.kts").writeText(
            "@file:Import(\"lib/fmt.kts\")\n\nfun check(block: () -> Any?) = echo(block())\ncheck { fmt(Vec3(1.0, 2.0, 3.0)) }"
        )
        awaitDone(jobs.runScript("uses", emptyList())!!)
        assertEquals(MessageKind.SCRIPT to "1.0", host.next())
    }

    @Test
    fun `chat handlers keep the job alive until stop`() {
        val job = jobs.eval("""onChat { if (it == "quit") stop() else echo("got " + it) }""")
        while (job.state != Job.State.LISTENING) Thread.sleep(20)
        jobs.dispatch(Job.Event.Chat("ping"))
        assertEquals(MessageKind.SCRIPT to "got ping", host.next())
        jobs.dispatch(Job.Event.Chat("quit"))
        awaitDone(job)
    }

    @Test
    fun `looping script can be killed`() {
        val job = jobs.eval("while (true) sleep(10)")
        while (job.state != Job.State.RUNNING) Thread.sleep(20)
        assertTrue(jobs.kill(job.id))
        awaitDone(job)
        assertTrue(host.messages.isEmpty(), "kill should be silent: ${host.messages}")
        assertTrue(jobs.jobs.isEmpty())
    }

    @Test
    fun `quoted args are grouped`() {
        assertEquals(listOf("say", "hello world", "x"), splitArgs("say \"hello world\" x"))
    }
}
