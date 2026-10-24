// In-game test for Fabric API events from scripts (experimental): registered like in a mod, removed when
// the script ends. Singleplayer test world.
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("FE ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
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

fun chatLines(): List<String> = game { mc ->
    val field = net.minecraft.client.gui.components.ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<net.minecraft.client.multiplayer.chat.GuiMessage>).map { it.content().string }
}

var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name

@Volatile var ticks = 0
ClientTickEvents.END_CLIENT_TICK.register { mc -> if (mc.player != null) ticks++ }
check("ClientTickEvents.END_CLIENT_TICK.register runs every tick") {
    val before = ticks
    waitTicks(20)
    val counted = ticks - before
    (counted in 15..25) then "$counted ticks in 20"
}

ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay -> "x7hidden" !in message.string }
check("ALLOW_GAME returning false hides a server message") {
    server("tellraw $me \"x7hidden message\"")
    server("tellraw $me \"a public message\"")
    waitFor(5) { chatLines().any { "a public message" in it } }
    val lines = chatLines()
    ("a public message" in lines.joinToString() && lines.none { "x7hidden" in it }) then "the marked message hidden, the other shown"
}

// A second script registers a tick listener; once it's killed, the listener must be gone.
val dir = game { it.gameDirectory.toPath().resolve("minekt") }
java.nio.file.Files.writeString(
    dir.resolve("fabrichelper.kts"),
    """
    @file:OptIn(ExperimentalFabricEvents::class)
    net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register {
        System.setProperty("minekt.fabrichelper", ((System.getProperty("minekt.fabrichelper") ?: "0").toInt() + 1).toString())
    }
    onChat { }
    """.trimIndent(),
)
check("killing a script takes its listener out of the event") {
    System.clearProperty("minekt.fabrichelper")
    val helper = startScript("fabrichelper")
    waitFor(10) { (System.getProperty("minekt.fabrichelper") ?: "0").toInt() > 5 }
    val running = System.getProperty("minekt.fabrichelper").toInt()
    helper.kill()
    waitFor(5) { !helper.isRunning }
    waitTicks(5)
    val atKill = System.getProperty("minekt.fabrichelper").toInt()
    waitTicks(20)
    val after = System.getProperty("minekt.fabrichelper").toInt()
    (running > 5 && after == atKill) then "counted $running while running, $atKill at kill, $after a second later"
}

java.nio.file.Files.writeString(dir.resolve("fabricnoopt.kts"), "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register { }")
check("without the opt-in, the error says how to turn it on") {
    val run = startScript("fabricnoopt")
    val message = runCatching { run.await(kotlin.time.Duration.parse("20s")) }.exceptionOrNull()?.message.orEmpty()
    ("@file:OptIn(ExperimentalFabricEvents::class)" in message) then message
}

echo("FE DONE")
sleep(1000)
game { it.stop() }
