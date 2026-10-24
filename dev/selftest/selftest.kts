@file:OptIn(ExperimentalBaritone::class)

@file:Import("lib/fmt.kts")

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false // a check returning false is a failure
    echo("SELFTEST ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

// Wait (up to ~60 s) for the chunks around the player to arrive.
var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
echo("SELFTEST loaded after ${waited / 20} s")
check("player") { "${player.name} at ${fmt(player.position)} in ${player.dimension}, hp ${player.health}" }
check("block below") { blockAt(BlockPos.containing(player.position).below()).id }
check("findBlocks") { findBlocks(4) { it != "minecraft:air" }.groupingBy { it.id }.eachCount().entries.take(4) }
check("entities") { entities(64).take(3).map { "${it.type}@%.0f".format(it.distance) } }
check("inventory") { inventory.size }
check("mods") { "fabric-api=${net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("fabric-api")} baritone=${baritone.isAvailable} kotlin=${net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fabric-language-kotlin").map { it.metadata.version.friendlyString }.orElse(null)}" }
check("raw game access") { game { mc -> mc.options.fov().get() } }
check("hud") { hudPanel { text("Minekt selftest"); text("running") }; "set" }
check("lookAt") { val p = player.position; lookAt(p.x + 10, p.y + 1.62, p.z); "yaw=%.0f pitch=%.0f".format(player.yaw, player.pitch) }
check("walk") {
    val before = player.position
    press("forward")
    sleep(1500)
    release("forward")
    "moved %.2f blocks".format(before.distanceTo(player.position))
}
check("ticks") { val t = System.currentTimeMillis(); waitTicks(20); "${System.currentTimeMillis() - t} ms for 20 ticks" }

// ---- Fabric hooks (hooktest.kts runs alongside as its own job) ----
check("hooktest's keybind is in Controls") { game { mc -> mc.options.keyMappings.any { it.name == "Hooktest key" } } }
game { mc ->
    // Bind it to J, as a player would in Controls.
    val mapping = mc.options.keyMappings.first { it.name == "Hooktest key" }
    mapping.setKey(com.mojang.blaze3d.platform.InputConstants.getKey("key.keyboard.j"))
    net.minecraft.client.KeyMapping.resetMapping()
}
check("keybind rebound") { game { mc -> mc.options.keyMappings.first { it.name == "Hooktest key" }.translatedKeyMessage.string } }
echo("SELFTEST PRESS KEYS NOW")
waitTicks(200) // the harness presses J for real via xdotool
check("command in tab-completion") { game { it.connection!!.commands.root.getChild("mkt") != null } }
chat("/mkt hello \"two words\"")
waitTicks(10)
chat("/mkt stop")
waitTicks(20)
check("command removed after script ends") { game { it.connection!!.commands.root.getChild("mkt") == null } }

// ---- world rendering: draw one of everything in front of the player, then the harness screenshots ----
val eye = player.position
setOrientation(-90f, 15f) // face +X, looking slightly down
val ahead = BlockPos.containing(eye).offset(5, 0, 0)
check("draw shapes") {
    highlight(ahead.below(), Color.GREEN)                                  // filled block on the ground
    highlight(ahead.offset(0, -4, 3), Color.MAGENTA)                        // buried: visible only through walls
    box(Vec3(eye.x + 4, eye.y, eye.z - 4.0), Vec3(eye.x + 6, eye.y + 2, eye.z - 2.0), Color.RED)  // outline box
    line(Vec3(eye.x + 3, eye.y + 0.5, eye.z - 3.0), Vec3(eye.x + 3, eye.y + 0.5, eye.z + 3.0), Color.YELLOW, 4f)
    arrow(Vec3(eye.x + 3, eye.y + 2.5, eye.z + 3.0), Vec3(eye.x + 7, eye.y + 2.5, eye.z + 3.0), Color.CYAN, 3f)
    text(Vec3(ahead.x + 0.5, ahead.y + 1.5, ahead.z + 0.5), "Minekt", Color.ORANGE, 2f)
    "6 shapes"
}
echo("SELFTEST SCREENSHOT NOW")
waitTicks(100)
check("clearShapes") { clearShapes(); "cleared" }
echo("SELFTEST DONE")
sleep(1000)
game { it.stop() }
