package dev.ilgax.minekt

import dev.ilgax.minekt.script.CompilerSetup
import dev.ilgax.minekt.script.MinektScript
import dev.ilgax.minekt.script.ScriptCompiler
import dev.ilgax.minekt.script.TemplateCompiler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The startup warm-up only says the compiler is ready when its script really compiled. */
class CompilerWarmUpTest {
    @Test
    fun `a warm-up that compiles reports ready`() {
        val messages = mutableListOf<Pair<String, Boolean>>()
        assertTrue(CompilerSetup.warmUp(ScriptCompiler) { message, error -> messages += message to error })
        assertEquals(emptyList(), messages)
    }

    @Test
    fun `a warm-up that doesn't compile reports the failure`() {
        // The compiler returns a failed result for this, without throwing.
        val broken = object : TemplateCompiler(MinektScript::class) {
            override val warmUpCode = "val x: Int = \"not a number\""
        }
        val messages = mutableListOf<Pair<String, Boolean>>()
        assertFalse(CompilerSetup.warmUp(broken) { message, error -> messages += message to error })
        val (message, error) = messages.single()
        assertTrue(error)
        assertTrue("Int" in message || "mismatch" in message.lowercase(), message)
    }
}
