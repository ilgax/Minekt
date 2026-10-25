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
import kotlin.test.assertSame
import kotlin.test.assertNotSame
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
        return compileCached(host.scriptDir.resolve("$name.kts"))
    }

    private fun compileCached(file: Path): KJvmCompiledScript {
        val result = ScriptCompiler.compile(FileScriptSource(file.toFile()))
        return (result as? ResultWithDiagnostics.Success)?.value as? KJvmCompiledScript ?: fail("$file didn't compile: $result")
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
    fun `all import spellings reuse unchanged helpers and notice nested edits without clearing memory`() {
        Files.createDirectories(host.scriptDir.resolve("lib"))
        val nested = host.scriptDir.resolve("lib/nested.kts")
        nested.writeText("fun nestedWord() = \"first\"")
        host.scriptDir.resolve("lib/words.kts").writeText("@file: Import(\"nested.kts\")\nfun word() = nestedWord()")
        val annotations = listOf(
            "@file: Import(\"lib/words.kts\")",
            "@file:[Import(\"lib/words.kts\")]",
            "@file:dev.ilgax.minekt.script.Import(\"lib/words.kts\")",
        )
        val previous = annotations.mapIndexed { i, annotation ->
            val name = "imports$i"
            val file = host.scriptDir.resolve("$name.kts")
            file.writeText("$annotation\n\necho(word())")
            assertEquals("first", run(name))
            val compiled = compileCached(file)
            assertSame(compiled, compileCached(file))
            compiled
        }

        nested.writeText("fun nestedWord() = \"second\"")
        annotations.indices.forEach { i ->
            assertEquals("second", run("imports$i"))
            assertNotSame(previous[i], compileCached(host.scriptDir.resolve("imports$i.kts")))
        }
        annotations.indices.forEach { i ->
            assertIs<CachedModule>(compileFresh("imports$i").getCompiledModule())
        }
        Files.delete(nested)
        annotations.indices.forEach { i ->
            assertIs<ResultWithDiagnostics.Failure>(ScriptCompiler.compile(FileScriptSource(host.scriptDir.resolve("imports$i.kts").toFile())))
        }
    }

    @Test
    fun `same named scripts in different folders keep their own relative helpers`() {
        val left = Files.createDirectories(host.scriptDir.resolve("left"))
        val right = Files.createDirectories(host.scriptDir.resolve("right"))
        left.resolve("words.kts").writeText("fun word() = \"left\"")
        right.resolve("words.kts").writeText("fun word() = \"right\"")
        val source = "@file: Import(\"words.kts\")\necho(word())"
        left.resolve("main.kts").writeText(source)
        right.resolve("main.kts").writeText(source)
        val first = compileCached(left.resolve("main.kts"))
        val second = compileCached(right.resolve("main.kts"))
        assertNotSame(first, second)
        assertSame(first, compileCached(left.resolve("main.kts")))
        assertSame(second, compileCached(right.resolve("main.kts")))
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
