// In-game test for script settings, autorun sections, HTTP and the newer events. Needs real input
// (dev/selftest/xdo-runner.sh) and this autorun.txt:
//
//   \events
//   [singleplayer]
//   \arun single
//   [server *]
//   \arun server
//   [dimension the_nether]
//   \arun nether
//
// arun.kts is `echo("ARUN", args.joinToString(" "))`; setdemo.kts is written by this script.
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.multiplayer.chat.GuiMessage
import kotlin.io.path.writeText

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("EV ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun xdo(command: String) {
    echo("XDO $command")
    sleep(800)
}

fun type(line: String) {
    xdo("key t")
    xdo("type --delay 30 '$line'")
    xdo("key Return")
    waitTicks(20)
}

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

fun chatText(n: Int = 40): List<String> = game { mc ->
    val chat = mc.gui.hud.getChat()
    val field = chat.javaClass.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(chat) as List<GuiMessage>).take(n).map { it.content().string }
}

fun screenTitle() = game { it.gui.screen()?.title?.string }
fun widgets() = game { mc -> mc.gui.screen()?.children()?.filterIsInstance<AbstractWidget>()?.map { it.message.string }.orEmpty() }

/** Really click the widget whose label starts with [text]. */
fun click(text: String, fraction: Double = 0.5) {
    val (x, y) = game { mc ->
        val w = mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>().first { it.message.string.startsWith(text) }
        val scale = mc.window.guiScale.toDouble()
        ((w.x + (w.width - 1) * fraction) * scale).toInt() to ((w.y + w.height / 2.0) * scale).toInt()
    }
    xdo("mousemove --window WIN $x $y click 1")
}

fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return condition()
}

var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode creative $me")
server("difficulty easy")
val dir = game { it.gameDirectory.toPath().resolve("minekt") }

// ---- autorun sections -----------------------------------------------------------------------------

check("autorun: the singleplayer section ran, the server one didn't") {
    val runs = chatText().filter { it.startsWith("ARUN") }
    (runs == listOf("ARUN single")) then runs
}

// ---- events ---------------------------------------------------------------------------------------

val events = CopyOnWriteArrayList<GameEvent>()
onBlockChange { pos, old, new -> events += GameEvent.BlockChange(pos, old, new) }
onEntityAppear { events += GameEvent.EntityAppear(it) }
onEntityGone { e, died -> events += GameEvent.EntityGone(e, died) }
onSound { id, pos, category -> events += GameEvent.Sound(id, pos, category) }
onParticle { id, pos, count -> events += GameEvent.Particle(id, pos, count) }
onScoreboardChange { events += GameEvent.ScoreboardChange(it.title, it.lines) }

// Handlers run once the script body is done, so the rest happens in a coroutine.
launch {
    val p = player.position.blockPos
    fun at(dx: Int, dy: Int, dz: Int) = "${p.x + dx} ${p.y + dy} ${p.z + dz}"

    check("onBlockChange sees a block placed and removed") {
        server("setblock ${at(2, 0, 0)} minecraft:gold_block")
        waitTicks(10)
        server("setblock ${at(2, 0, 0)} minecraft:air")
        val target = BlockPos(p.x + 2, p.y, p.z)
        waitFor(5) { events.count { it is GameEvent.BlockChange && it.pos == target } >= 2 }
        val seen = events.filterIsInstance<GameEvent.BlockChange>().filter { it.pos == target }.map { "${it.old} -> ${it.new}" }
        (seen == listOf("minecraft:air -> minecraft:gold_block", "minecraft:gold_block -> minecraft:air")) then seen
    }
    check("onEntityAppear and onEntityGone (died)") {
        server("summon minecraft:pig ${at(3, 0, 3)} {CustomName:\"Testpig\",NoAI:1b}")
        val appeared = waitFor(5) { events.any { it is GameEvent.EntityAppear && it.entity.name == "Testpig" } }
        server("kill @e[type=minecraft:pig,name=Testpig]")
        val gone = waitFor(8) { events.any { it is GameEvent.EntityGone && it.entity.name == "Testpig" } }
        val event = events.filterIsInstance<GameEvent.EntityGone>().firstOrNull { it.entity.name == "Testpig" }
        (appeared && gone && event?.died == true) then event
    }
    check("onSound hears a played sound") {
        server("playsound minecraft:entity.experience_orb.pickup master $me ${at(0, 0, 0)}")
        waitFor(5) { events.any { it is GameEvent.Sound && it.id == "minecraft:entity.experience_orb.pickup" } } then
            events.filterIsInstance<GameEvent.Sound>().map { "${it.id} (${it.category})" }.distinct().take(5)
    }
    check("onParticle sees particles from the server") {
        server("particle minecraft:heart ${at(1, 1, 1)} 0 0 0 0 5 force")
        waitFor(5) { events.any { it is GameEvent.Particle && it.id == "minecraft:heart" } } then
            events.filterIsInstance<GameEvent.Particle>().first { it.id == "minecraft:heart" }
    }
    check("sidebar() and onScoreboardChange") {
        server("scoreboard objectives add kills dummy \"Kill count\"")
        server("scoreboard objectives setdisplay sidebar kills")
        server("scoreboard players set Zombies kills 7")
        server("scoreboard players set Spiders kills 3")
        waitFor(5) { events.filterIsInstance<GameEvent.ScoreboardChange>().lastOrNull()?.lines == listOf("Zombies", "Spiders") }
        val changes = events.filterIsInstance<GameEvent.ScoreboardChange>()
        (sidebar()?.title == "Kill count" && changes.isNotEmpty() && changes.last().lines == listOf("Zombies", "Spiders")) then "${sidebar()} after ${changes.size} changes"
    }
    check("tabList() has the player") { (me in tabList()) then tabList() }

    // ---- http ---------------------------------------------------------------------------------------

    check("httpGet / httpPost inside the game") {
        val local = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        local.createContext("/") { exchange ->
            val body = "got ${exchange.requestMethod} ${exchange.requestBody.readAllBytes().decodeToString()}".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        local.start()
        try {
            val url = "http://127.0.0.1:${local.address.port}/"
            val get = httpGet(url)
            val post = httpPost(url, mapOf("player" to me))
            (get.body == "got GET " && post.body == "got POST {\"player\":\"$me\"}") then "${get.body} | ${post.body}"
        } finally {
            local.stop(0)
        }
    }
    check("httpGet to the internet (through the JVM's proxy settings, if any)") {
        val result = runCatching { httpGet("https://example.com/") }
        result.fold({ "status ${it.status}" }, { "no internet here: ${it.message}" })
    }

    // ---- settings -----------------------------------------------------------------------------------

    dir.resolve("setdemo.kts").writeText(
        """
        var range by setting(16, 1..64)
        var enabled by setting(true)
        var mode by setting("walk", listOf("walk", "sprint", "sneak"))
        echo("SETDEMO start", range, enabled, mode)
        onSettingChange { key, value -> echo("SETDEMO changed", key, value) }
        """.trimIndent() + "\n",
    )
    type("\\setdemo")
    check("a script with settings starts with the defaults") {
        waitFor(30) { chatText(5).any { it == "SETDEMO start 16 true walk" } } then chatText(3)
    }
    type("\\settings setdemo")
    check("\\settings opens the settings screen") { (screenTitle() == "setdemo settings") then widgets() }
    check("a real click on a toggle reaches the running script") {
        click("Enabled")
        waitFor(5) { chatText(5).any { it == "SETDEMO changed enabled false" } } then chatText(3)
    }
    check("a real click on a choice") {
        click("Mode")
        waitFor(5) { chatText(5).any { it == "SETDEMO changed mode sprint" } } then chatText(3)
    }
    check("a real drag on a slider") {
        click("Range", 1.0)
        waitFor(5) { chatText(5).any { it == "SETDEMO changed range 64" } } then chatText(3)
    }
    check("screenshot of the settings screen") { screenshot("minekt-settings") }
    xdo("key Escape")
    waitTicks(5)
    check("the values are saved in minekt/settings/setdemo.json") {
        val file = dir.resolve("settings/setdemo.json").toFile().readText()
        ("\"value\": 64" in file && "\"value\": false" in file && "\"value\": \"sprint\"" in file) then file.lines().size
    }
    type("\\menu")
    check("the manager has a Settings button for it") { ("Settings" in widgets()) then widgets().filter { it == "Settings" }.size }
    xdo("key Escape")
    waitTicks(5)

    // ---- autorun on entering the Nether (last: it changes the world around us) ------------------------

    check("autorun: the dimension section runs on entering the Nether") {
        server("execute in minecraft:the_nether run tp $me 0 120 0")
        waitFor(20) { chatText().any { it == "ARUN nether" } } then chatText().filter { it.startsWith("ARUN") }
    }

    echo("EV DONE")
    sleep(1000)
    game { it.stop() }
}
