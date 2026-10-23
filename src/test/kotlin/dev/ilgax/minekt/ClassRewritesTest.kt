package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Scripts replacing bytecode of loaded and not-yet-loaded classes. */
class ClassRewritesTest {
    private val host = TestHost()
    private val jobs = JobManager(host)
    private val target = RewriteTarget() // loaded before any rewrite

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 30_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job did not finish")
            Thread.sleep(10)
        }
        while (job in jobs.jobs) Thread.sleep(10)
    }

    private val imports = """
@file:OptIn(ExperimentalHooks::class)
        import dev.ilgax.minekt.RewriteTarget
        import org.objectweb.asm.Opcodes
        import org.objectweb.asm.tree.*
        /** Replace [name]'s body with `return <value>`. */
        fun ClassNode.returns(name: String, value: Any, op: Int) {
            val method = methods.first { it.name == name }
            method.instructions.clear()
            method.tryCatchBlocks.clear()
            method.localVariables?.clear()
            method.instructions.add(LdcInsnNode(value))
            method.instructions.add(InsnNode(op))
        }
    """.trimIndent()

    private fun script(code: String): Job {
        val job = jobs.eval("$imports\n$code\necho(\"ready\")")
        assertEquals("ready", host.next())
        return job
    }

    @Test
    fun `rewrite a loaded class, stack rewrites and hooks, then restore`() {
        assertEquals(1, target.value())
        val first = script(
            """
            rewriteClass<RewriteTarget> { it.returns("value", 42, Opcodes.IRETURN) }
            onChat { stop() }
            """.trimIndent(),
        )
        assertEquals(42, target.value())
        assertEquals("original", target.label())
        // A second script rewrites the same class on top, and hooks the rewritten method.
        val second = script(
            """
            rewriteClass<RewriteTarget> { it.returns("label", "rewritten", Opcodes.ARETURN) }
            hookAfter<RewriteTarget>("value") { it.result = (it.result as Int) + 1 }
            onChat { stop() }
            """.trimIndent(),
        )
        assertEquals(43, target.value())
        assertEquals("rewritten", target.label())
        jobs.kill(first.id)
        awaitDone(first)
        assertEquals(2, target.value()) // first rewrite gone, hook still there
        jobs.kill(second.id)
        awaitDone(second)
        assertEquals(1, target.value())
        assertEquals("original", target.label())
    }

    @Test
    fun `raw bytes in and out, and classBytes reads what runs`() {
        val job = jobs.eval(
            """
            $imports
            val before = classBytes(RewriteTarget::class.java)
            var seen = 0
            val rewrite = transformClass<RewriteTarget> { bytes -> seen = bytes.size; null }  // null: unchanged
            echo("sizes", before.size == seen, before[0] == 0xCA.toByte())
            rewrite.remove()
            """.trimIndent(),
        )
        assertEquals("sizes true true", host.next())
        awaitDone(job)
        assertEquals(1, target.value())
    }

    @Test
    fun `changes the JVM refuses are reported and leave the class alone`() {
        val job = jobs.eval(
            """
            $imports
            val adds = runCatching {
                rewriteClass<RewriteTarget> { node -> node.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "extra", "()V", null, null).apply { instructions.add(InsnNode(Opcodes.RETURN)) }) }
            }.exceptionOrNull()?.message
            echo(adds)
            val broken = runCatching { transformClass<RewriteTarget> { ByteArray(4) } }.exceptionOrNull()?.message
            echo(broken)
            val throws = runCatching { transformClass<RewriteTarget> { error("nope") } }.exceptionOrNull()?.message
            echo(throws)
            """.trimIndent(),
        )
        val adds = host.next()
        assertTrue("refused" in adds && "can't" in adds.lowercase() || "not add" in adds, adds)
        assertTrue(host.next().startsWith("The JVM refused the new dev.ilgax.minekt.RewriteTarget"))
        assertEquals("Rewriting dev.ilgax.minekt.RewriteTarget failed: nope", host.next())
        awaitDone(job)
        assertEquals(1, target.value())
    }

    @Test
    fun `a class that isn't loaded yet is rewritten when it loads`() {
        val job = script(
            """
            rewriteClass("dev.ilgax.minekt.LazyRewriteTarget") { it.returns("value", 7, Opcodes.IRETURN) }
            onChat { stop() }
            """.trimIndent(),
        )
        assertEquals(7, LazyRewriteTarget().value())
        jobs.kill(job.id)
        awaitDone(job)
        assertEquals(1, LazyRewriteTarget().value())
    }
}
