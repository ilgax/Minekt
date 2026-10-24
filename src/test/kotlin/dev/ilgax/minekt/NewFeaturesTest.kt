package dev.ilgax.minekt

import com.sun.net.httpserver.HttpServer
import net.minecraft.core.BlockPos
import dev.ilgax.minekt.api.MinektEvent
import dev.ilgax.minekt.job.Autorun
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.ScriptSettings
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Settings, autorun sections, HTTP and per-type game event listening. */
class NewFeaturesTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    private fun run(name: String, code: String): Job {
        host.scriptDir.resolve("$name.kts").writeText(code)
        return assertNotNull(jobs.runScript(name, emptyList()))
    }

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} did not finish")
            Thread.sleep(10)
        }
    }

    private fun awaitListening(job: Job) {
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.LISTENING) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} is not listening")
            Thread.sleep(10)
        }
    }

    // ---- settings ---------------------------------------------------------------------------------

    @Test
    fun `settings start at their default, keep changes, and reach the running script`() {
        val code = """
            var range by setting(16, 1..64)
            var autoEat by setting(true)
            var mode by setting("walk", listOf("walk", "sprint"))
            var speed by setting(1.0, 0.5..3.0, step = 0.25, label = "Walk speed")
            echo(range, autoEat, mode, speed)
            onSettingChange { (key, value) -> echo("changed", key, value, range); stop() }
        """.trimIndent()
        val first = run("miner", code)
        assertEquals("16 true walk 1.0", host.next())
        awaitListening(first)
        val list = jobs.settings.list("miner")
        assertEquals(listOf("Range", "Auto eat", "Mode", "Walk speed"), list.map { it.label })
        assertEquals(64, jobs.settings.set("miner", "range", 99)) // clamped into the range
        assertEquals("changed range 64 64", host.next())
        awaitDone(first)

        jobs.settings.set("miner", "mode", "sprint")
        assertFailsWith<IllegalArgumentException> { jobs.settings.set("miner", "mode", "fly") }
        val again = run("miner", code)
        assertEquals("64 true sprint 1.0", host.next())
        jobs.kill(again.id)
        awaitDone(again)
        // The file is readable JSON, and a fresh store reads the same values back.
        val file = jobs.settings.path("miner").readText()
        assertTrue("\"value\": 64" in file && "\"label\": \"Walk speed\"" in file, file)
        assertEquals(64, ScriptSettings(host.scriptDir.resolve("settings")).declare("miner", "range", "Range", ScriptSettings.Kind.Whole(1, 64), 16))
    }

    @Test
    fun `settings a script stopped declaring are dropped, and reset restores defaults`() {
        awaitDone(run("tidy", "val a by setting(1, 0..9)\nval b by setting(\"x\")\necho(a)"))
        assertEquals("1", host.next())
        jobs.settings.set("tidy", "a", 5)
        awaitDone(run("tidy", "val a by setting(1, 0..9)\necho(a)"))
        assertEquals("5", host.next())
        assertEquals(listOf("a"), jobs.settings.list("tidy").map { it.key })
        jobs.settings.reset("tidy")
        assertEquals(1, jobs.settings.get("tidy", "a"))
    }

    @Test
    fun `a value edited by hand that no longer fits falls back to the default`() {
        val dir = Files.createTempDirectory("settings")
        dir.resolve("s.json").writeText("""{ "n": { "label": "N", "type": "whole", "min": 0, "max": 9, "default": 3, "value": "lots" } }""")
        assertEquals(3, ScriptSettings(dir).declare("s", "n", "N", ScriptSettings.Kind.Whole(0, 9), 3))
    }

    // ---- autorun ----------------------------------------------------------------------------------

    @Test
    fun `autorun sections pick commands by server, world and dimension`() {
        val autorun = Autorun(
            """
            \hello            # everywhere
            [server *.example.net]
            \miner
            [world Creative*]
            \builder
            [dimension the_nether]
            \nightvision
            [multiplayer, dimension minecraft:the_end]
            \endtools
            [singleplayer]
            \cheats
            [always]
            \stats
            [planet mars]
            \never
            """.trimIndent(),
        )
        val server = Autorun.Place("play.example.net:25565", null, "minecraft:overworld")
        assertEquals(listOf("\\hello", "\\miner", "\\stats"), autorun.onJoin(server))
        assertEquals(listOf("\\hello", "\\builder", "\\cheats", "\\stats"), autorun.onJoin(Autorun.Place(null, "creative TEST", "minecraft:overworld")))
        val nether = Autorun.Place("play.example.net", null, "minecraft:the_nether")
        assertEquals(listOf("\\hello", "\\miner", "\\nightvision", "\\stats"), autorun.onJoin(nether))
        assertEquals(listOf("\\nightvision"), autorun.onDimensionChange(nether))
        assertEquals(listOf("\\endtools"), autorun.onDimensionChange(server.copy(dimension = "minecraft:the_end")))
        assertEquals(emptyList(), autorun.onDimensionChange(Autorun.Place(null, "w", "minecraft:the_end")))
        assertEquals(1, autorun.problems.size)
        assertTrue("line 14" in autorun.problems.single(), autorun.problems.toString())
    }

    // ---- http -------------------------------------------------------------------------------------

    @Test
    fun `http get, post as JSON, and error statuses`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val request = exchange.requestBody.readAllBytes().decodeToString()
            val (status, body) = when (exchange.requestURI.path) {
                "/data" -> 200 to """{"name":"Steve","levels":[1,2,3]}"""
                "/echo" -> 201 to "${exchange.requestMethod} ${exchange.requestHeaders.getFirst("Content-Type")} ${exchange.requestHeaders.getFirst("X-Test")} $request"
                else -> 404 to "nope"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            awaitDone(
                run(
                    "web",
                    """
                    data class Data(val name: String, val levels: List<Int>)
                    val data = http("GET", "$base/data").json<Data>()
                    echo(data.name, data.levels.sum())
                    echo(http("POST", "$base/echo", mapOf("content" to "hi"), headers = mapOf("X-Test" to "yes")).body)
                    echo(http("POST", "$base/echo", "plain").body)
                    val missing = http("GET", "$base/missing")
                    echo(missing.status, missing.ok, runCatching { missing.orThrow() }.exceptionOrNull()?.message)
                    echo(runCatching { http("GET", "ftp://x") }.exceptionOrNull()?.message)
                    """.trimIndent(),
                ),
            )
            assertEquals("Steve 6", host.next())
            assertEquals("POST application/json yes {\"content\":\"hi\"}", host.next())
            assertEquals("POST text/plain; charset=utf-8 null plain", host.next())
            assertEquals("404 false HTTP 404: nope", host.next())
            assertEquals("Not a web address: ftp://x", host.next())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `kill stops a request that is waiting for an answer`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { Thread.sleep(30_000) }
        server.executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.start()
        try {
            val job = run("slow", "echo(\"asking\")\nhttp(\"GET\", \"http://127.0.0.1:${server.address.port}/\")\necho(\"answered\")")
            assertEquals("asking", host.next())
            Thread.sleep(300)
            val started = System.currentTimeMillis()
            jobs.kill(job.id)
            awaitDone(job)
            assertTrue(System.currentTimeMillis() - started < 5_000)
        } finally {
            server.stop(0)
        }
    }

    // ---- events -----------------------------------------------------------------------------------

    @Test
    fun `scripts only receive the event types they listen for`() {
        val job = run(
            "events",
            """
            onBlockChange { (pos, old, new) -> echo("block", pos, old, new) }
            onPlayerJoin { echo("join", it); stop() }
            """.trimIndent(),
        )
        awaitListening(job)
        assertTrue(jobs.wantsGame(MinektEvent.BlockChange::class.java))
        assertTrue(jobs.wantsGame(MinektEvent.PlayerJoin::class.java))
        assertFalse(jobs.wantsGame(MinektEvent.Sound::class.java))
        assertFalse(jobs.wantsGame(MinektEvent.EntityAppear::class.java))
        jobs.dispatch(Job.Event.Game(MinektEvent.Sound("minecraft:ui.button.click", net.minecraft.world.phys.Vec3(0.0, 0.0, 0.0), "ui")))
        jobs.dispatch(Job.Event.Game(MinektEvent.BlockChange(BlockPos(1, 2, 3), "minecraft:stone", "minecraft:air")))
        assertEquals("block (1, 2, 3) minecraft:stone minecraft:air", host.next())
        jobs.dispatch(Job.Event.Game(MinektEvent.PlayerJoin("Alex")))
        assertEquals("join Alex", host.next())
        awaitDone(job)
        while (job in jobs.jobs) Thread.sleep(10) // removed right after it's marked done
        assertFalse(jobs.wantsGame(MinektEvent.BlockChange::class.java))
    }
}
