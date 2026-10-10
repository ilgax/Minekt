package dev.ilgax.minekt

import dev.ilgax.minekt.share.ScriptInstaller
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScriptInstallerTest {
    private val dir = Files.createTempDirectory("minekt-install")
    private val web = mutableMapOf<String, String>()
    private val installer = ScriptInstaller(dir) { uri -> web[uri.toString()] ?: error("Download failed: HTTP 404") }

    @Test
    fun `page links become raw links, and only https is allowed`() {
        assertEquals(
            URI("https://raw.githubusercontent.com/me/repo/main/scripts/ores.kts"),
            ScriptInstaller.rawUrl("https://github.com/me/repo/blob/main/scripts/ores.kts"),
        )
        assertEquals(URI("https://gist.githubusercontent.com/me/abc123/raw"), ScriptInstaller.rawUrl("https://gist.github.com/me/abc123"))
        assertEquals(URI("https://pastebin.com/raw/Xy12"), ScriptInstaller.rawUrl("https://pastebin.com/Xy12"))
        assertEquals(URI("https://example.com/a.kts"), ScriptInstaller.rawUrl("https://example.com/a.kts"))
        assertFailsWith<IllegalStateException> { ScriptInstaller.rawUrl("http://example.com/a.kts") }
        assertEquals("http", ScriptInstaller.rawUrl("http://127.0.0.1:8000/a.kts").scheme) // local testing is fine
        assertFailsWith<IllegalStateException> { ScriptInstaller.rawUrl("not a url") }
    }

    @Test
    fun `capabilities are listed most sensitive first and comments are ignored`() {
        val caps = ScriptInstaller.capabilities(
            """
            // game { } in a comment doesn't count, nor does chat("x")
            import net.minecraft.client.Minecraft
            val f = java.io.File("x")
            attack(1)
            """.trimIndent()
        )
        assertEquals(
            listOf(
                "read or write files on your computer",
                "use Minecraft's or other mods' code directly",
                "attack or interact with mobs and players",
            ),
            caps,
        )
        assertEquals(emptyList(), ScriptInstaller.capabilities("""echo("hi " + player.name); onChat { echo(it) }"""))
    }

    @Test
    fun `install from a url previews, then writes with its source recorded`() {
        val url = "https://raw.githubusercontent.com/me/repo/main/greet.kts"
        web[url] = "// \\greet — says hi\necho(\"hi\")\n"
        val pending = installer.fromUrl("https://github.com/me/repo/blob/main/greet.kts")
        assertEquals("greet", pending.name)
        assertEquals(emptyList(), pending.problems)
        assertNull(pending.replacesLines)
        assertFalse(dir.resolve("greet.kts").exists(), "nothing is installed before confirming")
        assertTrue(pending.previewFile.exists())

        installer.confirm()
        val installed = dir.resolve("greet.kts").readText()
        assertTrue(installed.startsWith("${ScriptInstaller.SOURCE_MARKER} $url\n"), installed)
        assertEquals(url, installer.sourceOf("greet"))
        assertFalse(pending.previewFile.exists())

        // Updating keeps a single source line and reports what it replaces.
        web[url] = "// \\greet — says hi\necho(\"hello\")\necho(\"again\")\n"
        val update = installer.fromUrl(url, "greet")
        assertEquals(2, update.replacesLines) // the old script's 2 lines; the source line doesn't count
        assertEquals(3, update.lines)
        installer.confirm(update.token)
        assertEquals(1, dir.resolve("greet.kts").readText().lines().count { it.startsWith(ScriptInstaller.SOURCE_MARKER) })
    }

    @Test
    fun `pasted scripts take their name from the usage comment`() {
        val pending = installer.fromText("// \\stash [item] — moves items\necho(1)")
        assertEquals("stash", pending.name)
        assertNull(pending.source)
        installer.confirm(pending.token)
        assertNull(installer.sourceOf("stash")) // no link to update from
        assertFailsWith<IllegalStateException> { installer.fromText("echo(1)") } // no name anywhere
        assertEquals("named", installer.fromText("echo(1)", "named.kts").name)
    }

    @Test
    fun `web pages, empty files and broken scripts are caught before installing`() {
        web["https://example.com/page.kts"] = "<!DOCTYPE html><html>…</html>"
        assertTrue(assertFailsWith<IllegalStateException> { installer.fromUrl("https://example.com/page.kts") }.message!!.contains("web page"))
        web["https://example.com/empty.kts"] = "  \n"
        assertFailsWith<IllegalStateException> { installer.fromUrl("https://example.com/empty.kts") }

        web["https://example.com/broken.kts"] = "val x: Int = \"no\""
        val broken = installer.fromUrl("https://example.com/broken.kts")
        assertTrue(broken.problems.single().startsWith("line 1:"), broken.problems.toString())
    }

    @Test
    fun `cancel discards, and answers need something pending`() {
        dir.resolve("keep.kts").writeText("echo(\"mine\")")
        val pending = installer.fromText("echo(\"theirs\")", "keep")
        assertEquals(1, pending.replacesLines)
        installer.cancel(pending.token)
        assertEquals("echo(\"mine\")", dir.resolve("keep.kts").readText())
        assertFailsWith<IllegalStateException> { installer.confirm() }
        assertFailsWith<IllegalStateException> { installer.confirm("nope") }
    }
}
