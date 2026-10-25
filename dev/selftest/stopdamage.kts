// In-game test for onDamage's cause and attacker (real damage from the server) and onStop after \kill.
// Singleplayer test world.
import java.util.concurrent.CopyOnWriteArrayList

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("SD ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
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

var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode survival $me")
server("difficulty easy")
server("effect clear $me")
server("fill -6 199 -6 6 199 16 minecraft:stone")
server("fill -6 200 -6 6 204 16 minecraft:air")
server("tp $me 0.5 200 0.5 0 0")
server("summon minecraft:zombie 0.5 200 4 {NoAI:1b,PersistenceRequired:1b,Tags:[\"sdtest\",\"sdzombie\"]}")
server("summon minecraft:skeleton 3 200 6 {NoAI:1b,PersistenceRequired:1b,Tags:[\"sdtest\",\"sdskeleton\"]}")
server("summon minecraft:arrow 2 201 4 {NoGravity:1b,Tags:[\"sdtest\",\"sdarrow\"]}")
waitTicks(20)

val hits = CopyOnWriteArrayList<String>()
onDamage { (amount, _, cause, attacker) -> hits += "$amount $cause ${attacker?.type}" }
val projectiles = CopyOnWriteArrayList<String?>()
onEvent { if (it is MinektEvent.Damage) projectiles += it.projectile?.type }

launch {
    check("onDamage: fall damage has its cause and no attacker") {
        server("effect give $me minecraft:instant_health 1 5")
        waitTicks(20)
        hits.clear()
        server("damage $me 2 minecraft:fall")
        waitFor(5) { hits.isNotEmpty() }
        (hits.firstOrNull() == "2.0 minecraft:fall null") then hits.toList()
    }
    check("onDamage: a zombie's hit names the zombie") {
        waitTicks(20)
        hits.clear()
        server("damage $me 3 minecraft:mob_attack by @e[tag=sdzombie,limit=1]")
        waitFor(5) { hits.isNotEmpty() }
        // Mob damage is scaled by difficulty (3 → 2.5 on easy), so check what and who, not how much.
        (hits.firstOrNull()?.endsWith(" minecraft:mob_attack minecraft:zombie") == true) then hits.toList()
    }
    check("onDamage: an arrow names the skeleton, and the arrow as the projectile") {
        waitTicks(20)
        hits.clear()
        projectiles.clear()
        server("damage $me 2 minecraft:arrow by @e[tag=sdarrow,limit=1] from @e[tag=sdskeleton,limit=1]")
        waitFor(5) { hits.isNotEmpty() && projectiles.isNotEmpty() }
        (hits.firstOrNull() == "2.0 minecraft:arrow minecraft:skeleton" && projectiles.firstOrNull() == "minecraft:arrow") then
            "${hits.toList()}, projectile ${projectiles.toList()}"
    }
    server("kill @e[tag=sdtest]")
    server("effect give $me minecraft:instant_health 1 5")
    server("difficulty peaceful")

    // A script that turns full-bright on and puts the old brightness back in onStop.
    val dir = game { it.gameDirectory.toPath().resolve("minekt") }
    java.nio.file.Files.writeString(
        dir.resolve("brightstop.kts"),
        """
        val before = game { mc -> mc.options.gamma().get() }
        game { mc -> mc.options.gamma().set(1.0) }
        onStop { reason ->
            sleep(200) // the API still works here, even after a kill
            game { mc -> mc.options.gamma().set(before) }
            echo("restored brightness after ${'$'}reason")
        }
        while (true) { }
        """.trimIndent(),
    )
    check("onStop runs after \\kill and puts a setting back") {
        game { mc -> mc.options.gamma().set(0.25) }
        val run = startScript("brightstop")
        waitFor(10) { game { mc -> mc.options.gamma().get() } == 1.0 }
        val on = game { mc -> mc.options.gamma().get() }
        run.kill()
        waitFor(10) { !run.isRunning }
        val after = game { mc -> mc.options.gamma().get() }
        (on == 1.0 && after == 0.25) then "gamma 1.0 while running, $after after the kill"
    }
    game { mc -> mc.options.gamma().set(0.5) }

    echo("SD DONE")
    sleep(1000)
    game { it.stop() }
}
