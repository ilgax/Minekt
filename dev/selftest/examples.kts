// In-game smoke test for the scripts in examples/: each one starts without errors (and, for the ones that
// keep running, is still running a few seconds later). Then a screenshot with the drawing ones on.
// Singleplayer test world; run from the repository's dev client (it reads ../examples).
import java.nio.file.Files

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("EX ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

var waited = 0
while (blockId(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode survival $me")
server("difficulty easy")
server("time set 18000")
server("fill -6 199 -6 6 199 16 minecraft:stone")
server("fill -6 200 -6 6 204 16 minecraft:air")
server("tp $me 0.5 200 0.5 0 10")
server("effect give $me minecraft:speed 120 1")
server("effect give $me minecraft:night_vision 60 0")
server("summon minecraft:zombie 3 200 8 {NoAI:1b,PersistenceRequired:1b,Tags:[\"extest\"],equipment:{head:{id:\"minecraft:iron_helmet\",count:1}}}")
server("summon minecraft:skeleton -3 200 10 {NoAI:1b,PersistenceRequired:1b,Tags:[\"extest\"],equipment:{head:{id:\"minecraft:iron_helmet\",count:1}}}")
server("summon minecraft:pig 0 200 5 {NoAI:1b,Tags:[\"extest\"]}")
server("give $me minecraft:bread 8")
waitTicks(40)

val scripts = game { it.gameDirectory.toPath().resolve("minekt") }
val examples = game { it.gameDirectory.toPath().toAbsolutePath().parent.resolve("examples") }
val names = Files.list(examples).use { files -> files.map { it.fileName.toString() }.filter { it.endsWith(".kts") }.map { it.removeSuffix(".kts") }.sorted().toList() }
for (name in names) Files.copy(examples.resolve("$name.kts"), scripts.resolve("$name.kts"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)

fun errorsOf(name: String): List<String> = game { mc ->
    val field = net.minecraft.client.gui.components.ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<net.minecraft.client.multiplayer.chat.GuiMessage>).map { it.content().string }.filter { it.startsWith("[$name") }
}

val running = mutableMapOf<String, ScriptRun>()
for (name in names) {
    check("\\$name starts without errors") {
        val run = startScript(name)
        sleep(4000)
        val errors = errorsOf(name)
        running[name] = run
        (errors.isEmpty()) then (if (run.isRunning) "running" else "finished") + errors.joinToString(prefix = " ")
    }
}

check("screenshot with the drawing examples running") { screenshot("minekt-examples") }
running.values.forEach { runCatching { it.kill() } }
server("kill @e[tag=extest]")
server("difficulty peaceful")
server("time set 6000")
echo("EX DONE")
sleep(1000)
game { it.stop() }
