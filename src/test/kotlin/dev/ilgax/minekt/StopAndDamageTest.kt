package dev.ilgax.minekt

import dev.ilgax.minekt.api.EntityInfo
import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.Vec3
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** onStop, however a script ends, and onDamage with its cause and attacker. */
class StopAndDamageTest {
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
    fun `onStop runs when the script finishes, calls stop or fails`() {
        awaitState(run("stopfinish", """onStop { echo("bye ${'$'}it") }; echo("body")"""), Job.State.DONE)
        assertEquals("body", host.next())
        assertEquals("bye FINISHED", host.next())

        val stopper = run("stopcall", """onStop { echo("bye ${'$'}it") }; onChat { stop() }""")
        awaitState(stopper, Job.State.LISTENING)
        jobs.dispatch(Job.Event.Chat("x"))
        awaitState(stopper, Job.State.DONE)
        assertEquals("bye FINISHED", host.next())

        awaitState(run("stopfail", """onStop { echo("bye ${'$'}it") }; error("broken")"""), Job.State.DONE)
        val messages = listOf(host.next(), host.next())
        assertTrue("bye FAILED" in messages && messages.any { "broken" in it }, messages.toString())
    }

    @Test
    fun `onStop still runs, with the API working, after a kill`() {
        val job = run("stopkill", """onStop { sleep(100); echo("cleaned up after ${'$'}it") }; echo("looping"); while (true) { }""")
        assertEquals("looping", host.next())
        jobs.kill(job.id)
        awaitState(job, Job.State.DONE)
        assertEquals("cleaned up after KILLED", host.next())
    }

    @Test
    fun `an onStop that never ends is stopped after a few seconds`() {
        val job = run("stophang", """onStop { while (true) { } }; echo("looping"); while (true) { }""")
        assertEquals("looping", host.next())
        val start = System.currentTimeMillis()
        jobs.kill(job.id)
        awaitState(job, Job.State.DONE)
        assertTrue(System.currentTimeMillis() - start in 2_500..10_000, "took ${System.currentTimeMillis() - start} ms")
        assertTrue("onStop took longer" in host.next())
    }

    @Test
    fun `onDamage gets the cause and attacker, and the two-argument form still works`() {
        val job = run(
            "damage",
            """
            onDamage { amount, health -> echo("short ${'$'}amount ${'$'}health") }
            onDamage { amount, health, cause, attacker -> echo("long ${'$'}amount ${'$'}cause ${'$'}{attacker?.name}") }
            onChat { stop() }
            """.trimIndent(),
        )
        awaitState(job, Job.State.LISTENING)
        val zombie = EntityInfo(7, "u", "Zombie", "minecraft:zombie", Vec3(0.0, 0.0, 0.0), 20f, 2.0)
        jobs.dispatch(Job.Event.Game(GameEvent.Damage(3f, 17f, "minecraft:mob_attack", zombie)))
        jobs.dispatch(Job.Event.Game(GameEvent.Damage(2f, 15f)))
        val messages = List(4) { host.next() }.toSet()
        assertEquals(setOf("short 3.0 17.0", "long 3.0 minecraft:mob_attack Zombie", "short 2.0 15.0", "long 2.0 null null"), messages)
        jobs.dispatch(Job.Event.Chat("x"))
        awaitState(job, Job.State.DONE)
    }
}
