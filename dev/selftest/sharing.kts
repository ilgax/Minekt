// In-game test for feedback (sounds, toasts, titles, screenshots) and installing / updating /
// sharing scripts. Expects a local web server on 127.0.0.1:8765 serving greet.kts from WEB_DIR.
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.client.multiplayer.chat.GuiMessage

val WEB_DIR = java.io.File("/tmp/claude-0/web")

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("SHARING ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

val scripts = game { it.gameDirectory.toPath().resolve("minekt") }
fun waitFor(what: String, seconds: Int = 20, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    echo("SHARING timed out waiting for $what")
    return false
}
fun pending(name: String) = scripts.resolve(".pending/$name.kts").toFile()
fun installed(name: String) = scripts.resolve("$name.kts").toFile()

/** Commands of all clickable chat buttons, newest first. */
fun chatButtons(): List<String> = game { mc ->
    val field = ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<GuiMessage>)
        .flatMap { it.content().toFlatList() }
        .mapNotNull { (it.style.clickEvent as? ClickEvent.RunCommand)?.command() }
}

/** Wait for a *new* install preview to appear in chat; returns its token, or null. */
val seenTokens = mutableSetOf<String>()
fun awaitPreview(seconds: Int = 20): String? {
    var token: String? = null
    waitFor("preview in chat", seconds) {
        token = chatButtons().map { it.substringAfterLast(' ') }.firstOrNull { it !in seenTokens && it.length == 8 }
        token != null
    }
    return token?.also { seenTokens += it }
}

/** Run a button's command exactly like clicking it in the chat screen does. */
fun click(command: String) = game { it.player!!.connection.sendUnattendedCommand(command, null) }

var waited = 0
while (blockId(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)
scripts.resolve("greet.kts").toFile().delete(); scripts.resolve("pasted.kts").toFile().delete()

// ---- feedback ----
check("playSound") { playSound("minecraft:entity.experience_orb.pickup"); "played" }
check("unknown sound is an error") { runCatching { playSound("minecraft:no.such.sound") }.exceptionOrNull()?.message ?: false }
check("toast / actionBar / title") { toast("Minekt", "toast test"); actionBar("action bar test"); title("Title test", "subtitle test"); "shown" }
waitTicks(10)
check("screenshot") { val shot = screenshot("minekt-feedback"); "${shot.fileName} ${shot.toFile().length()} bytes".takeIf { shot.toFile().length() > 0 } ?: false }

// ---- install from a link, confirm by clicking the [Install] button ----
chat("\\install http://127.0.0.1:8765/greet.kts")
val first = awaitPreview()
check("preview shown, nothing installed yet") { first != null && !installed("greet").exists() }
check("click [Install]") { "minekt install yes $first".also(::click) }
check("installed with source recorded") {
    waitFor("install") { installed("greet").exists() }
    installed("greet").readLines().first().takeIf { it == "// minekt:source http://127.0.0.1:8765/greet.kts" } ?: false
}
chat("\\greet")
waitTicks(40)

// ---- update: the published script changes ----
WEB_DIR.resolve("greet.kts").writeText("// \\greet — says hi\necho(\"greet v2\")\necho(\"now with more lines\")\n")
chat("\\update greet")
check("update previewed") { awaitPreview() != null }
chat("\\install yes")
check("update installed") { waitFor("update") { "greet v2" in installed("greet").readText() } }
chat("\\greet")
chat("\\update greet")
check("already up to date: no new preview") { awaitPreview(seconds = 4) == null && !pending("greet").exists() }

// ---- share via clipboard, install from clipboard ----
chat("\\share greet")
check("share copies to clipboard") { waitTicks(5); game { it.keyboardHandler.clipboard } == installed("greet").readText() }
game { it.keyboardHandler.clipboard = "// \\pasted — from a friend\necho(\"pasted ok\")\n" }
chat("\\install clipboard")
val pasted = awaitPreview()
check("clipboard install previewed") { pasted != null && pending("pasted").exists() }
check("click [Cancel] keeps nothing") { click("minekt install no $pasted"); sleep(500); !installed("pasted").exists() && !pending("pasted").exists() }
check("stale button is refused") { click("minekt install yes $pasted"); sleep(500); !installed("pasted").exists() }

// ---- safety checks ----
chat("\\install http://example.com/evil.kts")
chat("\\install https://raw.githubusercontent.com/FabricMC/fabric-example-mod/26.3/gradle.properties props")
check("real https download previewed") { awaitPreview(30) != null }
waitTicks(10)
check("preview screenshot") { screenshot("minekt-install-preview").fileName }
chat("\\install no")
check("cancelled") { sleep(500); !pending("props").exists() && !installed("props").exists() }

echo("SHARING DONE")
sleep(1000)
game { it.stop() }
