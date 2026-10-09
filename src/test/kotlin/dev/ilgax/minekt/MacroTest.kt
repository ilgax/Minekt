package dev.ilgax.minekt

import dev.ilgax.minekt.api.Key
import dev.ilgax.minekt.api.step
import dev.ilgax.minekt.hooks.MacroRecorder
import dev.ilgax.minekt.script.ScriptCompiler
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MacroTest {
    private fun frame(keys: Set<Key>, yaw: Float, pitch: Float = 0f, slot: Int = 0) = MacroRecorder.Frame(keys, yaw, pitch, slot)

    private fun assertCompiles(name: String, text: String) {
        val result = ScriptCompiler.compile(text.toScriptSource("$name.kts"))
        assertTrue(result is ResultWithDiagnostics.Success, "doesn't compile: ${result.reports.joinToString { it.message }}\n$text")
    }

    @Test
    fun `frames merge into readable steps that compile`() {
        val frames = List(20) { frame(setOf(Key.FORWARD, Key.SPRINT), 90f) } +
            List(3) { frame(setOf(Key.FORWARD, Key.JUMP), 90.04f) } + // same tenth of a degree: merges
            listOf(frame(setOf(Key.ATTACK), 95f, 30f, slot = 2), frame(emptySet(), 95f, 30f, slot = 2))
        val text = MacroRecorder.script("walk", frames)
        assertTrue(text.startsWith("// \\walk "), text)
        val steps = text.lines().filter { it.trim().startsWith("step(") }
        assertEquals(
            listOf(
                "step(20, \"forward sprint\", yaw = 90.0f, pitch = 0.0f, slot = 0),",
                "step(3, \"forward jump\", yaw = 90.0f, pitch = 0.0f),",
                "step(1, \"attack\", yaw = 95.0f, pitch = 30.0f, slot = 2),",
                "step(1, yaw = 95.0f, pitch = 30.0f),",
            ),
            steps.map { it.trim() },
        )
        assertCompiles("walk", text)
    }

    @Test
    fun `long recordings are split so they still compile`() {
        val frames = List(1200) { frame(if (it % 2 == 0) setOf(Key.LEFT) else setOf(Key.RIGHT), it.toFloat()) }
        val text = MacroRecorder.script("long", frames)
        assertTrue("fun part5()" in text && "macro(part1() + part2()" in text, text.take(400))
        assertCompiles("long", text)
    }

    @Test
    fun `step parses key names`() {
        assertEquals(setOf(Key.FORWARD, Key.SWAP_OFFHAND), step(1, "forward swapoffhand").keys)
        assertFailsWith<IllegalArgumentException> { step(1, "fly") }
    }
}
