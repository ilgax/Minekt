package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/** publish / subscribe between separately compiled scripts. */
class MessagingTest {
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
    fun `data classes cross between scripts`() {
        val receiver = run(
            "receiver",
            """
            data class Stats(val kills: Int, val hp: Float, val tags: List<String>)
            subscribe<Stats>("stats") { echo("got ${'$'}{it.kills} kills, hp ${'$'}{it.hp}, ${'$'}{it.tags}"); stop() }
            echo("ready")
            """.trimIndent(),
        )
        assertEquals("ready", host.next())
        run(
            "sender",
            """
            data class Stats(val kills: Int, val hp: Float, val tags: List<String>)
            publish("stats", Stats(3, 19.5f, listOf("a", "b")))
            """.trimIndent(),
        )
        assertEquals("got 3 kills, hp 19.5, [a, b]", host.next())
        awaitDone(receiver)
    }

    @Test
    fun `latest value reaches scripts that start later`() {
        awaitDone(run("early", """publish("mode", "mining"); publish("counts", listOf(1, 2, 3))"""))
        val late = run(
            "late",
            """
            echo("last: " + lastMessage<List<Int>>("counts")?.sum() + " " + lastMessage<String>("nothing"))
            subscribe<String>("mode", latest = true) { echo("mode " + it); stop() }
            """.trimIndent(),
        )
        assertEquals("last: 6 null", host.next())
        assertEquals("mode mining", host.next())
        awaitDone(late)
    }

    @Test
    fun `awaitMessage in a coroutine and cancelling a subscription`() {
        val waiter = run(
            "waiter",
            """
            val sub = subscribe<Any?>("never") { echo("should not happen") }
            sub.cancel()
            launch { echo("number " + (awaitMessage<Long>("n") + 1)) }
            echo("waiting")
            """.trimIndent(),
        )
        assertEquals("waiting", host.next())
        jobs.eval("""publish("never", 1); publish("n", 41)""")
        assertEquals("number 42", host.next())
        awaitDone(waiter) // no subscriptions or coroutines left
    }
}
