package dev.ilgax.minekt

import dev.ilgax.minekt.hooks.InputRecorder
import dev.ilgax.minekt.hooks.InputRecorder.Action
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.script.ScriptCompiler
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.toScriptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class InputRecorderTest {
    private fun assertCompiles(name: String, text: String) {
        val result = ScriptCompiler.compile(text.toScriptSource("$name.kts"))
        assertTrue(result is ResultWithDiagnostics.Success, "doesn't compile: ${result.reports.joinToString { it.message }}\n$text")
    }

    @Test
    fun `a recording becomes a plain script that compiles`() {
        val text = InputRecorder.script(
            "walk",
            listOf(
                Action(0, "selectSlot(0)", initial = true),
                Action(0, "setOrientation(90.0f, 10.0f)", initial = true),
                Action(0, "keyDown(\"w\")"),
                Action(0, "keyDown(\"left.control\")"),
                Action(20, "keyDown(\"space\")"),
                Action(21, "keyUp(\"space\")"),
                Action(21, "setOrientation(92.5f, 40.0f)"),
                Action(21, "mouseDown(0)"),
                Action(29, "mouseUp(0)"),
                Action(30, "scrollMouse(-1.0)"),
            ),
        )
        assertTrue(text.startsWith("// \\walk, recorded with \\record on "), text)
        assertEquals(
            listOf(
                "selectSlot(0)", "setOrientation(90.0f, 10.0f)", "keyDown(\"w\")", "keyDown(\"left.control\")",
                "waitTicks(20)", "keyDown(\"space\")",
                "waitTicks(1)", "keyUp(\"space\")", "setOrientation(92.5f, 40.0f)", "mouseDown(0)",
                "waitTicks(8)", "mouseUp(0)",
                "waitTicks(1)", "scrollMouse(-1.0)",
                // still held at the end: released
                "keyUp(\"w\")", "keyUp(\"left.control\")",
            ),
            text.lines().filter { it.isNotBlank() && !it.startsWith("//") },
        )
        assertCompiles("walk", text)
    }

    @Test
    fun `keys held when the recording ends are released at its end`() {
        val text = InputRecorder.script("timed", listOf(Action(0, "selectSlot(0)", initial = true), Action(17, "keyDown(\"w\")")), length = 40)
        assertTrue("on " in text && ": 2.0 s." in text, text.lines().first())
        assertEquals(
            listOf("selectSlot(0)", "waitTicks(17)", "keyDown(\"w\")", "waitTicks(23)", "keyUp(\"w\")"),
            text.lines().filter { it.isNotBlank() && !it.startsWith("//") },
        )
    }

    @Test
    fun `long recordings are split into functions that run in order`() {
        val count = InputRecorder.LINES_PER_PART * 2 + 10
        val text = InputRecorder.script("long", List(count) { Action(0, "echo(\"$it\")") })
        assertTrue("fun part3() {" in text && "\npart1()\npart2()\npart3()\n" in text, text.takeLast(200))

        val host = TestHost()
        host.scriptDir.resolve("long.kts").writeText(text)
        val job = assertNotNull(JobManager(host).runScript("long", emptyList()))
        repeat(count) { assertEquals("$it", host.next()) }
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("didn't finish")
            Thread.sleep(10)
        }
    }

    @Test
    fun `recording times`() {
        assertEquals(600, InputRecorder.parseDuration("30"))
        assertEquals(600, InputRecorder.parseDuration("30s"))
        assertEquals(30, InputRecorder.parseDuration("1.5s"))
        assertEquals(2400, InputRecorder.parseDuration("2M"))
        assertEquals(100, InputRecorder.parseDuration("100t"))
        for (bad in listOf("", "0", "0.01", "-5", "ten", "5h", "1e9m")) assertEquals(null, InputRecorder.parseDuration(bad), bad)
    }
}
