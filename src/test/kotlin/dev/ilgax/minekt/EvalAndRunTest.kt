package dev.ilgax.minekt

import dev.ilgax.minekt.job.Autorun
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** eval, runScript / startScript, and autorun's [startup] section. */
class EvalAndRunTest {
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

    @Test
    fun `eval returns values, sees variables and the API, and reports errors`() {
        awaitDone(
            run(
                "evals",
                """
                echo(eval("(1..10).sum()"))
                echo(eval("greeting.uppercase() + count * 2", "greeting" to "hi", "count" to 21))
                echo(eval("items.size + scores[\"a\"] as Int", "items" to listOf(1, 2, 3), "scores" to mapOf("a" to 4)))
                echo(eval("pos.x + 1", "pos" to Vec3(1.0, 2.0, 3.0)))
                echo(runCatching { eval("val a = 1\nval b = a +", "unused" to 0) }.exceptionOrNull()?.message)
                echo(eval("val built = \"echo(\\\"generated \" + 6 * 7 + \"\\\")\"; eval(built)"))
                echo(runCatching { eval("nope + 1") }.exceptionOrNull()?.message)
                echo(runCatching { eval("error(\"inside\")") }.exceptionOrNull()?.message)
                echo(eval("val x = 5"))
                """.trimIndent(),
            ),
        )
        assertEquals("55", host.next())
        assertEquals("HI42", host.next())
        assertEquals("7", host.next())
        assertEquals("2.0", host.next())
        val syntax = host.next()
        assertTrue(syntax.startsWith("eval didn't compile: line 2:12 "), syntax) // the user's line, not shifted by the added declarations
        assertEquals("generated 42", host.next())
        assertEquals("kotlin.Unit", host.next())
        val compileError = host.next()
        assertTrue(compileError.startsWith("eval didn't compile: line 1:1 Unresolved reference 'nope'"), compileError)
        assertEquals("inside", host.next())
        assertEquals("kotlin.Unit", host.next())
    }

    @Test
    fun `handlers registered by eval belong to the calling script`() {
        val job = run("evalhandler", """eval("onChat { if (it.text == \"bye\") stop() else echo(\"heard \" + it.text) }")""")
        while (job.state != Job.State.LISTENING) Thread.sleep(20)
        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("hello")))
        assertEquals("heard hello", host.next())
        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("bye")))
        awaitDone(job)
    }

    @Test
    fun `runScript returns another script's result and startScript runs it in parallel`() {
        host.scriptDir.resolve("double.kts").writeText("args.first().toInt() * 2")
        host.scriptDir.resolve("broken.kts").writeText("error(\"no fuel\")")
        host.scriptDir.resolve("forever.kts").writeText("onChat { }\nUnit")
        awaitDone(
            run(
                "caller",
                """
                echo(runScript("double", "21"))
                echo(runCatching { runScript("broken") }.exceptionOrNull()?.message)
                echo(runCatching { runScript("missing") }.exceptionOrNull()?.message)
                val bg = startScript("forever")
                echo(bg.name, bg.await(), bg.isRunning)   // its body is done, its handler keeps it running
                bg.kill()
                while (bg.isRunning) sleep(10)
                echo("killed", bg.isRunning)
                echo(runCatching { startScript("forever").also { it.kill() }.await() }.exceptionOrNull()?.message)
                """.trimIndent(),
            ),
        )
        // The failing child also reports its own error, at its own pace: check the caller's lines in order.
        val lines = generateSequence { host.messages.poll(30, java.util.concurrent.TimeUnit.SECONDS) }.take(7).toList()
        assertTrue("[broken] no fuel (line 1)" in lines, lines.toString())
        assertEquals(
            listOf(
                "42",
                "broken failed: no fuel",
                "No script named 'missing'",
                "forever kotlin.Unit true",
                "killed false",
                "forever was killed before it finished",
            ),
            lines.filterNot { it.startsWith("[broken]") },
        )
    }

    @Test
    fun `autorun startup section runs only at startup`() {
        val autorun = Autorun(
            """
            \everywhere
            [startup]
            \patches
            \more
            [startup, server x]
            \never
            [singleplayer]
            \solo
            """.trimIndent(),
        )
        assertEquals(listOf("\\patches", "\\more"), autorun.onStartup())
        val place = Autorun.Place(null, "w", "minecraft:overworld")
        assertEquals(listOf("\\everywhere", "\\solo"), autorun.onJoin(place))
        assertEquals(emptyList(), autorun.onDimensionChange(place))
    }
}
