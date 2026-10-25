package dev.ilgax.minekt

import dev.ilgax.minekt.share.BundledExamples
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The bundled example scripts: added, updated when unchanged, never overwritten when the player changed them. */
class BundledExamplesTest {
    private val dir = Files.createTempDirectory("minekt-examples")

    private fun ship(vararg examples: Pair<String, String>) =
        BundledExamples(dir, examples.associate { (name, text) -> name to text.toByteArray() }).sync()

    private fun text(name: String) = dir.resolve(name).readText()

    @Test
    fun `a new folder gets every example`() {
        ship("hello" to "echo(1)", "hud" to "hud(1)")
        assertEquals("echo(1)", text("hello.kts"))
        assertEquals("hud(1)", text("hud.kts"))
    }

    @Test
    fun `an example the player didn't change is updated`() {
        ship("hello" to "echo(1)")
        val changes = ship("hello" to "echo(2)")
        assertEquals("echo(2)", text("hello.kts"))
        assertFalse(dir.resolve("hello.kts.new").exists())
        assertEquals(listOf("updated hello.kts"), changes)
    }

    @Test
    fun `an example the player changed is kept, with the new version next to it`() {
        ship("hello" to "echo(1)")
        dir.resolve("hello.kts").writeText("echo(\"mine\")")
        ship("hello" to "echo(2)")
        assertEquals("echo(\"mine\")", text("hello.kts"))
        assertEquals("echo(2)", text("hello.kts.new"))
        // Later versions keep refreshing the .new file, never the player's.
        ship("hello" to "echo(3)")
        assertEquals("echo(\"mine\")", text("hello.kts"))
        assertEquals("echo(3)", text("hello.kts.new"))
    }

    @Test
    fun `an example the player deleted stays deleted, and new examples still arrive`() {
        ship("hello" to "echo(1)")
        Files.delete(dir.resolve("hello.kts"))
        ship("hello" to "echo(2)", "hud" to "hud(1)")
        assertFalse(dir.resolve("hello.kts").exists())
        assertEquals("hud(1)", text("hud.kts"))
    }

    @Test
    fun `a folder from before the manifest adopts matching examples and treats differing ones as changed`() {
        dir.resolve("hello.kts").writeText("echo(1)")
        dir.resolve("hud.kts").writeText("hud(\"edited or old\")")
        ship("hello" to "echo(1)", "hud" to "hud(1)")
        assertEquals("hud(\"edited or old\")", text("hud.kts"))
        assertEquals("hud(1)", text("hud.kts.new"))
        // hello was adopted, so it updates from now on.
        ship("hello" to "echo(2)", "hud" to "hud(1)")
        assertEquals("echo(2)", text("hello.kts"))
    }

    @Test
    fun `line endings alone don't count as a change`() {
        ship("hello" to "a\nb\n")
        dir.resolve("hello.kts").writeText("a\r\nb\r\n")
        ship("hello" to "a\nc\n")
        assertEquals("a\nc\n", text("hello.kts"))
        assertTrue(BundledExamples.hash("a\r\nb".toByteArray()) == BundledExamples.hash("a\nb".toByteArray()))
    }
}