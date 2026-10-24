// In-game test for raw input: listening and cancelling real keyboard and mouse input (xdo-runner.sh),
// and sending input that the game handles like the real thing. Singleplayer test world.
import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ChatScreen

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("IN ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun xdo(command: String) {
    echo("XDO $command")
    sleep(800)
}

fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return condition()
}

fun screenName() = game { it.gui.screen()?.javaClass?.simpleName }

var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)

val keys = CopyOnWriteArrayList<RawKey>()
val mouse = CopyOnWriteArrayList<InputEvent>()
@Volatile var blockE = false
@Volatile var freezeMouse = false
onRawKey { e ->
    keys += e
    if (blockE && e.key == "key.keyboard.e") e.cancel()
}
onRawMouse { mouse += it }
onMouseMove { if (freezeMouse) it.cancel() }

// Listeners run on the game thread from now on; the checks run in a coroutine so the body can finish.
launch {
    check("a real key press is seen, with its name") {
        xdo("key j")
        waitFor(5) { keys.any { it.key == "key.keyboard.j" && it.action == InputAction.PRESS && !it.synthetic } } then
            keys.filter { it.key == "key.keyboard.j" }.map { "${it.action}${if (it.synthetic) " synthetic" else ""}" }
    }
    check("tapKey(\"e\") opens the inventory like the real key") {
        tapKey("e")
        waitFor(5) { screenName() == "InventoryScreen" }.also { tapKey("escape"); waitFor(5) { screenName() == null } } then
            keys.filter { it.synthetic }.map { "${it.key} ${it.action}" }.take(2)
    }
    check("cancelling E in onRawKey stops the real key from opening the inventory") {
        blockE = true
        xdo("key e")
        val opened = waitFor(2) { screenName() != null }
        blockE = false
        (!opened && keys.any { it.key == "key.keyboard.e" && !it.synthetic }) then "inventory stayed closed"
    }
    check("typeText types into chat") {
        tapKey("t")
        waitFor(5) { screenName() == "ChatScreen" }
        typeText("hello from minekt")
        sleep(300)
        val typed = game { mc -> (mc.gui.screen() as? ChatScreen)?.children()?.filterIsInstance<EditBox>()?.firstOrNull()?.value }
        tapKey("escape")
        waitFor(5) { screenName() == null }
        (typed == "hello from minekt") then typed
    }
    check("scrollMouse moves the hotbar selection") {
        game { it.player!!.inventory.selectedSlot = 0 }
        waitTicks(2)
        scrollMouse(-1.0)
        waitFor(3) { player.selectedSlot == 1 } then "slot ${player.selectedSlot}"
    }
    check("a real mouse click is seen as button 0") {
        xdo("mousemove --window WIN 640 360 click 1")
        waitFor(5) { mouse.any { it is RawMouseButton && it.button == 0 && !it.synthetic } } then
            mouse.filterIsInstance<RawMouseButton>().map { "${it.button} ${it.action}" }.take(2)
    }
    check("moveMouse turns the camera") {
        setOrientation(0f, 0f)
        waitTicks(2)
        moveMouse(200.0, 0.0)
        waitFor(3) { Math.abs(player.yaw) > 1f } then "yaw ${player.yaw}"
    }
    check("cancelling mouse movement keeps the camera still") {
        setOrientation(0f, 0f)
        waitTicks(2)
        freezeMouse = true
        moveMouse(200.0, 0.0)
        waitTicks(5)
        val yaw = player.yaw
        freezeMouse = false
        (Math.abs(yaw) < 0.01f) then "yaw $yaw"
    }
    check("a bad key name is explained") {
        runCatching { tapKey("notakey") }.exceptionOrNull()?.message.orEmpty().let { ("Unknown key 'notakey'" in it) then it }
    }

    echo("IN DONE")
    sleep(1000)
    game { it.stop() }
}
