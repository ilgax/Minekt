package dev.ilgax.minekt

import dev.ilgax.minekt.job.ErrorReport
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.ScriptLogs
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ErrorsAndLogsTest {
    /** Keeps the full reports, which the real game turns into clickable chat. */
    private class ReportingHost : dev.ilgax.minekt.job.Host by TestHost() {
        val reports = LinkedBlockingQueue<ErrorReport>()
        override fun showError(report: ErrorReport) { reports.put(report) }
        fun nextReport(): ErrorReport = reports.poll(60, TimeUnit.SECONDS) ?: fail("no error reported")
    }

    private val host = ReportingHost()
    private val jobs = JobManager(host)

    private fun awaitDone(job: Job) {
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job ${job.name} did not finish")
            Thread.sleep(20)
        }
    }

    private fun run(name: String, code: String): ErrorReport {
        host.scriptDir.resolve("$name.kts").writeText(code)
        awaitDone(assertNotNull(jobs.runScript(name, emptyList())))
        return host.nextReport()
    }

    @Test
    fun `runtime errors point at the script line and function`() {
        val report = run(
            "parse",
            """
            fun amount(text: String) = text.toInt()

            echo("starting")
            val n = amount("lots")
            """.trimIndent(),
        )
        assertEquals("\"lots\" isn't a number", report.summary)
        assertEquals(1, report.line)
        assertEquals("fun amount(text: String) = text.toInt()", report.sourceLine)
        assertEquals(listOf("line 1 in amount", "line 4"), report.scriptStack)
        assertEquals("[parse] \"lots\" isn't a number (line 1)", report.headline)
        assertTrue("NumberFormatException" in report.detail, report.detail)
    }

    @Test
    fun `error() shows just its message, and lambdas are named after their function`() {
        val report = run("boom", "fun go() {\n    val task = { error(\"no fuel\") }\n    task()\n}\ngo()")
        assertEquals("no fuel", report.summary)
        assertEquals("line 2 in a lambda in go", report.scriptStack.first())
        assertEquals(listOf("line 3 in go", "line 5"), report.scriptStack.drop(1))
    }

    @Test
    fun `recursion is collapsed`() {
        val report = run("deep", "fun f(n: Int): Int = f(n + 1) + 1\nf(0)")
        assertTrue(report.summary.startsWith("Endless recursion"), report.summary)
        assertTrue(report.scriptStack.first().matches(Regex("line 1 in f \\(×\\d+\\)")), report.scriptStack.toString())
        assertTrue(report.scriptStack.size <= 9, report.scriptStack.toString())
    }

    @Test
    fun `compile errors mark the offending code`() {
        val report = run("typo", "val ores = 3\necho(oers + 1)")
        assertEquals(2, report.line)
        assertEquals("echo(oers + 1)", report.sourceLine)
        assertEquals("oers", report.sourceLine!!.substring(report.highlight!!))
        assertTrue(report.headline.startsWith("[typo:2:6] "), report.headline)
        assertTrue("oers" in report.summary, report.summary)
    }

    @Test
    fun `the log has the run, output, writeLog and the error with its stack trace`() {
        run("logged", "echo(\"hello\")\nwriteLog(\"only in the log\", 42)\ncheck(false) { \"bad state\" }")
        jobs.logs.flush()
        val levels = jobs.logs.recent("logged").map { it.level to it.text }
        assertEquals(ScriptLogs.Level.INFO, levels.first().first)
        assertTrue(ScriptLogs.Level.ECHO to "hello" in levels, levels.toString())
        assertTrue(ScriptLogs.Level.LOG to "only in the log 42" in levels, levels.toString())
        assertTrue(levels.any { it.first == ScriptLogs.Level.ERROR && it.second == "bad state (line 3)" }, levels.toString())
        assertTrue(levels.last().second.startsWith("Ended by an error after"), levels.toString())
        val file = jobs.logs.file("logged").readText()
        assertTrue("ECHO  #" in file && "hello" in file, file)
        assertTrue("\tat " in file || "    at " in file, "stack trace in the file:\n$file")
    }

    @Test
    fun `logs keep the latest entries in memory and rotate the file`() {
        val logs = ScriptLogs(Files.createTempDirectory("minekt-logs"))
        val big = "x".repeat(4000)
        repeat(ScriptLogs.MAX_RECENT + 50) { logs.add("s", ScriptLogs.Level.LOG, "$it $big") }
        logs.flush()
        val recent = logs.recent("s")
        assertEquals(ScriptLogs.MAX_RECENT, recent.size)
        assertTrue(recent.last().text.startsWith("${ScriptLogs.MAX_RECENT + 49} "))
        assertTrue(Files.size(logs.file("s")) <= ScriptLogs.MAX_FILE_BYTES + 5000)
        assertTrue(Files.exists(logs.dir.resolve("s.old.log")))
        logs.clear("s")
        logs.flush()
        assertTrue(logs.recent("s").isEmpty() && !Files.exists(logs.file("s")))
    }

    @Test
    fun `friendly descriptions`() {
        assertEquals("A value was null (a !! on null, or a Java method returned null)", ErrorReport.describe(NullPointerException()))
        assertEquals("Out of range: Index 5 out of bounds for length 3", ErrorReport.describe(IndexOutOfBoundsException("Index 5 out of bounds for length 3")))
        assertEquals("List is empty.", ErrorReport.describe(NoSuchElementException("List is empty.")))
        assertEquals("Math error: / by zero", ErrorReport.describe(ArithmeticException("/ by zero")))
        assertEquals("java.io.IOException".substringAfterLast('.') + ": disk", ErrorReport.describe(java.io.IOException("disk")))
    }

    @Test
    fun `unknown script names suggest the closest one`() {
        assertEquals("autowalk", JobManager.closest("autowlak", listOf("hello", "autowalk", "ores")))
        assertEquals("ores", JobManager.closest("Ores", listOf("hello", "ores")))
        assertNull(JobManager.closest("zzzzzz", listOf("hello", "ores")))
    }
}
