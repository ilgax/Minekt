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

        val commands = java.util.concurrent.ConcurrentHashMap<String, Job>()
        override fun registerCommand(name: String, completions: List<String>, owner: Job): String? {
            val existing = commands.putIfAbsent(name, owner)
            return if (existing != null && existing !== owner) "/$name is taken" else null
        }
        override fun unregisterCommand(name: String, owner: Job) { commands.remove(name, owner) }
        override fun bindingKeyName(slot: Int) = "Key$slot"

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
    fun `script commands receive args and are unregistered when the script ends`() {
        val job = jobs.eval("""command("greet", listOf("world")) { args -> if (args == listOf("bye")) stop() else echo("hello", args) }""")
        while (job.state != Job.State.LISTENING) Thread.sleep(20)
        assertEquals(job, host.commands["greet"])
        jobs.dispatch(Job.Event.Command("greet", listOf("big", "world")))
        assertEquals(MessageKind.SCRIPT to "hello [big, world]", host.next())
        jobs.dispatch(Job.Event.Command("greet", listOf("bye")))
        awaitDone(job)
        assertTrue(host.commands.isEmpty(), "command should be unregistered: ${host.commands}")
    }

    @Test
    fun `registering a taken command name fails the script`() {
        val owner = jobs.eval("""command("dup") { }""")
        while (owner.state != Job.State.LISTENING) Thread.sleep(20)
        awaitDone(jobs.eval("""command("dup") { }"""))
        val (kind, text) = host.next()
        assertEquals(MessageKind.ERROR, kind)
        assertTrue("/dup is taken" in text, text)
        jobs.kill(owner.id)
        awaitDone(owner)
    }

    @Test
    fun `keybind slots dispatch to their handlers only`() {
        val job = jobs.eval("""echo(bindingKey(2)); onBinding(2) { echo("slot 2"); stop() }; onBinding(3) { echo("slot 3") }""")
        assertEquals(MessageKind.SCRIPT to "Key2", host.next())
        while (job.state != Job.State.LISTENING) Thread.sleep(20)
        jobs.dispatch(Job.Event.Binding(1))
        jobs.dispatch(Job.Event.Binding(2))
        assertEquals(MessageKind.SCRIPT to "slot 2", host.next())
        awaitDone(job)
        assertTrue(host.messages.isEmpty(), "unexpected output: ${host.messages}")
    }

    @Test
    fun `invalid keybind slot is rejected`() {
        awaitDone(jobs.eval("onBinding(9) { }"))
        assertTrue("1..5" in host.next().second)
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
    fun `bundled examples compile`() {
        val dir = Path.of(javaClass.getResource("/minekt-examples/hello.kts")!!.toURI()).parent
        val failures = Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".kts") }.toList().mapNotNull { file ->
                val result = dev.ilgax.minekt.script.ScriptCompiler.compile(kotlin.script.experimental.host.FileScriptSource(file.toFile()))
                if (result is kotlin.script.experimental.api.ResultWithDiagnostics.Failure) "${file.fileName}: ${result.reports.map { it.message }}" else null
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun `quoted args are grouped`() {
        assertEquals(listOf("say", "hello world", "x"), splitArgs("say \"hello world\" x"))
    }
}
