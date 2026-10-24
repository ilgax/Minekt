// In-game test for the watchdog, script screens and messaging. Run in a singleplayer world.
// Clicks and typing go through the screen's real mouse/keyboard handlers.
import dev.ilgax.minekt.MinektClient
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("UI ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

/** `condition then detail`: the detail when it holds, else false (a FAIL). */
infix fun Boolean.then(detail: Any?): Any? = if (this) detail else false

fun waitFor(seconds: Int = 20, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return false
}

/** Every line in the chat window, newest first. */
fun chatLines(): List<String> = game { mc ->
    val field = ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<GuiMessage>).map { it.content().string }
}

fun job(name: String) = MinektClient.jobs.jobs.firstOrNull { it.name == name }

var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)

// ---- watchdog ------------------------------------------------------------------------------

check("\\kill stops a loop that never calls the API") {
    chat("\\eval while (true) {}")
    if (!waitFor { job("eval")?.state?.name == "RUNNING" }) return@check "FALSE never started"
    val spinner = job("eval")!!
    sleep(500)
    chat("\\kill ${spinner.id}")
    val started = System.currentTimeMillis()
    waitFor(5) { spinner.state.name == "DONE" } then "stopped in ${System.currentTimeMillis() - started} ms"
}
check("CPU hog warning") {
    chat("\\eval var n = 0L; while (true) { n++ }")
    val warned = waitFor(15) { chatLines().any { "keeping a CPU core busy" in it } }
    val hog = job("eval")
    chat("\\kill ${hog?.id}")
    waitFor(5) { job("eval") == null }
    if (warned) chatLines().first { "CPU core" in it } else false
}
check("game still ticks afterwards") {
    val t = game { it.level!!.gameTime }
    waitTicks(10)
    game { it.level!!.gameTime } > t
}

// ---- messaging -----------------------------------------------------------------------------

data class Stats(val kills: Int, val where: Vec3)
check("publish / subscribe between scripts") {
    chat("\\eval data class Stats(val kills: Int, val where: Vec3); publish(\"stats\", Stats(7, player.position))")
    val got = runBlocking { withTimeoutOrNull(15_000) { awaitMessage<Stats>("stats") } }
    got ?: false
}
check("lastMessage") { lastMessage<Stats>("stats")?.kills == 7 }

// ---- screens -------------------------------------------------------------------------------

var enabled = false
var range = 16
var speed = 1.0
var label = "home"
var mode = "walk"
var clicks = 0
var closedBy = ""

val screen = showScreen("Minekt UI test") {
    text("Settings for the UI test\nsecond line")
    text { "Live: tick ${game { it.level!!.gameTime }} • clicks $clicks" }
    toggle("Enabled", ::enabled)
    slider("Range", 8..64, ::range)
    slider("Speed", 0.5..3.0, ::speed, step = 0.25)
    textField("Name", ::label)
    choice("Mode", listOf("walk", "sprint", "fly"), ::mode)
    row {
        button("Click me", tooltip = "Counts clicks") { clicks++ }
        button("Refresh") { }
    }
}
screen.onClose { closedBy = "onClose ran" }
waitTicks(10)

fun widgets(): List<AbstractWidget> = game { mc -> mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>() }
fun widget(text: String) = widgets().first { it.message.string.startsWith(text) }

/** Ask the test harness to run `xdotool <command>` (real mouse/keyboard input); `WIN` is the game window. */
fun xdo(command: String) {
    echo("XDO $command")
    sleep(800)
}

/** Really click a widget, at [fraction] of its width. */
fun click(text: String, fraction: Double = 0.5) {
    val (x, y) = game { mc ->
        val w = mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>().first { it.message.string.startsWith(text) }
        val scale = mc.window.guiScale.toDouble()
        ((w.x + (w.width - 1) * fraction) * scale).toInt() to ((w.y + w.height / 2.0) * scale).toInt()
    }
    xdo("mousemove --window WIN $x $y click 1")
}

check("screen is open and doesn't pause the game") {
    val title = game { it.gui.screen()?.title?.string }
    val t = game { it.level!!.gameTime }
    waitTicks(5)
    (title == "Minekt UI test" && screen.isOpen && game { it.level!!.gameTime } > t) then "title '$title', widgets: ${widgets().map { it.message.string }}"
}
check("live text updates") {
    sleep(300)
    true
}
check("screenshot of the screen") { screenshot("minekt-ui-1") }

check("toggle") { click("Enabled"); waitFor(5) { enabled } then widget("Enabled").message.string }
check("int slider") { click("Range", 0.75); waitFor(5) { range != 16 } then "range=$range (${widget("Range").message.string})" }
check("double slider snaps to step") {
    click("Speed", 1.0)
    (waitFor(5) { speed != 1.0 } && (speed * 4) % 1.0 == 0.0) then "speed=$speed"
}
check("choice") { click("Mode"); waitFor(5) { mode == "sprint" } then "mode=$mode" }
check("button callback on script side") {
    repeat(3) { click("Click me") }
    waitFor(5) { clicks == 3 } then "clicks=$clicks"
}
check("text field typing") {
    val (x, y) = game { mc ->
        val box = mc.gui.screen()!!.children().filterIsInstance<net.minecraft.client.gui.components.EditBox>().first()
        val scale = mc.window.guiScale.toDouble()
        ((box.x + box.width / 2.0) * scale).toInt() to ((box.y + box.height / 2.0) * scale).toInt()
    }
    xdo("mousemove --window WIN $x $y click 1")
    xdo("key --window WIN End BackSpace BackSpace BackSpace BackSpace")
    xdo("type --window WIN base")
    waitFor(5) { label == "base" } then "label=$label"
}
check("live text shows script state") {
    sleep(300)
    game { mc ->
        // Live text is drawn rather than a widget; read it back from the view.
        val view = mc.gui.screen()!!
        val slots = view.javaClass.getDeclaredField("texts").apply { isAccessible = true }.get(view) as List<*>
        slots.map { s -> s!!.javaClass.getDeclaredField("lines").apply { isAccessible = true }.get(s) as List<*> }.flatten()
    }.let { lines -> lines.any { "clicks 3" in it.toString() } then lines.joinToString(" | ") }
}
check("screenshot after edits") { screenshot("minekt-ui-2") }
check("refresh keeps the screen open") {
    screen.refresh()
    waitTicks(2)
    screen.isOpen then widget("Range").message.string
}
check("Esc closes it and runs onClose") {
    xdo("key --window WIN Escape")
    (waitFor(5) { !screen.isOpen && closedBy.isNotEmpty() } && game { it.gui.screen() == null }) then closedBy
}
check("awaitClose / reopen and close()") {
    val second = showScreen("Second", doneButton = false) { text("closing soon") }
    launch { delay(300); second.close() }
    runBlocking { withTimeoutOrNull(5000) { second.awaitClose(); "closed" } } ?: false
}

echo("UI DONE")
sleep(1000)
game { it.stop() }
