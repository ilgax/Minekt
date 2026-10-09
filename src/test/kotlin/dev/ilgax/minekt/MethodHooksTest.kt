package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Hooks on an already-loaded class, through a runtime-attached agent. */
class MethodHooksTest {
    private val host = TestHost()
    private val jobs = JobManager(host)
    private val target = HookTarget() // loads the class before any hook exists

    /** The JVM static `HookTarget.twice` (Kotlin code would call the companion's copy instead). */
    private fun twice(x: Long) = HookTarget::class.java.getMethod("twice", Long::class.java).invoke(null, x)

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 30_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job did not finish")
            Thread.sleep(10)
        }
    }

    private fun script(code: String): Job {
        val job = jobs.eval("import dev.ilgax.minekt.HookTarget\n$code\necho(\"hooked\")")
        assertEquals("hooked", host.next())
        return job
    }

    @Test
    fun `change arguments, cancel and replace results, then restore`() {
        assertEquals(3, target.add(1, 2))
        val job = script(
            """
            hook<HookTarget>("add") { it.args[0] = 10 }
            hookAfter<HookTarget>("add") { it.result = (it.result as Int) * 100 }
            hook<HookTarget>("greet") { if (it.arg<String>(0) == "bob") it.cancel("go away") }
            hook<HookTarget>("record") { it.arg<MutableList<String>>(0) += "before" }
            hookAfter<HookTarget>("scale") { it.result = 1 }           // an Int for a Double method: converted
            hook(HookTarget::class.java, "twice") { it.args[0] = 5 }  // static method
            """.trimIndent(),
        )
        assertEquals(1200, target.add(1, 2))
        assertEquals("go away", target.greet("bob"))
        assertEquals("hi ann", target.greet("ann"))
        assertEquals(listOf("before", "original"), mutableListOf<String>().also(target::record))
        assertEquals(1.0, target.scale(4.0))
        assertEquals(10L, twice(1))

        job.kill()
        awaitDone(job)
        assertEquals(3, target.add(1, 2))
        assertEquals("hi bob", target.greet("bob"))
        assertEquals(2L, twice(1))
    }

    @Test
    fun `remove one hook and the script ends`() {
        val job = script("""val h = hook<HookTarget>("greet") { it.cancel("hooked once"); }; launch { delay(300); h.remove() }""")
        assertEquals("hooked once", target.greet("x"))
        awaitDone(job)
        assertEquals("hi x", target.greet("x"))
    }

    @Test
    fun `a throwing handler is removed and the method still works`() {
        script("""hook<HookTarget>("scale") { error("bad hook") }; hook<HookTarget>("add") { }""")
        assertEquals(8.0, target.scale(4.0))
        assertTrue("bad hook" in host.next())
        Thread.sleep(300)
        assertEquals(8.0, target.scale(4.0))
    }

    @Test
    fun `overloads must be named`() {
        jobs.eval("""import dev.ilgax.minekt.HookTarget; hook<HookTarget>("overloaded") { }""")
        assertTrue("is overloaded" in host.next())
        val job = script("""hook<HookTarget>("overloaded", String::class.java) { it.cancel("picked") }""")
        assertEquals("picked", target.overloaded("x"))
        assertEquals(4, target.overloaded(4))
        job.kill()
        awaitDone(job)
    }
}
