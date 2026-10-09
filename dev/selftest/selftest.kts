@file:Import("lib/fmt.kts")

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    echo("SELFTEST ${if (result.isSuccess) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

// Wait (up to ~60 s) for the chunks around the player to arrive.
var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
echo("SELFTEST loaded after ${waited / 20} s")
check("player") { "${player.name} at ${fmt(player.position)} in ${player.dimension}, hp ${player.health}" }
check("block below") { getBlock(player.position.blockPos.offset(dy = -1)) }
check("findBlocks") { findBlocks(4) { it != "minecraft:air" }.groupingBy { it.id }.eachCount().entries.take(4) }
check("entities") { entities(64).take(3).map { "${it.type}@%.0f".format(it.distance) } }
check("inventory") { inventory().size }
check("mods") { "fabric-api=${isModLoaded("fabric-api")} baritone=${baritone.isAvailable} kotlin=${modVersion("fabric-language-kotlin")}" }
check("raw game access") { game { mc -> mc.options.fov().get() } }
check("hud") { hud("Minekt selftest", "running"); "set" }
check("lookAt") { val p = player.position; lookAt(p.x + 10, p.y + 1.62, p.z); "yaw=%.0f pitch=%.0f".format(player.yaw, player.pitch) }
check("walk") {
    val before = player.position
    hold(Key.FORWARD, 1500)
    "moved %.2f blocks".format(before.distanceTo(player.position))
}
check("ticks") { val t = System.currentTimeMillis(); waitTicks(20); "${System.currentTimeMillis() - t} ms for 20 ticks" }
echo("SELFTEST DONE")
sleep(1000)
game { it.stop() }
