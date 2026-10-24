// \mksevents — server scripts in this singleplayer world, run with /mks: onEvent<ServerEvent.Chat> sees the
// player's chat, and a script with only a Fabric listener keeps running until /mks kill. The integrated server
// runs in this JVM, so the server scripts report through system properties. Singleplayer test world (with cheats).
fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("MKSEV ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

fun waitFor(ticks: Int, condition: () -> Boolean): Boolean {
    repeat(ticks) { if (condition()) return true; waitTicks(1) }
    return condition()
}

fun serverTicks() = (System.getProperty("minekt.serverticks") ?: "0").toInt()

waitTicks(60)
val serverScripts = game { mc ->
    mc.singleplayerServer!!.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("minekt").toFile()
}.apply { mkdirs() }
java.io.File(serverScripts, "chatwatch.kts").writeText(
    """onEvent<ServerEvent.Chat> { (player, message) -> System.setProperty("minekt.chatseen", player + ": " + message) }""",
)
java.io.File(serverScripts, "ticklistener.kts").writeText(
    """
    net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register {
        System.setProperty("minekt.serverticks", ((System.getProperty("minekt.serverticks") ?: "0").toInt() + 1).toString())
    }
    """.trimIndent(),
)
System.clearProperty("minekt.chatseen")
System.clearProperty("minekt.serverticks")

chat("/mks run chatwatch")
chat("/mks run ticklistener")
check("the listener-only script starts counting server ticks") { waitFor(600) { serverTicks() > 0 } then serverTicks() }
check("onEvent<ServerEvent.Chat> sees the player's chat") {
    waitTicks(100) // chatwatch compiles in the meantime
    chat("hello from the client")
    waitFor(100) { System.getProperty("minekt.chatseen")?.endsWith(": hello from the client") == true } then System.getProperty("minekt.chatseen")
}
check("the listener-only script keeps running") {
    val before = serverTicks()
    waitTicks(60)
    (serverTicks() - before >= 40) then "${serverTicks() - before} ticks counted in 60"
}
check("/mks kill stops it and takes its listener out") {
    chat("/mks kill all")
    waitTicks(20)
    val atKill = serverTicks()
    waitTicks(60)
    (serverTicks() == atKill) then "stayed at $atKill"
}

echo("MKSEV DONE")
sleep(1000)
game { it.stop() }

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else false
