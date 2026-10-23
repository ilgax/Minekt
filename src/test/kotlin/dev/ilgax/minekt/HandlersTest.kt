package dev.ilgax.minekt

import dev.ilgax.minekt.api.ChatMessage
import dev.ilgax.minekt.api.MinektEvent
import net.minecraft.core.BlockPos
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** Handlers return a Handler that removes just them; handlers with several values get one event object. */
class HandlersTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    private fun run(name: String, code: String): Job {
        host.scriptDir.resolve("$name.kts").writeText(code)
        return assertNotNull(jobs.runScript(name, emptyList()))
    }

    private fun awaitState(job: Job, state: Job.State) {
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != state) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} did not reach $state")
            Thread.sleep(10)
        }
    }

    @Test
    fun `remove takes out one handler, and the script ends when none are left`() {
        val job = run(
            "removing",
            """
            val loud = onChat { echo("loud " + it.text) }
            onChat { (text) -> if (text == "quiet") loud.remove() else if (text == "last") stop() else echo("calm " + text) }
            """.trimIndent(),
        )
        awaitState(job, Job.State.LISTENING)
        jobs.dispatch(Job.Event.Chat(ChatMessage("a")))
        assertEquals(setOf("loud a", "calm a"), setOf(host.next(), host.next()))
        jobs.dispatch(Job.Event.Chat(ChatMessage("quiet")))
        assertEquals("loud quiet", host.next()) // the first handler still saw this one
        jobs.dispatch(Job.Event.Chat(ChatMessage("b")))
        assertEquals("calm b", host.next())

        val single = run("single", """val h = onTick { }; h.remove(); h.remove(); echo("removed")""")
        assertEquals("removed", host.next())
        awaitState(single, Job.State.DONE) // nothing left to keep it running
        jobs.dispatch(Job.Event.Chat(ChatMessage("last")))
        awaitState(job, Job.State.DONE)
    }

    @Test
    fun `handlers with several values unpack the event object`() {
        val job = run(
            "unpack",
            """
            onChat { (text, sender) -> echo("chat " + text + " from " + sender) }
            onBlockChange { (pos, old, new) -> echo("block", pos, old, "->", new) } // echo shows positions as (x, y, z)
            onDamage { e -> echo("damage " + e.amount + " " + e.cause); stop() }
            """.trimIndent(),
        )
        awaitState(job, Job.State.LISTENING)
        jobs.dispatch(Job.Event.Chat(ChatMessage("hi", "Alex")))
        assertEquals("chat hi from Alex", host.next())
        jobs.dispatch(Job.Event.Game(MinektEvent.BlockChange(BlockPos(1, 2, 3), "minecraft:air", "minecraft:stone")))
        assertEquals("block (1, 2, 3) minecraft:air -> minecraft:stone", host.next())
        jobs.dispatch(Job.Event.Game(MinektEvent.Damage(2f, 18f, "minecraft:fall", null, null)))
        assertEquals("damage 2.0 minecraft:fall", host.next())
        awaitState(job, Job.State.DONE)
    }

    @Test
    fun `handlers run while the body sleeps, so a loop sees what they change`() {
        val job = run(
            "looping",
            """
            var running = true
            onChat { (text) -> if (text == "stop") running = false else echo("heard " + text) }
            echo("started")
            var rounds = 0
            while (running) { sleep(20); rounds++ }
            echo("loop ended")
            stop()
            """.trimIndent(),
        )
        assertEquals("started", host.next())
        awaitState(job, Job.State.RUNNING)
        Thread.sleep(200)
        jobs.dispatch(Job.Event.Chat(ChatMessage("hi")))
        assertEquals("heard hi", host.next()) // delivered while the body is still in its loop
        jobs.dispatch(Job.Event.Chat(ChatMessage("stop")))
        assertEquals("loop ended", host.next())
        awaitState(job, Job.State.DONE)
    }

    @Test
    fun `waiting on the game thread is refused with an explanation`() {
        val job = run("gamewait", """echo(runCatching { game { sleep(10) } }.exceptionOrNull()?.message)""")
        val message = host.next()
        kotlin.test.assertTrue(message.startsWith("sleep can't be used here: this code runs on the game's own thread"), message)
        awaitState(job, Job.State.DONE)
    }
}
