// \prismcheck — a guided check on a real desktop (Prism or the official launcher), for what the headless test
// display can't show: real frame rates, real mouse and keyboard input, clicking chat. Open a singleplayer
// world (creative is fine), run \prismcheck and follow the big text on screen. It takes about two minutes.
// Results go to chat and to minekt/prismcheck-result.txt, to paste back.

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

val results = CopyOnWriteArrayList<String>()

fun report(ok: Boolean?, name: String, detail: Any?) {
    val mark = when (ok) { true -> "OK  "; false -> "FAIL"; null -> "SKIP" }
    val line = "PC $mark $name -> $detail"
    results += line
    echo(chatText(line, if (ok == true) Color.GREEN else if (ok == false) Color.RED else 0xAAAAAA))
}

fun check(name: String, block: () -> Pair<Boolean, Any?>) {
    val result = runCatching(block)
    result.onSuccess { (ok, detail) -> report(ok, name, detail) }.onFailure { report(false, name, it) }
}

/**
 * Show [instruction] on screen and wait (up to [seconds]) until [done]; false if the player skipped it.
 * Titles and subtitles never wrap, so both stay short: [detail] goes below and the skip hint only in chat.
 */
fun ask(instruction: String, detail: String = "", seconds: Int = 30, done: () -> Boolean): Boolean {
    title(instruction, detail, fadeInTicks = 0, stayTicks = seconds * 20, fadeOutTicks = 5)
    echo(chatText("» $instruction $detail".trim(), Color.YELLOW, bold = true) + chatText(" (or wait ${seconds}s to skip)", 0xAAAAAA))
    repeat(seconds * 10) {
        if (done()) {
            title("✔", "", fadeInTicks = 0, stayTicks = 15, fadeOutTicks = 5)
            playSound("minecraft:entity.experience_orb.pickup")
            sleep(800)
            return true
        }
        sleep(100)
    }
    return false
}

echo(chatText("Minekt check: follow the big text on screen. ", Color.CYAN, bold = true) + "Each step skips itself after a while.")
sleep(2000)

// ---- automatic --------------------------------------------------------------------------------------

check("environment") {
    val java = System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"
    true to "Minekt ${modVersion("minekt")}, Minecraft ${modVersion("minecraft")}, Fabric API ${modVersion("fabric-api")}, " +
        "Kotlin ${modVersion("fabric-language-kotlin")}, Java $java, ${System.getProperty("os.name")}"
}

val hudFrames = AtomicInteger()
val worldFrames = AtomicInteger()
val tickCount = AtomicInteger()
onHudRender { g ->
    hudFrames.incrementAndGet()
    g.rect(g.width - 54, g.height - 30, 50, 12, 0x90000000.toInt())
    g.text("Minekt ✓", g.width - 51, g.height - 28, Color.GREEN)
}
onWorldRender { w ->
    worldFrames.incrementAndGet()
    val p = w.cameraPos
    w.box(Vec3(p.x + 2, p.y - 1, p.z + 2), Vec3(p.x + 3, p.y, p.z + 3), Color.CYAN)
}
ClientTickEvents.END_CLIENT_TICK.register { tickCount.incrementAndGet() }
check("drawing every frame keeps up with the frame rate") {
    val h0 = hudFrames.get(); val w0 = worldFrames.get(); val t0 = tickCount.get()
    sleep(2000)
    val fps = game { it.fps }
    val hud = (hudFrames.get() - h0) / 2
    val world = (worldFrames.get() - w0) / 2
    val ticks = (tickCount.get() - t0) / 2
    (hud >= fps * 0.7 && world >= fps * 0.7 && ticks in 15..25) to "$fps fps: HUD drawn $hud/s, world $world/s; Fabric tick event $ticks/s"
}
@Volatile var heavy = false
onHudRender { g -> if (heavy) for (i in 0 until 500) g.rect(4 + i % 50, 100 + i / 50, 1, 1, Color.WHITE) }
check("a heavy-ish HUD handler (500 rectangles a frame) doesn't drop the frame rate much") {
    sleep(1000)
    val before = game { it.fps }
    heavy = true
    sleep(2500)
    val after = game { it.fps }
    heavy = false
    (after >= before * 0.6) to "$before fps before, $after fps with it"
}

// ---- real input ------------------------------------------------------------------------------------

val rawKeys = CopyOnWriteArrayList<RawKey>()
val rawMouse = CopyOnWriteArrayList<InputEvent>()
val moves = AtomicInteger()
onRawKey { if (!it.synthetic) rawKeys += it }
onRawMouse { if (!it.synthetic) rawMouse += it }
onMouseMove { if (!it.synthetic) moves.incrementAndGet() }

val presses = AtomicInteger()
val releases = AtomicInteger()
val key = keybind("Minekt check", default = "b")
key.onPress { presses.incrementAndGet() }
key.onRelease { releases.incrementAndGet() }

launch {
    // The guided part runs in a coroutine; keybind and input handlers run alongside it on the script thread.
    run {
        val startYaw = player.yaw
        val ok = ask("Move your mouse", "around a bit", 20) { moves.get() > 20 && Math.abs(player.yaw - startYaw) > 10 }
        report(if (ok) true else null, "real mouse movement turns the camera and reaches onMouseMove", "${moves.get()} moves, yaw ${"%.0f".format(startYaw)} → ${"%.0f".format(player.yaw)}")
    }
    run {
        val ok = ask("Tap B", "once", 20) { presses.get() >= 1 && releases.get() >= 1 }
        report(if (ok) true else null, "a script keybind sees one press and one release", "presses ${presses.get()}, releases ${releases.get()}")
    }
    run {
        val before = presses.get()
        val ok = ask("Hold B", "for 2 seconds, then let go", 25) { releases.get() >= 2 }
        val heldPresses = presses.get() - before
        report(if (ok) heldPresses == 1 else null, "holding a keybind is one press (no key repeat)", "$heldPresses press(es) while held")
    }
    run {
        val ok = ask("Press E", "to open your inventory, then close it", 25) {
            rawKeys.any { it.action == InputAction.PRESS && it.key == game { mc -> mc.options.keyInventory.saveString() } } &&
                game { it.gui.screen() } == null && rawKeys.size >= 2
        }
        report(if (ok) true else null, "onRawKey sees real key presses", rawKeys.takeLast(4).map { "${it.key.removePrefix("key.keyboard.")} ${it.action}" })
    }
    run {
        val slot = player.selectedSlot
        val ok = ask("Scroll", "your mouse wheel", 20) { rawMouse.any { it is RawScroll } }
        report(if (ok) true else null, "onRawMouse sees the real scroll wheel", "slot $slot → ${player.selectedSlot}")
    }
    run {
        val ok = ask("Left-click", "once, in the world", 20) { rawMouse.any { it is RawMouseButton && it.button == 0 } }
        report(if (ok) true else null, "onRawMouse sees a real left click", rawMouse.filterIsInstance<RawMouseButton>().take(2).map { "${it.button} ${it.action}" })
    }
    run {
        val clicked = java.util.concurrent.atomic.AtomicBoolean()
        echo(chatText("» Open chat (T) and click ", Color.YELLOW, bold = true) +
            chatText("[here]", Color.GREEN, bold = true, underlined = true, hover = "Runs code in \\prismcheck", click = ChatClick.run { clicked.set(true) }))
        val ok = ask("Press T", "to open chat and click [here]", 40) { clicked.get() }
        if (ok) game { mc -> mc.gui.screen()?.onClose() }
        report(if (ok) true else null, "clicking styled chat runs the script's code", if (ok) "clicked" else "not clicked")
    }
    run {
        val hits = CopyOnWriteArrayList<String>()
        onDamage { (amount, _, cause, attacker) -> hits += "$amount $cause ${attacker?.name}" }
        if (player.gameMode != "survival") report(null, "onDamage cause (needs survival)", "you're not in survival")
        else {
            val ok = ask("Fall 4+ blocks", "to take fall damage", 40) { hits.any { "minecraft:fall" in it } }
            report(if (ok) true else null, "onDamage names fall damage", hits.toList())
        }
    }

    // ---- results ---------------------------------------------------------------------------------
    val file = game { it.gameDirectory.toPath().resolve("minekt/prismcheck-result.txt") }
    java.nio.file.Files.writeString(file, results.joinToString("\n") + "\n")
    val failed = results.count { " FAIL " in it }
    val skipped = results.count { " SKIP " in it }
    title(if (failed == 0) "All done ✔" else "$failed failed", "$skipped skipped", fadeInTicks = 0, stayTicks = 80, fadeOutTicks = 10)
    echo(
        chatText("Done: ${results.size - failed - skipped} OK, $failed failed, $skipped skipped. ", Color.CYAN, bold = true),
        chatText("[copy results]", Color.GREEN, hover = "Copy all the result lines", click = ChatClick.copy(results.joinToString("\n"))),
        chatText("Also saved in minekt/prismcheck-result.txt", 0xAAAAAA),
    )
    stop()
}
