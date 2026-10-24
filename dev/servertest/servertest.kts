// Dedicated-server smoke test, run by `./gradlew serverSmokeTest` (or by hand from autorun.txt): prints
// OK/FAIL lines and DONE to the server log, then stops the server. The Gradle task reads those lines.
fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo(if (ok) "OK $name" else "FAIL $name ${result.exceptionOrNull() ?: result.getOrNull()}")
}

var stored by persistent { 0 }
try {
    var ticks = 0
    onTick { ticks++ }
    waitTicks(20)
    check("ticks") { ticks >= 10 }
    check("players") { players.isEmpty() }
    check("setBlock/blockId") { setBlock(3, 100, 3, "minecraft:oak_log[axis=x]"); blockId(3, 100, 3) == "minecraft:oak_log" }
    check("nether") { blockId(0, 200, 0, "the_nether") == "minecraft:air" }
    check("runCommand") { "time" in runCommand("time query daytime").lowercase() }
    check("command") {
        val handle = command("greet", CommandPermission.OPS) { call -> call.reply("hi", call.sender) }
        // Brigadier is a game library: compiling this needs it on the scripts' classpath.
        val registered = server { it.commands.dispatcher.root.getChild("greet") != null }
        handle.remove()
        registered
    }
    check("persistent") { ++stored >= 1 }
    check("eval") { eval("1 + 2") == 3 }
    check("server thread") { server { it.isSameThread } }
    echo("DONE servertest")
} finally {
    runCommand("stop")
}
