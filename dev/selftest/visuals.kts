// In-game test for glow outlines, fake blocks, pixels and Minekt's settings (incl. Mod Menu's entry).
// Singleplayer test world.
@file:OptIn(ExperimentalVisuals::class)

import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("VZ ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
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

/** What the server has at a block, ignoring anything only this client shows. */
fun serverBlock(x: Int, y: Int, z: Int): String = game { mc ->
    val level = mc.singleplayerServer!!.overworld()
    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(net.minecraft.core.BlockPos(x, y, z)).block).toString()
}

/** Pixels of a screenshot matching [test] (r, g, b). */
fun count(name: String, test: (Int, Int, Int) -> Boolean): Int {
    sleep(250)
    val image = ImageIO.read(screenshot(name).toFile())
    var n = 0
    for (y in 0 until image.height) for (x in 0 until image.width) {
        val c = image.getRGB(x, y)
        if (test(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)) n++
    }
    return n
}

fun pixel(name: String, x: Int, y: Int): Int {
    sleep(250)
    val image = ImageIO.read(screenshot(name).toFile())
    val scale = game { it.window.guiScale }
    return image.getRGB((x * scale).toInt(), (y * scale).toInt()) and 0xFFFFFF
}

// Color.RED and Color.GREEN are soft (255, 64, 64 and 64, 255, 64), not pure.
val redish = { r: Int, g: Int, b: Int -> r > 180 && g < 90 && b < 90 }
val greenish = { r: Int, g: Int, b: Int -> g > 180 && r < 90 && b < 90 }
val yellowish = { r: Int, g: Int, b: Int -> r > 180 && g > 180 && b < 70 }

var waited = 0
while (blockId(player.position.blockPos.below()) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode creative $me")
server("time set 6000")
server("difficulty peaceful")
server("kill @e[type=!player]")
server("scoreboard objectives setdisplay sidebar") // an earlier test's sidebar has red numbers
server("fill -4 199 -3 4 199 12 minecraft:stone")
server("fill -4 200 -3 4 204 12 minecraft:air")
server("tp $me 0.5 200 0.5 0 0")
waitTicks(30)

// ---- glow ------------------------------------------------------------------------------------------

check("glow(entity) outlines it in its color, through a wall") {
    server("summon minecraft:armor_stand 0.5 200 6.5 {NoGravity:1b,Tags:[\"vz\"]}")
    waitTicks(10)
    val stand = entities(16.0).firstOrNull { it.type == "minecraft:armor_stand" } ?: return@check "FALSE no armor stand"
    server("fill -2 200 3 2 203 3 minecraft:stone")
    waitTicks(10)
    val before = count("vz-glow-0", redish)
    val g = glow(stand, Color.RED)
    val with = count("vz-glow-1", redish)
    g.color = Color.GREEN
    val green = count("vz-glow-2", greenish)
    g.remove()
    val after = count("vz-glow-3", redish) + count("vz-glow-4", greenish)
    (before < 20 && with > 100 && green > 100 && after < 20) then "red px: $before -> $with; green after recolor: $green; after remove: $after"
}
check("highlight outlines a block through a wall, in its shape, and recolors") {
    server("setblock 0 200 6 minecraft:oak_slab")
    waitTicks(5)
    val before = count("vz-gb-0", yellowish)
    val g = highlight(0, 200, 6, Color.YELLOW)
    val with = count("vz-gb-1", yellowish)
    g.color = Color.GREEN
    val green = count("vz-gb-1g", greenish)
    g.remove()
    val after = count("vz-gb-2", yellowish)
    (before < 20 && with > 100 && green > 100 && after < 20) then "yellow px: $before -> $with -> $after; green after recolor: $green"
}
server("fill -2 200 3 2 203 3 minecraft:air")
server("kill @e[tag=vz]")

// ---- fake blocks -------------------------------------------------------------------------------------

check("fakeBlock shows a block only on this client; remove() brings the real one back") {
    val f = fakeBlock(0, 200, 3, "minecraft:glass")
    waitTicks(2)
    val shown = blockId(BlockPos(0, 200, 3))
    val real = serverBlock(0, 200, 3)
    f.remove()
    waitTicks(2)
    (shown == "minecraft:glass" && real == "minecraft:air" && blockId(BlockPos(0, 200, 3)) == "minecraft:air") then "client $shown, server $real, after remove ${blockId(BlockPos(0, 200, 3))}"
}
check("fake block states parse, like commands") {
    val f = fakeBlock(1, 200, 3, "minecraft:oak_stairs[facing=east,half=top]")
    val props = blockAt(1, 200, 3).properties
    f.remove()
    (props["facing"] == "east" && props["half"] == "top") then props
}
check("a bad block name says so") {
    val e = runCatching { fakeBlock(1, 200, 3, "minecraft:not_a_block") }.exceptionOrNull()
    (e is IllegalArgumentException && "not_a_block" in e.message.orEmpty()) then e?.message
}
check("a server change under a fake keeps the fake, and shows after remove") {
    val f = fakeBlock(0, 200, 4, "minecraft:glass")
    server("setblock 0 200 4 minecraft:gold_block")
    waitTicks(10)
    val during = blockId(BlockPos(0, 200, 4))
    f.remove()
    waitTicks(2)
    val after = blockId(BlockPos(0, 200, 4))
    server("setblock 0 200 4 minecraft:air")
    (during == "minecraft:glass" && after == "minecraft:gold_block") then "during $during, after $after"
}
check("two fakes on one block: the newest shows, then the older, then the real one") {
    val a = fakeBlock(0, 200, 5, "minecraft:glass")
    val b = fakeBlock(0, 200, 5, "minecraft:diamond_block")
    val top = blockId(BlockPos(0, 200, 5))
    b.remove()
    val next = blockId(BlockPos(0, 200, 5))
    a.remove()
    val last = blockId(BlockPos(0, 200, 5))
    (top == "minecraft:diamond_block" && next == "minecraft:glass" && last == "minecraft:air") then "$top, $next, $last"
}
check("fakeBlocks: 2000 at once is quick") {
    val started = System.nanoTime()
    val plan = buildMap { for (x in -4..4) for (y in 201..204) for (z in -3..12) put(BlockPos(x, y + 10, z), "minecraft:glass") }
    val many = fakeBlocks(plan)
    val ms = (System.nanoTime() - started) / 1_000_000
    val ok = blockId(BlockPos(4, 214, 12)) == "minecraft:glass"
    clearFakeBlocks()
    val cleared = blockId(BlockPos(4, 214, 12)) == "minecraft:air"
    (ok && cleared && ms < 2000) then "${many.size} in $ms ms, cleared: $cleared"
}
check("fake blocks come off when their script ends") {
    val file = game { it.gameDirectory.toPath().resolve("minekt/vzchild.kts") }
    java.nio.file.Files.writeString(file, "@file:OptIn(ExperimentalVisuals::class)\nfakeBlock(2, 200, 6, \"minecraft:emerald_block\")\nsleep(1500)\nstop()\n")
    val run = startScript("vzchild")
    val during = waitFor(5) { blockId(BlockPos(2, 200, 6)) == "minecraft:emerald_block" }
    run.await()
    waitTicks(2)
    val after = blockId(BlockPos(2, 200, 6))
    java.nio.file.Files.deleteIfExists(file)
    (during && after == "minecraft:air") then "during: $during, after: $after"
}

// ---- pixels ---------------------------------------------------------------------------------------

val px = pixels(4, 4)
@Volatile var showPixels = false
onHudRender { g -> if (showPixels) g.pixels(px, 20, 40, 40, 40) }
check("pixels draw on the HUD, scaled up sharp") {
    px.fill(Color.RED)
    px[3, 3] = Color.GREEN
    showPixels = true
    val center = pixel("vz-px-0", 25, 45)
    val corner = pixel("vz-px-1", 55, 75)
    fun rgb(c: Int) = Triple(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
    val (r1, g1, b1) = rgb(center)
    val (r2, g2, b2) = rgb(corner)
    (redish(r1, g1, b1) && greenish(r2, g2, b2)) then "%06X, %06X".format(center, corner)
}
check("writing data directly and changed() redraws") {
    px.data.fill(0xFF0000FF.toInt())
    px.changed()
    val c = pixel("vz-px-2", 25, 45)
    (c == 0x0000FF) then "%06X".format(c)
}
check("transparent pixels show what's behind") {
    px.clear()
    val c = pixel("vz-px-3", 25, 45)
    (c != 0x0000FF && c != 0xFF0000) then "%06X".format(c)
}
check("a 256 × 256 buffer rewritten every frame keeps the frame rate") {
    val big = pixels(256, 256)
    val before = game { it.fps }
    var t = 0
    onHudRender { g ->
        t++
        for (i in big.data.indices) big.data[i] = (0xFF shl 24) or ((i + t) and 0xFFFFFF)
        big.changed()
        g.pixels(big, 100, 20, 128, 128)
    }
    sleep(3000)
    val after = game { it.fps }
    (after >= minOf(before, 30) / 2) then "$before fps -> $after fps"
}
showPixels = false
check("out-of-range pixels say where") {
    val e = runCatching { px[4, 0] = 1 }.exceptionOrNull()
    (e is IndexOutOfBoundsException && "(4, 0)" in e.message.orEmpty()) then e?.message
}

// ---- settings and Mod Menu ---------------------------------------------------------------------------

val factories = net.fabricmc.loader.api.FabricLoader.getInstance()
    .getEntrypointContainers("modmenu", com.terraformersmc.modmenu.api.ModMenuApi::class.java)
    .filter { it.provider.metadata.id == "minekt" }
check("Mod Menu finds Minekt's settings screen") {
    val title = game { mc ->
        val screen = factories.single().entrypoint.modConfigScreenFactory.create(null)
        mc.gui.setScreen(screen)
        screen.title.string
    }
    waitTicks(5)
    val open = game { it.gui.screen()?.title?.string }
    closeScreen()
    (title == "Minekt settings" && open == title) then "${factories.size} entry, opens \"$open\""
}
check("Done goes back to the screen it came from") {
    game { mc ->
        val parent = net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player!!)
        mc.gui.setScreen(factories.single().entrypoint.modConfigScreenFactory.create(parent))
    }
    waitTicks(3)
    game { it.gui.screen()!!.onClose() }
    waitTicks(3)
    val now = game { it.gui.screen()?.javaClass?.simpleName }
    closeScreen()
    // In creative, the inventory turns into the creative one as it opens.
    (now?.endsWith("InventoryScreen") == true) then now
}
check("the prefix can be changed, is saved, and bad ones are refused") {
    val cfg = dev.ilgax.minekt.hooks.MinektConfig
    val refused = listOf("", "/x", "a b", "toolong").filter { !cfg.setPrefix(it) }
    val set = cfg.setPrefix("!")
    val saved = java.nio.file.Files.readString(game { it.gameDirectory.toPath().resolve("minekt/config.json") })
    cfg.setPrefix("\\")
    (refused.size == 4 && set && "\"prefix\": \"!\"" in saved) then "refused ${refused.size}, saved: ${saved.replace("\n", " ")}"
}

// The last checks run in a coroutine, while handlers run on the script thread.
launch {
    check("fakes don't show up as block changes") {
        val changes = AtomicInteger()
        onBlockChange { (_, _, _) -> changes.incrementAndGet() }
        val f = fakeBlock(-1, 200, 5, "minecraft:glass")
        f.remove()
        server("setblock -1 200 5 minecraft:stone")
        waitFor(3) { changes.get() > 0 }
        sleep(500)
        val n = changes.get()
        server("setblock -1 200 5 minecraft:air")
        (n == 1) then "$n change (only the real setblock)"
    }
    server("fill -4 200 -3 4 216 12 minecraft:air")
    echo("VZ DONE")
    sleep(1000)
    game { it.stop() }
}
