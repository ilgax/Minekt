package dev.ilgax.minekt

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.script.CachedModule
import dev.ilgax.minekt.script.CompiledScriptCache
import dev.ilgax.minekt.script.ScriptCompiler
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/** Compiled scripts kept on disk are used after a restart, and only while nothing they depend on changed. */
class CompiledScriptCacheTest {
    private val host = TestHost()
    private val jobs = JobManager(host)
    private val cacheDir: Path = Files.createTempDirectory("minekt-compiled")

    @BeforeTest
    fun setUp() {
        CompiledScriptCache.dir = cacheDir
    }

    @AfterTest
    fun tearDown() {
        CompiledScriptCache.dir = null
        ScriptCompiler.clearCache()
    }

    private fun run(name: String): String {
        val job = jobs.runScript(name, emptyList()) ?: fail("no script $name")
        val output = host.next()
        val deadline = System.currentTimeMillis() + 60_000
        while (job.state != Job.State.DONE) {
            if (System.currentTimeMillis() > deadline) fail("job $name did not finish")
            Thread.sleep(20)
        }
        return output
    }

    /** Compile [name] as after a restart: with nothing in memory. */
    private fun compileFresh(name: String): KJvmCompiledScript {
        ScriptCompiler.clearCache()
        val result = ScriptCompiler.compile(FileScriptSource(host.scriptDir.resolve("$name.kts").toFile()))
        return (result as? ResultWithDiagnostics.Success)?.value as? KJvmCompiledScript ?: fail("$name didn't compile: $result")
    }

    private fun entries() = cacheDir.listDirectoryEntries("*.bin")

    @Test
    fun `an unchanged script is read back from disk and runs`() {
        host.scriptDir.resolve("disk.kts").writeText("""echo("from", "disk")""")
        assertEquals("from disk", run("disk"))
        assertEquals(1, entries().size)

        assertIs<CachedModule>(compileFresh("disk").getCompiledModule())
        ScriptCompiler.clearCache()
        assertEquals("from disk", run("disk"))
    }

    @Test
    fun `editing the script compiles it again`() {
        val file = host.scriptDir.resolve("edit.kts")
        file.writeText("""echo("one")""")
        assertEquals("one", run("edit"))
        file.writeText("""echo("two")""")
        ScriptCompiler.clearCache()
        assertEquals("two", run("edit"))
        assertEquals(2, entries().size)
    }

    @Test
    fun `editing an imported file compiles the script again`() {
        Files.createDirectories(host.scriptDir.resolve("lib"))
        val util = host.scriptDir.resolve("lib/words.kts")
        util.writeText("fun word() = \"first\"")
        host.scriptDir.resolve("importer.kts").writeText("@file:Import(\"lib/words.kts\")\n\necho(word())")
        assertEquals("first", run("importer"))
        assertIs<CachedModule>(compileFresh("importer").getCompiledModule())

        util.writeText("fun word() = \"second\"")
        ScriptCompiler.clearCache()
        assertEquals("second", run("importer"))
    }

    @Test
    fun `an unreadable entry is compiled again`() {
        host.scriptDir.resolve("broken.kts").writeText("""echo("still works")""")
        assertEquals("still works", run("broken"))
        for (entry in entries()) entry.writeText("not a compiled script")
        ScriptCompiler.clearCache()
        assertEquals("still works", run("broken"))
        assertIs<CachedModule>(compileFresh("broken").getCompiledModule())
    }

    @Test
    fun `nothing is kept without a directory`() {
        CompiledScriptCache.dir = null
        host.scriptDir.resolve("memory.kts").writeText("""echo("memory only")""")
        assertEquals("memory only", run("memory"))
        assertTrue(entries().isEmpty())
    }
}
