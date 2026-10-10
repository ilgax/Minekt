// In-game test for drawing every frame (onHudRender, onWorldRender), pressing any binding by name,
// the extra player fields and the clipboard. Singleplayer test world.
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("DR ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return condition()
}

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

fun screenName() = game { it.gui.screen()?.javaClass?.simpleName }

/** The color at GUI position [x], [y] of a screenshot, as 0xRRGGBB. */
fun pixel(file: java.nio.file.Path, x: Int, y: Int): Int {
    val image = ImageIO.read(file.toFile())
    val scale = game { it.window.guiScale }
    return image.getRGB((x * scale).toInt(), (y * scale).toInt()) and 0xFFFFFF
}

fun red(color: Int) = color shr 16 and 0xFF
fun green(color: Int) = color shr 8 and 0xFF

var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode survival $me")
server("time set 6000")
server("difficulty peaceful")
server("fill -3 199 -3 3 199 10 minecraft:stone")
server("fill -3 200 -3 3 203 10 minecraft:air")
server("tp $me 0.5 200 0.5 0 0")
waitTicks(20)

// ---- pressing bindings by name ---------------------------------------------------------------

check("bindings() lists the game's bindings") {
    val all = bindings()
    ("key.forward" in all && "key.inventory" in all && "key.hotbar.3" in all) then "${all.size} bindings"
}
check("press(\"inventory\") opens the inventory, like the key") {
    press("inventory")
    release("inventory")
    val opened = waitFor(3) { screenName() == "InventoryScreen" }
    closeScreen()
    waitFor(3) { screenName() == null }
    opened then "opened"
}
check("press(\"key.hotbar.3\") selects the third slot") {
    selectSlot(0)
    press("key.hotbar.3")
    release("key.hotbar.3")
    waitFor(3) { player.selectedSlot == 2 } then "slot ${player.selectedSlot}"
}
check("held bindings: press(\"forward\") walks until released") {
    val start = position
    press("forward")
    sleep(700)
    release("forward")
    sleep(300)
    val moved = position.distanceTo(start)
    (moved > 1.0) then "moved %.2f".format(moved)
}
check("an unknown binding is explained") {
    runCatching { press("notabinding") }.exceptionOrNull()?.message.orEmpty().let { ("No binding named 'notabinding'" in it) then it }
}

// ---- player fields ---------------------------------------------------------------------------

check("player: food, xp, effects, game mode, ground") {
    server("effect give $me minecraft:speed 30 1")
    server("xp set $me 7 levels")
    waitTicks(10)
    val p = player
    (p.food in 0..20 && p.xpLevel == 7 && p.effects.any { it.id == "minecraft:speed" && it.amplifier == 1 && it.duration > 0 } &&
        p.gameMode == "survival" && p.onGround && !p.inWater && p.air == p.maxAir && p.vehicle == null) then
        "food=${p.food} xp=${p.xpLevel} effects=${p.effects} mode=${p.gameMode} air=${p.air}/${p.maxAir} armor=${p.armor}"
}
check("player: velocity and sprinting while moving, flying in creative") {
    press("forward")
    press("sprint")
    sleep(500)
    val moving = player
    release("sprint")
    release("forward")
    server("gamemode creative $me")
    server("tp $me 0.5 203 0.5") // in the air: landing turns flying off
    waitTicks(5)
    game { mc -> mc.player!!.abilities.flying = true; mc.player!!.onUpdateAbilities() }
    waitTicks(5)
    val flying = player
    game { mc -> mc.player!!.abilities.flying = false; mc.player!!.onUpdateAbilities() }
    server("gamemode survival $me")
    server("effect clear $me")
    waitTicks(10)
    val speed = Math.hypot(moving.velocity.x, moving.velocity.z)
    (speed > 0.05 && moving.sprinting && flying.flying && flying.gameMode == "creative") then
        "speed %.3f, sprinting ${moving.sprinting}, flying ${flying.flying} in ${flying.gameMode}".format(speed)
}

// ---- clipboard -------------------------------------------------------------------------------

check("setClipboard / clipboard") {
    val text = "minekt clipboard ${System.nanoTime()}"
    setClipboard(text)
    (clipboard() == text) then clipboard()
}

// ---- drawing every frame -------------------------------------------------------------------

server("tp $me 0.5 200 0.5 0 0")
setOrientation(0f, 30f)
waitTicks(10)

val hudFrames = AtomicInteger()
onHudRender { g ->
    hudFrames.incrementAndGet()
    g.rect(20, 20, 40, 30, Color.RED)
    g.outline(18, 18, 44, 34, Color.WHITE)
    val w = g.text("Drawn by onHudRender", 20, 56, Color.YELLOW)
    g.rect(20, 56 + g.lineHeight + 2, w, 2, Color.CYAN)
    g.item("minecraft:diamond", 70, 20, count = 3)
}
check("onHudRender runs every frame") {
    val before = hudFrames.get()
    sleep(1000)
    val perSecond = hudFrames.get() - before
    val fps = game { it.fps }
    (perSecond >= maxOf(1, fps / 2)) then "$perSecond frames in 1 s at $fps fps"
}
check("onHudRender draws: the red rectangle is in a screenshot") {
    val shot = screenshot("minekt-draw-hud")
    val color = pixel(shot, 40, 35)
    (red(color) > 200 && green(color) < 100) then "pixel %06X".format(color)
}

val worldFrames = AtomicInteger()
@Volatile var bigBox = false
onWorldRender { w ->
    worldFrames.incrementAndGet()
    val ahead = Vec3(0.5, 199.0, 4.5)
    w.box(Vec3(-0.5, 199.0, 3.5), Vec3(1.5, 201.0, 5.5), Color.GREEN)
    w.line(Vec3(-2.0, 200.5, 3.0), Vec3(3.0, 200.5, 3.0), Color.YELLOW, 4f)
    w.text(Vec3(0.5, 201.6, 4.5), "onWorldRender", Color.ORANGE, 1.5f)
    w.point(ahead, Color.CYAN, 8f)
    if (bigBox) w.box(Vec3(-1.0, 199.0, 1.5), Vec3(2.0, 203.0, 2.5), Color.RED, fill = true, throughWalls = true)
}
check("onWorldRender runs every frame, with the camera position") {
    val before = worldFrames.get()
    sleep(1000)
    val perSecond = worldFrames.get() - before
    val fps = game { it.fps }
    (perSecond >= maxOf(1, fps / 2)) then "$perSecond frames in 1 s at $fps fps"
}
check("onWorldRender draws: a filled red box turns the middle of the view red") {
    val center = game { mc -> mc.window.guiScaledWidth / 2 to mc.window.guiScaledHeight / 2 + 20 }
    val without = pixel(screenshot("minekt-draw-world-before"), center.first, center.second)
    bigBox = true
    sleep(300)
    val with = pixel(screenshot("minekt-draw-world"), center.first, center.second)
    bigBox = false
    (red(with) - red(without) > 10) then "%06X -> %06X".format(without, with)
}
check("entityPosition follows an entity between ticks") {
    server("summon minecraft:armor_stand 0.5 200 6.5 {NoGravity:1b,Tags:[\"drawtest\"]}")
    waitTicks(10)
    val stand = entities(16.0).firstOrNull { it.type == "minecraft:armor_stand" } ?: return@check "FALSE no armor stand"
    val positions = java.util.concurrent.CopyOnWriteArrayList<Vec3?>()
    onWorldRender { w -> if (positions.size < 3) positions += w.entityPosition(stand.id) }
    waitFor(3) { positions.size >= 3 }
    server("kill @e[tag=drawtest]")
    val p = positions.firstOrNull()
    (p != null && Math.abs(p.x - 0.5) < 0.01 && Math.abs(p.z - 6.5) < 0.01) then p
}
check("a handler that throws is removed, and its error shown") {
    val calls = AtomicInteger()
    onHudRender { calls.incrementAndGet(); error("draw failed on purpose") }
    sleep(500)
    val n = calls.get()
    val shown = game { mc ->
        val field = net.minecraft.client.gui.components.ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(mc.gui.hud.chat) as List<net.minecraft.client.multiplayer.chat.GuiMessage>).any { "draw failed on purpose" in it.content().string }
    }
    (n == 1 && shown) then "called $n time, error in chat: $shown"
}

// ---- styled chat ---------------------------------------------------------------------------

fun lastChat(): net.minecraft.network.chat.Component = game { mc ->
    val field = net.minecraft.client.gui.components.ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<net.minecraft.client.multiplayer.chat.GuiMessage>).first().content()
}

/** The style of the first piece of [message] whose text is [text]. */
fun styleOf(message: net.minecraft.network.chat.Component, text: String): net.minecraft.network.chat.Style? {
    var found: net.minecraft.network.chat.Style? = null
    message.visit({ style, piece -> if (piece == text && found == null) found = style; java.util.Optional.empty<Unit>() }, net.minecraft.network.chat.Style.EMPTY)
    return found
}

@Volatile var clicked = 0
var clickCommand: String? = null
check("echo shows colors, hover text and click actions") {
    echo("Ores:", chatText("[copy]", Color.GREEN, bold = true, hover = "Copy them", click = ChatClick.copy("12 -58 40")),
        chatText("[again]", Color.CYAN, click = ChatClick.run { clicked++ }))
    waitTicks(5)
    val message = lastChat()
    val copy = styleOf(message, "[copy]")
    val again = styleOf(message, "[again]")
    clickCommand = (again?.clickEvent as? net.minecraft.network.chat.ClickEvent.RunCommand)?.command()
    (message.string == "Ores: [copy] [again]" && copy?.color?.value == (Color.GREEN and 0xFFFFFF) && copy.isBold &&
        copy.hoverEvent is net.minecraft.network.chat.HoverEvent.ShowText &&
        (copy.clickEvent as? net.minecraft.network.chat.ClickEvent.CopyToClipboard)?.value() == "12 -58 40" &&
        again?.clickEvent is net.minecraft.network.chat.ClickEvent.RunCommand) then "${message.string}: ${again?.clickEvent}"
}

// ---- other entities --------------------------------------------------------------------------

check("entities: custom name, baby, equipment, glowing, max health") {
    server("difficulty easy")
    server("summon minecraft:zombie 2.5 200 6.5 {IsBaby:1b,CustomName:\"Bob\",Glowing:1b,NoAI:1b,PersistenceRequired:1b,Tags:[\"drawtest\"],equipment:{mainhand:{id:\"minecraft:iron_sword\",count:1},head:{id:\"minecraft:iron_helmet\",count:1}}}")
    waitFor(5) { entities(16.0).any { it.type == "minecraft:zombie" } }
    waitTicks(10)
    val bob = entities(16.0).firstOrNull { it.type == "minecraft:zombie" } ?: return@check "FALSE no zombie"
    val again = entity(bob.id)
    server("kill @e[tag=drawtest]")
    server("difficulty peaceful")
    waitTicks(20)
    (bob.customName == "Bob" && bob.isBaby && bob.heldItem == "minecraft:iron_sword" && bob.equipment["head"] == "minecraft:iron_helmet" &&
        bob.glowing && bob.maxHealth == 20f && again?.id == bob.id && entity(bob.id) == null) then
        "${bob.customName}: baby=${bob.isBaby} equipment=${bob.equipment} glowing=${bob.glowing} health=${bob.health}/${bob.maxHealth}, then gone"
}

// Clicks are handled like other events, once the script body is done: click, then check from a coroutine.
launch {
    check("clicking a ChatClick.run text runs the script's code") {
        execute(clickCommand ?: return@check "FALSE no click command") // what the game does when the text is clicked
        waitFor(5) { clicked == 1 } then "clicked=$clicked via /$clickCommand"
    }
    echo("DR DONE")
    sleep(1000)
    game { it.stop() }
}
