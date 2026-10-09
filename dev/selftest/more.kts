// In-game test for timers, packet hooks, method hooks, the macro recorder and the script manager.
// Run with dev/selftest/xdo-runner.sh: it presses real keys and clicks for the `XDO …` lines.
import dev.ilgax.minekt.MinektClient
import net.minecraft.client.gui.Hud
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket
import net.minecraft.world.entity.LivingEntity
import java.util.concurrent.atomic.AtomicInteger

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("MORE ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else false

fun waitFor(seconds: Int = 20, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return false
}

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

fun chatLines(): List<String> = game { mc ->
    val field = ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<GuiMessage>).map { it.content().string }
}

/** Real input through the harness (`WIN` = the game window). */
fun xdo(command: String) {
    echo("XDO $command")
    sleep(800)
}

fun widgetCenter(text: String): Pair<Int, Int>? = game { mc ->
    val w = mc.gui.screen()?.children()?.filterIsInstance<AbstractWidget>()?.firstOrNull { it.visible && it.message.string.startsWith(text) }
        ?: return@game null
    val scale = mc.window.guiScale.toDouble()
    ((w.x + w.width / 2.0) * scale).toInt() to ((w.y + w.height / 2.0) * scale).toInt()
}

/** Click a widget, first scrolling the wheel down until it's on screen. */
fun clickWidget(text: String): Boolean {
    repeat(10) {
        if (widgetCenter(text) != null) return@repeat
        val (x, y) = game { mc -> (mc.window.width / 2) to (mc.window.height / 2) }
        xdo("mousemove --window WIN $x $y click 5 click 5")
    }
    val (x, y) = widgetCenter(text) ?: return false
    xdo("mousemove --window WIN $x $y click 1")
    return true
}

var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)
val me = player.name
server("gamemode survival $me")
server("time set 1000")
server("gamerule doDaylightCycle true")
server("difficulty peaceful")
waitTicks(20)

// ---- timers --------------------------------------------------------------------------------

check("every / after / everyTicks") {
    val count = AtomicInteger()
    val ticks = AtomicInteger()
    val t = every(100.milliseconds) { count.incrementAndGet() }
    val tt = everyTicks(2) { ticks.incrementAndGet() }
    var fired = false
    after(300.milliseconds) { fired = true }
    sleep(1100)
    t.cancel(); tt.cancel()
    (count.get() in 8..12 && ticks.get() in 6..14 && fired) then "every=${count.get()} everyTicks=${ticks.get()} after=$fired"
}
check("atDaytime crossing") {
    var fired = false
    val timer = atDaytime(1100) { fired = true }
    waitFor(15) { fired }.also { timer.cancel() } then "fired at ${world.timeOfDay}"
}

// ---- packets -------------------------------------------------------------------------------

check("onPacket sees health updates") {
    var seen: Float? = null
    val h = onPacket<ClientboundSetHealthPacket> { seen = it.health }
    server("effect give $me minecraft:instant_damage 1 0 true")
    val got = runBlocking { withTimeoutOrNull(5000) { awaitPacket<ClientboundSetHealthPacket>() } }
    h.remove()
    (got != null) then "health packet: ${got?.health}"
}
check("interceptPacket drops a chat packet, rewrites another") {
    val h = interceptPacket<ClientboundSystemChatPacket> {
        val text = it.content().string
        when {
            "DROPME" in text -> null
            "REWRITE" in text -> ClientboundSystemChatPacket(Component.literal("rewritten by packet hook"), it.overlay())
            else -> it
        }
    }
    server("tellraw @a \"DROPME please\"")
    server("tellraw @a \"REWRITE me\"")
    sleep(1500)
    h.remove()
    val lines = chatLines()
    (lines.none { "DROPME" in it } && lines.any { "rewritten by packet hook" in it }) then lines.take(3)
}
check("onPacketSent sees movement packets") {
    // onPacketSent handlers run after the script body; while the body runs, a coroutine can count.
    val moves = AtomicInteger()
    val counter = launch { while (true) { awaitPacket<ServerboundMovePlayerPacket>(sent = true); moves.incrementAndGet() } }
    sleep(200)
    hold(Key.FORWARD, 600)
    sleep(300)
    counter.cancel()
    (moves.get() > 3) then "${moves.get()} move packets"
}

// ---- method hooks --------------------------------------------------------------------------

check("hook counts and cancels jumps") {
    val jumps = AtomicInteger()
    var cancel = false
    val h = hook<LivingEntity>("jumpFromGround") { call ->
        if (call.self is LocalPlayer) {
            jumps.incrementAndGet()
            if (cancel) call.cancel()
        }
    }
    hold(Key.JUMP, 300)
    sleep(1000)
    val counted = jumps.get()
    cancel = true
    val y0 = position.y
    var maxY = y0
    press(Key.JUMP)
    repeat(15) { waitTicks(1); maxY = maxOf(maxY, position.y) }
    release(Key.JUMP)
    h.remove()
    (counted >= 1 && maxY - y0 < 0.1 && jumps.get() > counted) then "jumps seen=${jumps.get()}, rise while cancelled=${"%.2f".format(maxY - y0)}"
}
check("hook rewrites arguments of a game method") {
    val h = hook<Hud>("setOverlayMessage", Component::class.java, Boolean::class.java) { it.args[0] = Component.literal("HOOKED " + it.arg<Component>(0).string) }
    actionBar("action bar text")
    val shown = game { mc ->
        Hud::class.java.getDeclaredField("overlayMessageString").apply { isAccessible = true }.get(mc.gui.hud).let { (it as Component).string }
    }
    h.remove()
    actionBar("after removal")
    val after = game { mc -> (Hud::class.java.getDeclaredField("overlayMessageString").apply { isAccessible = true }.get(mc.gui.hud) as Component).string }
    (shown == "HOOKED action bar text" && after == "after removal") then "with hook: '$shown', after: '$after'"
}

// ---- macro recorder ------------------------------------------------------------------------

check("record a macro with real keys and replay it") {
    val scripts = game { it.gameDirectory.toPath().resolve("minekt") }
    java.nio.file.Files.deleteIfExists(scripts.resolve("walktest.kts"))
    setOrientation(0f, 0f)
    waitTicks(2)
    chat("\\record walktest")
    waitTicks(5)
    val start = position
    xdo("keydown --window WIN w")
    sleep(1000)
    xdo("keyup --window WIN w")
    waitTicks(5)
    chat("\\record stop")
    if (!waitFor(5) { java.nio.file.Files.exists(scripts.resolve("walktest.kts")) }) return@check "FALSE not saved"
    val recorded = position.distanceTo(start)
    val text = java.nio.file.Files.readString(scripts.resolve("walktest.kts"))
    // Replay from where the recording started.
    server("tp $me ${start.x} ${start.y} ${start.z} 0 0")
    waitTicks(20)
    val before = position
    chat("\\walktest")
    waitFor(15) { MinektClient.jobs.jobs.none { it.name == "walktest" } && position.distanceTo(before) > 0.5 }
    waitTicks(10)
    val replayed = position.distanceTo(before)
    ("\"forward\"" in text && recorded > 1.0 && replayed > recorded * 0.6 && replayed < recorded * 1.4) then
        "recorded %.2f blocks, replay moved %.2f; %d step lines".format(recorded, replayed, text.lines().count { it.trim().startsWith("step(") })
}

// ---- script manager ------------------------------------------------------------------------

check("\\menu opens the manager, lists jobs and scripts") {
    chat("\\menu")
    waitFor(5) { game { it.gui.screen()?.title?.string } == "Minekt scripts" } then
        game { mc -> mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>().filter { it.visible }.map { it.message.string } }
}
check("manager scrolls") {
    val scroll = { game { mc -> mc.gui.screen()!!.let { s -> s.javaClass.getDeclaredField("scroll").apply { isAccessible = true }.getInt(s) } } }
    val before = scroll()
    val (x, y) = widgetCenter("Arguments") ?: (400 to 200)
    xdo("mousemove --window WIN $x $y click 5 click 5 click 5")
    val after = scroll()
    xdo("mousemove --window WIN $x $y click 4 click 4 click 4 click 4")
    (after > before) then "scroll $before -> $after -> ${scroll()}"
}
check("Run a script from the manager") {
    val ok = clickWidget("▶ hello")
    (ok && waitFor(10) { chatLines().any { it.startsWith("Hello, ") } }) then chatLines().firstOrNull { it.startsWith("Hello, ") }
}
check("Stop a running script from the manager") {
    chat("\\eval while (true) sleep(100)")
    if (!waitFor(10) { MinektClient.jobs.jobs.any { it.name == "eval" } }) return@check "FALSE eval didn't start"
    if (game { it.gui.screen()?.title?.string } != "Minekt scripts") chat("\\menu")
    waitFor(5) { widgetCenter("Stop") != null && game { mc -> mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>().count { it.message.string == "Stop" } } >= 2 }
    // The eval job is the newest, so its Stop button is the last one. Scroll up until it's on screen: clicking
    // another visible Stop would stop this test instead.
    fun evalStop() = game { mc -> mc.gui.screen()!!.children().filterIsInstance<AbstractWidget>().last { it.message.string == "Stop" } }
    val (cx, cy) = game { mc -> (mc.window.width / 2) to (mc.window.height / 2) }
    repeat(5) { if (!evalStop().visible) xdo("mousemove --window WIN $cx $cy click 4 click 4 click 4 click 4 click 4 click 4 click 4 click 4") }
    if (!evalStop().visible) return@check "FALSE couldn't scroll the eval job's Stop button into view"
    val (x, y) = game { mc ->
        val w = evalStop()
        val scale = mc.window.guiScale.toDouble()
        ((w.x + w.width / 2.0) * scale).toInt() to ((w.y + w.height / 2.0) * scale).toInt()
    }
    xdo("mousemove --window WIN $x $y click 1")
    waitFor(5) { MinektClient.jobs.jobs.none { it.name == "eval" } } then "eval stopped"
}
check("screenshot of the manager") { screenshot("minekt-manager") }
xdo("key --window WIN Escape")

echo("MORE DONE")
sleep(1000)
game { it.stop() }
