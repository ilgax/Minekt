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
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** `by persistent { }`: values survive script runs and "game restarts" (a fresh JobManager on the same folder). */
class PersistenceTest {
    private class FakeHost(override val scriptDir: Path) : Host {
        val messages = LinkedBlockingQueue<Pair<MessageKind, String>>()
        override fun echo(message: String, kind: MessageKind) { messages.put(kind to message) }
        override fun <T> onGameThread(block: (Minecraft) -> T): T = error("no game in tests")
        override fun registerCommand(name: String, completions: List<String>, owner: Job): String? = null
        override fun unregisterCommand(name: String, owner: Job) {}
        override fun addKeybind(owner: Job, id: String, label: String, defaultKey: String?): dev.ilgax.minekt.job.KeybindHandle =
        object : dev.ilgax.minekt.job.KeybindHandle {
            override val keyName = defaultKey?.substringAfterLast('.')?.uppercase() ?: "Not Bound"
            override val isDown = false
            override fun remove() = Unit
        }
        fun next(): Pair<MessageKind, String> = messages.poll(60, TimeUnit.SECONDS) ?: fail("timed out waiting for script output")
    }

    private val dir: Path = Files.createTempDirectory("minekt-persist")
    private val host = FakeHost(dir)

    private fun run(name: String, code: String, jobs: JobManager = JobManager(host)): List<String> {
        dir.resolve("$name.kts").writeText(code)
        val job = jobs.runScript(name, emptyList()) ?: fail("no script")
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("script $name did not finish")
            Thread.sleep(20)
        }
        return generateSequence { host.messages.poll() }.map { it.second }.toList()
    }

    @Test
    fun `a var survives runs and restarts`() {
        val code = "var runs by persistent { 0 }\nruns++\necho(runs)"
        assertEquals(listOf("1"), run("counter", code))
        assertEquals(listOf("2"), run("counter", code))
        assertEquals(listOf("3"), run("counter", code, JobManager(host))) // "restart": new manager, same files
        assertTrue("\"runs\": 3" in dir.resolve("data/counter.json").readText())
    }

    @Test
    fun `in-place changes are saved and values come back with their real types`() {
        run("homes", """val homes by persistent { mutableMapOf<String, Vec3>() }
            |homes["base"] = Vec3(1.5, 64.0, -3.0)""".trimMargin())
        // Typed access (.x on a Vec3) proves it was decoded as Vec3, not as a generic JSON map.
        assertEquals(listOf("1.5 3"), run("homes", """val homes by persistent { mutableMapOf<String, Vec3>() }
            |echo(homes.getValue("base").x, homes.getValue("base").blockPos.z + 6)""".trimMargin(), JobManager(host)))
    }

    @Test
    fun `script-defined data classes round-trip`() {
        val code = """data class Waypoint(val name: String, val pos: BlockPos, val tags: List<String>)
            |val points by persistent { mutableListOf<Waypoint>() }
            |echo(points.joinToString { it.name + "(" + it.pos.toShortString() + ")" + it.tags })
            |points += Waypoint("mine", BlockPos(1, -40, 2), listOf("diamonds"))""".trimMargin()
        assertEquals(listOf(""), run("points", code))
        assertEquals(listOf("mine(1, -40, 2)[diamonds]"), run("points", code, JobManager(host)))
    }

    @Test
    fun `scripts share values through a named store`() {
        run("writer", """var motd by persistent(store = "shared") { "" }
            |motd = "hello from writer"""".trimMargin())
        assertEquals(listOf("hello from writer"), run("reader", """val motd by persistent(store = "shared") { "unset" }
            |echo(motd)""".trimMargin()))
        assertTrue(dir.resolve("data/shared.json").exists())
    }

    @Test
    fun `changed types and broken files fall back to the default`() {
        run("typed", "var v by persistent { 5 }; v = 6")
        val out = run("typed", "val v by persistent { mutableListOf<String>() }; echo(v)", JobManager(host))
        assertTrue(out.first().contains("doesn't fit its type"), out.toString())
        assertEquals("[]", out.last())

        Files.createDirectories(dir.resolve("data"))
        dir.resolve("data/broken.json").writeText("{ not json")
        assertEquals(listOf("fresh"), run("broken", "val v by persistent { \"fresh\" }; echo(v)"))
        assertTrue(dir.resolve("data/broken.json.broken").exists(), "broken file should be kept aside")
    }

    @Test
    fun `values are autosaved while the script runs and saved when it is killed`() {
        val jobs = JobManager(host)
        dir.resolve("live.kts").writeText("val log by persistent { mutableListOf<String>() }\nlog += \"started\"\nonChat { log += it.text }")
        val job = jobs.runScript("live", emptyList())!!
        while (job.state != Job.State.LISTENING) Thread.sleep(20)
        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("first")))
        val file = dir.resolve("data/live.json")
        val deadline = System.currentTimeMillis() + 15_000
        while (!(file.exists() && "first" in file.readText())) {
            if (System.currentTimeMillis() > deadline) fail("not autosaved while running")
            Thread.sleep(200)
        }
        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("second")))
        Thread.sleep(200)
        jobs.kill(job.id)
        while (job.state != Job.State.DONE) Thread.sleep(20)
        assertTrue("second" in file.readText(), file.readText())
    }
}
