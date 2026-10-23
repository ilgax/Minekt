package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import net.fabricmc.fabric.api.event.Event
import net.fabricmc.fabric.api.event.EventFactory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Fabric events made for the tests, like a mod's. */
object TestEvents {
    fun interface Modify {
        fun modify(text: String): String
    }

    @JvmField
    val PING: Event<Runnable> = EventFactory.createArrayBacked(Runnable::class.java) { listeners -> Runnable { listeners.forEach { it.run() } } }

    @JvmField
    val MODIFY: Event<Modify> = EventFactory.createArrayBacked(Modify::class.java) { listeners ->
        Modify { text -> listeners.fold(text) { acc, listener -> listener.modify(acc) } }
    }

    fun listenerCount(event: Event<*>): Int =
        (event.javaClass.getDeclaredField("handlers").apply { isAccessible = true }.get(event) as Array<*>).size
}

/** Fabric API events registered from scripts: opt-in, errors, and removal when the script ends. */
class FabricEventsTest {
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
    fun `listeners registered like in a mod run while the script runs and are removed when it ends`() {
        val job = run(
            "fabricok",
            """
            @file:OptIn(ExperimentalFabricEvents::class)
            import dev.ilgax.minekt.TestEvents

            TestEvents.PING.register { echo("ping") }
            TestEvents.MODIFY.register { text -> "${'$'}text!" }
            onChat { if (it.text == "bye") stop() }
            """.trimIndent(),
        )
        awaitState(job, Job.State.LISTENING)
        assertEquals(1, TestEvents.listenerCount(TestEvents.PING))
        TestEvents.PING.invoker().run()
        assertEquals("ping", host.next())
        assertEquals("hi!", TestEvents.MODIFY.invoker().modify("hi"))

        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("bye")))
        awaitState(job, Job.State.DONE)
        assertEquals(0, TestEvents.listenerCount(TestEvents.PING), "the listener is taken out of the event")
        assertEquals(0, TestEvents.listenerCount(TestEvents.MODIFY))
        TestEvents.PING.invoker().run()
        assertEquals("hi", TestEvents.MODIFY.invoker().modify("hi"))
        Thread.sleep(200)
        assertTrue(host.messages.isEmpty(), "nothing runs after the script ended: ${host.messages}")
    }

    @Test
    fun `without the opt-in, register explains how to turn it on`() {
        val job = run("fabricnoopt", "dev.ilgax.minekt.TestEvents.PING.register { }")
        awaitState(job, Job.State.DONE)
        val error = host.next()
        assertTrue("@file:OptIn(ExperimentalFabricEvents::class)" in error, error)
        assertEquals(0, TestEvents.listenerCount(TestEvents.PING))
    }

    @Test
    fun `a listener that throws is reported, switched off, and doesn't reach the caller`() {
        val job = run(
            "fabricthrow",
            """
            @file:OptIn(ExperimentalFabricEvents::class)
            dev.ilgax.minekt.TestEvents.MODIFY.register { text -> error("broken listener") }
            onChat { if (it.text == "bye") stop() }
            """.trimIndent(),
        )
        awaitState(job, Job.State.LISTENING)
        assertEquals("hi", TestEvents.MODIFY.invoker().modify("hi"), "a failed listener leaves the value unchanged")
        assertTrue("broken listener" in host.next())
        assertEquals("hi", TestEvents.MODIFY.invoker().modify("hi"))
        Thread.sleep(200)
        assertTrue(host.messages.isEmpty(), "switched off after the first error: ${host.messages}")
        jobs.dispatch(Job.Event.Chat(dev.ilgax.minekt.api.ChatMessage("bye")))
        awaitState(job, Job.State.DONE)
    }
}
