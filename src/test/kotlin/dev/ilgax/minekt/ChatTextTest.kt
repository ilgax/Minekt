package dev.ilgax.minekt

import dev.ilgax.minekt.api.ChatClick
import dev.ilgax.minekt.api.Color
import dev.ilgax.minekt.api.chatText
import dev.ilgax.minekt.job.ChatClicks
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Styled chat text and clickable script actions. */
class ChatTextTest {
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
    fun `pieces join, and their plain text is what they say`() {
        val text = chatText("Warning: ", Color.RED, bold = true) + "low " + chatText("durability", hover = "8 uses left")
        assertEquals("Warning: low durability", text.toString())
        assertFailsWith<IllegalArgumentException> { ChatClick.url("file:///etc/passwd") }
    }

    @Test
    fun `echo shows styled text as plain text where styles can't go, and clicks run the script's code`() {
        val job = run(
            "clicky",
            """
            echo("Found", chatText("[again]", Color.GREEN, click = ChatClick.run { echo("clicked"); stop() }), "here")
            onChat { }
            """.trimIndent(),
        )
        assertEquals("Found [again] here", host.next())
        awaitState(job, Job.State.LISTENING)
        val id = job.clickActions.keys.single()
        assertTrue(ChatClicks.click(id))
        assertEquals("clicked", host.next())
        awaitState(job, Job.State.DONE)
        assertFalse(ChatClicks.click(id), "a click after the script ended does nothing")
    }
}
