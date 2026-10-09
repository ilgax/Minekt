package dev.ilgax.minekt

import dev.ilgax.minekt.api.passed
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TimersTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 30_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job did not finish")
            Thread.sleep(10)
        }
    }

    @Test
    fun `every and after run alongside the body and keep the script alive`() {
        val job = jobs.eval(
            """
            var n = 0
            val t = every(50.milliseconds) { n++; echo("tick ${'$'}n"); if (n == 3) stop() }
            after(20.milliseconds) { echo("after") }
            echo("body done")
            """.trimIndent(),
        )
        val seen = List(5) { host.next(10) }
        assertEquals("body done", seen.first())
        assertEquals(listOf("after", "tick 1", "tick 2", "tick 3"), seen.drop(1))
        awaitDone(job)
    }

    @Test
    fun `cancelled timer lets the script end`() {
        val job = jobs.eval("""val t = every(1.hours) { echo("never") }; t.cancel(); echo("ok " + t.isActive)""")
        assertEquals("ok false", host.next())
        awaitDone(job)
    }

    @Test
    fun `at rejects bad times`() {
        jobs.eval("""at("25:99") { }""")
        assertTrue("like \"18:00\"" in host.next(), "message")
    }

    @Test
    fun `daytime crossing`() {
        assertTrue(passed(12990, 13010, 13000))
        assertFalse(passed(13000, 13010, 13000))
        assertTrue(passed(23990, 10, 0))
        assertTrue(passed(23990, 10, 23995))
        assertFalse(passed(23990, 10, 12000))
        assertTrue(passed(1000, 500, 12000)) // /time set backwards passes everything after 1000
    }
}
