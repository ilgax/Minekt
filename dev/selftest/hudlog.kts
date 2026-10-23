// In-game test for HUD panels, script errors in chat, \log and the log viewer. Types into chat with
// real key presses (run it with dev/selftest/xdo-runner.sh). Singleplayer test world.
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("HL ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun xdo(command: String) {
    echo("XDO $command")
    sleep(800)
}

/** Type [line] into chat and send it, with real key presses. */
fun type(line: String) {
    xdo("key t")
    xdo("type --delay 30 '$line'")
    xdo("key Return")
    waitTicks(20)
}

fun screenTitle() = game { it.gui.screen()?.title?.string }
fun widgets() = game { mc -> mc.gui.screen()?.children()?.filterIsInstance<AbstractWidget>()?.map { it.message.string }.orEmpty() }

/** Chat messages, newest first. */
fun chat(): List<Component> = game { mc ->
    val chat = mc.gui.hud.getChat()
    val field = chat.javaClass.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(chat) as List<GuiMessage>).map { it.content() }
}

fun chatText(n: Int = 12) = chat().take(n).map { it.string }

/** Every part of a chat message, flattened. */
fun parts(c: Component): List<Component> = listOf(c) + c.siblings.flatMap(::parts)

/** The texts in all HUD panels right now (read from Minekt's internals). */
fun hudTexts(): List<String> = game {
    val hud = Class.forName("dev.ilgax.minekt.api.ScriptHud")
    val panels = hud.getDeclaredField("panels").apply { isAccessible = true }.get(null) as Map<*, *>
    fun texts(nodes: List<*>): List<String> = nodes.flatMap { node ->
        when (node!!.javaClass.simpleName) {
            "Text", "Item" -> listOf(node.javaClass.getMethod("getText").invoke(node) as String)
            "Row" -> texts(node.javaClass.getMethod("getNodes").invoke(node) as List<*>)
            else -> emptyList()
        }
    }
    panels.values.flatMap { panel -> texts(panel!!.javaClass.getMethod("getNodes").invoke(panel) as List<*>) }
}

var waited = 0
while (blockId(player.position.blockPos.below()) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)

val dir = game { it.gameDirectory.toPath().resolve("minekt") }
val logs = dir.resolve("logs")
val png = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB).apply {
    for (x in 0 until 32) for (y in 0 until 32) setRGB(x, y, (0xFF shl 24) or ((x * 8) shl 16) or ((y * 8) shl 8) or 0xC0)
}
ImageIO.write(png, "png", dir.resolve("hudtest.png").toFile())
dir.resolve("errdemo.kts").writeText(
    """
    fun load(name: String): Int {
        val parts = name.split(":")
        return parts[1].toInt()
    }

    echo("loading")
    load("slot:abc")
    """.trimIndent() + "\n",
)
dir.resolve("typodemo.kts").writeText("val ores = 3\necho(\"found \" + oers)\n")

// ---- HUD ------------------------------------------------------------------------------------------

val miner = hudPanel(Anchor.TOP_RIGHT) {
    title("Miner")
    // Live parts run on the game thread, so game { } inside them is direct.
    text { "Ticks: ${game { it.level!!.gameTime }}" }
    bar("Progress", Color.CYAN) { game { it.level!!.gameTime % 100 } / 100.0 }
    separator()
    row {
        item("diamond", count = 12)
        item("minecraft:iron_ingot") { "${game { it.level!!.gameTime } / 20} s" }
    }
    image("hudtest.png", 32, 32)
}
val second = hudPanel(Anchor.TOP_RIGHT) { text("Stacked under Miner") }
hudPanel(Anchor.BOTTOM_LEFT, scale = 1.5f) { item("golden_apple", "Scaled 1.5×") }
hudPanel(Anchor.LEFT, background = false) {
    text("§aGreen §cred §fwhite")
    bar(0.25, label = "Static", color = Color.ORANGE, width = 80)
}
hudPanel(Anchor.TOP) { text { error("live fail") } }
hudPanel(Anchor.BOTTOM) { text("Above the hotbar") }

check("unknown items are rejected") {
    runCatching { hudPanel { item("not_an_item") } }.exceptionOrNull()?.message.let { (it == "Unknown item 'not_an_item'") then it }
}
check("live text updates every tick") {
    waitTicks(5)
    val a = hudTexts().first { it.startsWith("Ticks:") }
    waitTicks(10)
    val b = hudTexts().first { it.startsWith("Ticks:") }
    (a != b) then "$a -> $b"
}
check("a failing live value shows ⚠ and is logged once") {
    waitTicks(5)
    sleep(500)
    val log = logs.resolve("hudlog.log").readText().substringAfterLast("#$jobId Started") // this run only
    ("⚠ live fail" in hudTexts() && Regex("A live HUD value failed: live fail").findAll(log).count() == 1) then hudTexts()
}
check("screenshot of the HUD") { screenshot("minekt-hud") }
check("update, hide and remove") {
    miner.update { title("Updated"); text("now static") }
    second.visible = false
    val updated = "now static" in hudTexts()
    second.remove()
    (updated && second.isRemoved && !second.visible) then hudTexts()
}

// ---- errors in chat -------------------------------------------------------------------------------

type("\\errdemo")
waitTicks(40)
check("runtime error: summary, line, [log] [edit], source line") {
    val text = chatText()
    (text.any { it.startsWith("[errdemo] \"abc\" isn't a number (line 3) [log] [edit]") } && text.any { "3 │ return parts[1].toInt()" in it }) then text.take(4)
}
check("hovering the error shows where in the script") {
    val head = chat().first { it.string.startsWith("[errdemo]") }
    val hover = (parts(head).firstNotNullOf { it.style.hoverEvent } as net.minecraft.network.chat.HoverEvent.ShowText).value().string
    ("at line 3 in load" in hover && "at line 7" in hover) then hover.replace("\n", " | ")
}

type("\\typodemo")
waitTicks(40)
check("compile error: location and the typo marked in red") {
    val messages = chat().take(6)
    val source = messages.first { "2 │" in it.string }
    val marked = parts(source).first { it.style.isUnderlined }
    (messages.any { it.string.startsWith("[typodemo:2:") && "oers" in it.string } && marked.string == "oers") then messages.map { it.string }.take(3)
}
check("screenshot of the errors in chat") {
    xdo("key t")
    val shot = screenshot("minekt-errors")
    xdo("key Escape")
    shot
}

type("\\errdmo")
check("a misspelled script name suggests the right one") {
    chatText(1).first().let { ("Did you mean \\errdemo?" in it) then it }
}

// ---- the log viewer -------------------------------------------------------------------------------

check("clicking [log] opens the log viewer") {
    xdo("key t")
    game { mc ->
        val head = chat().first { it.string.startsWith("[errdemo]") }
        val click = parts(head).first { it.string == "[log]" }.style.clickEvent as ClickEvent
        // What a mouse click on that text does after hit-testing.
        Screen::class.java.getDeclaredMethod("defaultHandleGameClickEvent", ClickEvent::class.java, net.minecraft.client.Minecraft::class.java, Screen::class.java)
            .apply { isAccessible = true }.invoke(null, click, mc, mc.gui.screen())
    }
    waitTicks(10)
    (screenTitle() == "Log of errdemo") then "${screenTitle()}: ${widgets()}"
}
check("the log file has the run, output and the stack trace") {
    val log = logs.resolve("errdemo.log").readText()
    ("INFO  #" in log && "Started" in log && "ECHO  #" in log && "loading" in log && "isn't a number (line 3)" in log &&
        "errdemo.kts:3" in log && "Ended by an error" in log) then log.lines().size
}
check("screenshot of the log viewer") { waitTicks(5); screenshot("minekt-log") }
xdo("key Escape")
waitTicks(5)

type("\\log")
check("\\log alone opens the last failed script's log") { (screenTitle() == "Log of typodemo") then screenTitle() }
xdo("key Escape")
waitTicks(5)

type("\\menu")
check("manager: Log buttons and clickable recent errors") {
    val labels = widgets()
    (labels.count { it == "Log" } >= 3 && labels.any { it.startsWith("[typodemo:2:") }) then labels.filter { it == "Log" || it.startsWith("[") }
}
check("screenshot of the manager") { screenshot("minekt-manager-errors") }
xdo("key Escape")

type("\\hud")
check("the bundled hud example runs") {
    waitTicks(20)
    val errors = chatText(5).filter { it.startsWith("[hud") }
    (errors.isEmpty() && "Where am I" in hudTexts()) then hudTexts().take(4)
}
check("screenshot of the example") { screenshot("minekt-hud-example") }

echo("HL DONE")
sleep(1000)
game { it.stop() }
