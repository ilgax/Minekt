package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/** Scripts that never call the API must still stop on `\kill`. */
class WatchdogTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    private fun awaitState(job: Job, state: Job.State, seconds: Long) {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (job.state != state) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} is ${job.state}, expected $state within $seconds s")
            Thread.sleep(10)
        }
    }

    /** Start [code], let it spin, kill it and expect it to end promptly. */
    private fun assertKillable(code: String) {
        val job = jobs.eval(code)
        assertTrue(host.next() == "spinning")
        Thread.sleep(200)
        val killedAt = System.nanoTime()
        job.kill()
        awaitState(job, Job.State.DONE, 2)
        val ms = (System.nanoTime() - killedAt) / 1_000_000
        assertTrue(ms < 1000, "took $ms ms to stop")
    }

    @Test
    fun `empty infinite loop can be killed`() = assertKillable("""echo("spinning"); while (true) {}""")

    @Test
    fun `loop that swallows every exception can be killed`() =
        assertKillable("""echo("spinning"); var n = 0L; while (true) { try { while (true) { n++ } } catch (t: Throwable) { n = 0 } }""")

    @Test
    fun `busy loops in functions, lambdas and coroutines can be killed`() = assertKillable(
        """
        fun spin(): Nothing { var x = 0; while (true) x++ }
        launch { spin() }
        launch { generateSequence(1) { it + 1 }.forEach { _ -> } }
        echo("spinning")
        repeat(Int.MAX_VALUE) { (0..Int.MAX_VALUE).forEach { _ -> } }
        """.trimIndent(),
    )

    @Test
    fun `a script hogging the CPU is reported`() {
        val job = jobs.eval("""echo("spinning"); while (true) {}""")
        assertTrue(host.next() == "spinning")
        // Sample until the warning comes: with other tests busy, one window may not show a full core.
        job.sampleCpu()
        val deadline = System.currentTimeMillis() + 20_000
        while (host.messages.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            job.sampleCpu()
        }
        val message = host.next(5)
        assertTrue("CPU core busy" in message && "\\kill ${job.id}" in message, message)
        job.kill()
        awaitState(job, Job.State.DONE, 2)
    }

    @Test
    fun `scripts using sleep are not reported`() {
        val job = jobs.eval("""echo("waiting"); while (true) sleep(50)""")
        assertTrue(host.next() == "waiting")
        job.sampleCpu()
        Thread.sleep(500)
        job.sampleCpu()
        assertTrue((job.cpuPercent ?: 0) < 50, "cpu ${job.cpuPercent}")
        job.kill()
        awaitState(job, Job.State.DONE, 2)
        assertTrue(host.messages.isEmpty(), host.messages.toString())
    }
}
