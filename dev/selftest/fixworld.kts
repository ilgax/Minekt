// \fixworld — puts the test world back in a known state after a test left it odd (dead, in the Nether,
// at night in survival): respawns, creative, peaceful, day, overworld spawn. Then closes the game.
fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}
sleep(3000)
if (game { it.player?.isDeadOrDying == true }) {
    game { it.player!!.respawn() }
    sleep(3000)
}
game { it.player!!.closeContainer() }
val me = player.name
server("difficulty peaceful")
server("gamemode creative $me")
server("time set 6000")
server("weather clear")
server("effect clear $me")
server("kill @e[type=!player]")
server("scoreboard objectives setdisplay sidebar")
server("execute in minecraft:overworld run tp $me 0.5 200 0.5")
sleep(3000)
echo("FIX DONE in ${world.dimension}, health ${player.health}")
sleep(1000)
game { it.stop() }
