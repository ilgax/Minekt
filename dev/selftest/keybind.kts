// In-game test for script keybinds: Controls entries, real key presses (xdo-runner.sh), rebinding,
// removal and the remembered key. Singleplayer test world.
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import java.util.concurrent.atomic.AtomicInteger

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("KB ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
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

fun mapping(name: String): KeyMapping? = game { mc -> mc.options.keyMappings.firstOrNull { it.name == name } }

var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val dir = game { it.gameDirectory.toPath().resolve("minekt") }
dir.resolve("settings/keybinds.json").toFile().delete()

val presses = AtomicInteger()
val ping = keybind("Ping", default = "j")
ping.onPress { presses.incrementAndGet() }
val unbound = keybind("Nothing yet")

// Handlers run after the script body, so the rest happens in a coroutine.
launch {
    check("the keybinds are in Controls, in the Minekt category, with their defaults") {
        val m = mapping("Ping")
        (m != null && mapping("Nothing yet") != null && ping.key == "J" && unbound.key == "Not Bound" &&
            m.category == game { mc -> mc.options.keyMappings.first { it.name == "key.minekt.manager" }.category }) then "${ping.key}, ${unbound.key}"
    }
    check("keybind(name) again returns the same one") { (keybind("Ping").key == "J" && game { mc -> mc.options.keyMappings.count { it.name == "Ping" } } == 1) then "one entry" }
    check("a real key press reaches onPress") {
        xdo("key j")
        waitFor(5) { presses.get() == 1 } then "presses=${presses.get()}"
    }
    check("isDown while the key is held") {
        xdo("keydown j")
        val down = ping.isDown
        xdo("keyup j")
        waitFor(3) { !ping.isDown }
        down then "held: $down, released: ${ping.isDown}"
    }
    check("the player rebinds it (as Controls does), and the new key works") {
        game { mc ->
            mapping("Ping")!!.setKey(InputConstants.getKey("key.keyboard.k"))
            KeyMapping.resetMapping()
        }
        val before = presses.get()
        xdo("key k")
        waitFor(5) { presses.get() == before + 1 } then "${ping.key}, presses=${presses.get()}"
    }
    check("the chosen key is saved") {
        waitFor(5) { dir.resolve("settings/keybinds.json").toFile().let { it.exists() && "key.keyboard.k" in it.readText() } } then
            dir.resolve("settings/keybinds.json").toFile().readText().replace("\n", " ")
    }
    check("screenshot of Controls with the script keybinds") {
        game { mc -> mc.gui.setScreen(KeyBindsScreen(null, mc.options)) }
        waitTicks(5)
        game { mc ->
            // Scroll to the end, where the mod categories are.
            val screen = mc.gui.screen()!!
            val list = screen.children().first { it.javaClass.simpleName == "KeyBindsList" }
            val scroll = generateSequence<Class<*>>(list.javaClass) { it.superclass }.flatMap { it.declaredMethods.asSequence() }
            val max = scroll.first { it.name == "maxScrollAmount" && it.parameterCount == 0 }.apply { isAccessible = true }.invoke(list) as Number
            scroll.first { it.name == "setScrollAmount" && it.parameterCount == 1 }.apply { isAccessible = true }.invoke(list, max.toDouble())
        }
        waitTicks(5)
        val shot = screenshot("minekt-keybinds")
        game { mc -> mc.gui.setScreen(null) }
        shot
    }
    check("remove() takes it out of Controls and the key stops working") {
        ping.remove()
        val before = presses.get()
        xdo("key k")
        sleep(500)
        (mapping("Ping") == null && KeyMapping.get("Ping") == null && presses.get() == before) then "gone"
    }
    check("making it again brings back the player's key") {
        val again = keybind("Ping", default = "j")
        (again.key == "K") then again.key
    }
    check("a bad key name is explained") {
        runCatching { keybind("Bad", default = "notakey") }.exceptionOrNull()?.message.orEmpty().let { ("Unknown key 'notakey'" in it) then it }
    }

    echo("KB DONE")
    sleep(1000)
    game { it.stop() }
}
