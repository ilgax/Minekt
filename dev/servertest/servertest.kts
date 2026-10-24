// Dedicated-server smoke test, run by `./gradlew serverSmokeTest` (or by hand from autorun.txt): prints
// OK/FAIL lines and DONE to the server log, then stops the server. The Gradle task reads those lines.
@file:OptIn(ExperimentalHooks::class, ExperimentalPackets::class)

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.network.protocol.game.ServerboundChatPacket
import net.minecraft.server.MinecraftServer

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo(if (ok) "OK $name" else "FAIL $name ${result.exceptionOrNull() ?: result.getOrNull()}")
}

/** Wait up to [ticks] for [done]; handlers run meanwhile. */
fun waitFor(ticks: Int = 100, done: () -> Boolean): Boolean {
    repeat(ticks) { if (done()) return true; waitTicks(1) }
    return done()
}

var stored by persistent { 0 }
val greeting by setting("hi", label = "Greeting")
try {
    var ticks = 0
    onTick { ticks++ }
    waitTicks(20)
    check("ticks") { ticks >= 10 }
    check("players") { players.isEmpty() }
    check("setBlock/blockAt") { setBlock(3, 100, 3, "minecraft:oak_log[axis=x]"); blockAt(BlockPos(3, 100, 3)).id == "minecraft:oak_log" }
    check("nether") { blockAt(BlockPos(0, 200, 0), "the_nether").id == "minecraft:air" }
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

    // ---- world ----
    runCommand("forceload add 0 0 16 16")
    check("blockAt") { blockAt(BlockPos(3, 100, 3)).let { it.id == "minecraft:oak_log" && it.properties["axis"] == "x" && it.biome.isNotEmpty() } }
    check("findBlocks") { findBlocks(BlockPos(3, 100, 3), 3) { it == "minecraft:oak_log" }.map { it.pos } == listOf(BlockPos(3, 100, 3)) }
    check("raycast") { (raycast(Vec3(3.5, 110.0, 3.5), Vec3(3.5, 90.0, 3.5), entities = false) as? RayHit.Block)?.id == "minecraft:oak_log" }
    check("time") { runCommand("time set 6000"); world().timeOfDay in 6000..6100 }
    check("weather") {
        runCommand("weather rain")
        val raining = world().isRaining
        runCommand("weather clear")
        raining && !world().isRaining
    }
    check("atDaytime") {
        var passed = false
        val timer = atDaytime(6010) { passed = true }
        runCommand("time set 6000")
        waitFor { passed }.also { timer.cancel() }
    }

    // ---- entities, damage and death ----
    check("spawn/entities/killEntity") {
        val cow = spawn("minecraft:cow", Vec3(2.5, 101.0, 2.5))
        val found = entity(cow.id)?.type == "minecraft:cow" && entity(cow.uuid)?.id == cow.id &&
            entities(type = "cow", near = Vec3(2.5, 101.0, 2.5), radius = 4.0).any { it.id == cow.id } &&
            entities(dimension = "the_nether", type = "cow").none { it.id == cow.id }
        found && killEntity(cow.id) && waitFor { entity(cow.id) == null }
    }
    check("entity events") {
        var spawned = false; var damaged = false; var died = false; var removed: String? = null
        val handles = listOf(
            onEntitySpawn { if (it.type == "minecraft:pig") spawned = true },
            onDamage { (entity, amount, _, cause) -> if (entity.type == "minecraft:pig" && amount > 0 && cause.isNotEmpty()) damaged = true },
            onDeath { if (it.entity.type == "minecraft:pig") died = true },
            onEntityRemoved { (entity, _, reason) -> if (entity.type == "minecraft:sheep") removed = reason },
        )
        val pig = spawn("minecraft:pig", Vec3(4.5, 101.0, 4.5))
        runCommand("damage ${pig.uuid} 2")
        killEntity(pig.id)
        val sheep = spawn("minecraft:sheep", Vec3(5.5, 101.0, 5.5))
        removeEntity(sheep.id)
        val ok = waitFor { spawned && damaged && died && removed != null }
        handles.forEach { it.remove() }
        ok && removed == "discarded"
    }

    // ---- inventories ----
    check("container") {
        setBlock(6, 100, 6, "minecraft:chest")
        val chest = container(BlockPos(6, 100, 6))!!
        for (slot in 0 until chest.size) chest.clearSlot(slot) // the world is kept between runs
        chest.setSlot(0, "minecraft:diamond_sword", name = "Excalibur", lore = listOf("Sharp"), enchantments = mapOf("sharpness" to 5), damage = 10)
        chest.give("minecraft:apple", 70)
        val sword = chest[0]!!
        val ok = sword.name == "Excalibur" && sword.lore == listOf("Sharp") && sword.enchantments["minecraft:sharpness"] == 5 &&
            sword.damage == 10 && chest.count("apple") == 70 && chest.take("minecraft:apple", 5) == 5 && chest.count("apple") == 65
        chest.clearSlot(0)
        ok && chest[0] == null && container(BlockPos(7, 100, 7)) == null
    }

    // ---- power tools ----
    check("hooks") {
        val hook = hookAfter<MinecraftServer>("getPlayerCount") { it.result = 42 }
        val hooked = server { it.playerCount }
        hook.remove()
        hooked == 42 && server { it.playerCount } == 0
    }
    check("fabric events") {
        var fabricTicks = 0
        ServerTickEvents.END_SERVER_TICK.register { fabricTicks++ }
        waitFor { fabricTicks >= 3 }
    }
    check("packet hooks") { onPacket<ServerboundChatPacket> { _, _ -> }.remove(); true }

    // ---- /mks settings, share, install ----
    check("mks settings") { "greeting = hello" in runCommand("mks settings servertest greeting hello") || waitFor { greeting == "hello" } }
    check("mks share") { runCommand("mks share servertest"); true }
    check("mks install") {
        val http = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/installed.kts") { exchange ->
            val body = "publish(\"installed\", \"ran\")".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        http.start()
        try {
            val file = java.io.File("minekt/installed.kts")
            file.delete()
            runCommand("mks install http://127.0.0.1:${http.address.port}/installed.kts")
            // It's downloaded and compiled in the background: confirm once it's waiting.
            val installed = waitFor(1200) { if (!file.exists()) runCommand("mks install confirm"); file.exists() }
            var ran = false
            val handle = subscribe<String>("installed", latest = true) { ran = true }
            runCommand("mks run installed")
            (installed && waitFor(1200) { ran }).also { handle.remove() }
        } finally {
            http.stop(0)
        }
    }
    echo("DONE servertest")
} finally {
    runCommand("stop")
}
