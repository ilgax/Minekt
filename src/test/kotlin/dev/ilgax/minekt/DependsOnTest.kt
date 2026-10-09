package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.script.ScriptDependencies
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** @file:DependsOn through the real Maven resolver, against Maven Central. */
class DependsOnTest {
    private val host = TestHost()
    private val jobs = JobManager(host)

    init {
        ScriptDependencies.libDir = Files.createTempDirectory("minekt-lib")
    }

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 120_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job did not finish")
            Thread.sleep(20)
        }
    }

    @Test
    fun `a library from Maven Central, with its own dependencies`() {
        // commons-text needs commons-lang3, which the resolver pulls in too.
        val job = jobs.eval(
            """
            @file:DependsOn("org.apache.commons:commons-text:1.12.0")
            import org.apache.commons.text.WordUtils
            import org.apache.commons.text.similarity.LevenshteinDistance
            echo(WordUtils.capitalizeFully("hello from maven"), LevenshteinDistance.getDefaultInstance().apply("kitten", "sitting"))
            """.trimIndent(),
        )
        assertEquals("Hello From Maven 3", host.next(120))
        awaitDone(job)
    }

    @Test
    fun `a missing library is a clear compile error`() {
        val job = jobs.eval("""@file:DependsOn("dev.ilgax.nope:does-not-exist:0.0.1")
echo("ran")""")
        val message = host.next(120)
        assertTrue("does-not-exist" in message, message)
        awaitDone(job)
    }
}
