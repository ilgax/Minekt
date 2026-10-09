// In-game test for inventory/containers, targeting, interaction, events, chat interception, world
// info, region scans and coroutines. Run in a singleplayer world: it uses the integrated server
// (raw game access) to give items, change game mode, deal damage and teleport.

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("FEATURES ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

/** Run a command on the integrated server with full permissions. */
fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

// Wait for terrain.
var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)

// Record every game event from the start, concurrently with the test body (coroutines).
val seen = java.util.concurrent.CopyOnWriteArrayList<GameEvent>()
val recorder = launch { while (true) seen += awaitEvent() }
waitTicks(2)
fun saw(predicate: (GameEvent) -> Boolean) = seen.any(predicate)

val me = player.name
server("gamemode creative $me")
server("give $me minecraft:stone 64")
server("give $me minecraft:chest 1")
waitTicks(20)

// ---- world info / scan ----
check("world") { world }
check("isLoaded here / far away") {
    val here = isLoaded(position.blockPos.x, position.blockPos.z)
    val far = isLoaded(100_000, 100_000)
    "here=$here far=$far".takeIf { here && !far } ?: "FALSE here=$here far=$far"
}
check("scanRegion 96x(full height)x96 for ores") {
    val p = position.blockPos
    val started = System.currentTimeMillis()
    val ores = scanRegion(BlockPos(p.x - 48, -64, p.z - 48), BlockPos(p.x + 48, 320, p.z + 48)) { it.endsWith("_ore") }
    "${ores.size} ores in ${System.currentTimeMillis() - started} ms"
}

// ---- inventory ----
check("inventory has the given items") { inventory().map { it.id }.containsAll(listOf("minecraft:stone", "minecraft:chest")) }
check("ItemGained event") { seen.filterIsInstance<GameEvent.ItemGained>().joinToString { "${it.id} x${it.count}" }.ifEmpty { false } }
check("heldItem") { selectSlot(inventory().first { it.id == "minecraft:stone" }.slot); heldItem() }

// ---- interaction: place, target, break ----
val base = position.blockPos
val spot = base.offset(dx = 2)
server("fill ${spot.x} ${spot.y} ${spot.z - 1} ${spot.x + 2} ${spot.y + 2} ${spot.z + 1} minecraft:air")
waitTicks(5)
check("placeBlock") { placeBlock(spot) && run { waitTicks(5); getBlock(spot) == "minecraft:stone" } }
check("turnToward + targetedBlock") {
    turnToward(spot.x + 0.5, spot.y + 0.5, spot.z + 0.5, ticks = 8)
    waitTicks(2)
    targetedBlock()?.let { "${it.id} at ${it.pos} face ${it.face}" } ?: false
}
check("breakBlock") { breakBlock(spot) && getBlock(spot) == "minecraft:air" }

// ---- containers ----
val chestPos = spot
check("place chest") {
    selectSlot(inventory().first { it.id == "minecraft:chest" }.slot)
    placeBlock(chestPos) && run { waitTicks(5); getBlock(chestPos) == "minecraft:chest" }
}
check("open chest + awaitScreen") {
    runBlocking {
        val opened = async { withTimeoutOrNull(3000) { awaitScreen { it.isContainer } } }
        delay(100)
        useOn(chestPos, Face.UP)
        opened.await() ?: false
    }
}
check("container() / shiftClick stone into chest") {
    waitTicks(5)
    val before = container()
    val stone = before.playerSlots.first { it.id == "minecraft:stone" }
    shiftClick(stone.index)
    waitTicks(5)
    val chest = container().containerSlots
    "${before.title}: ${before.containerSlots.size} chest slots, now holds ${chest.filter { it.id != null }.map { "${it.id} x${it.count}" }}"
        .takeIf { chest.any { it.id == "minecraft:stone" } } ?: false
}
check("closeScreen + events") {
    closeScreen()
    waitTicks(5)
    screen == null && saw { it is GameEvent.ScreenOpen } && saw { it is GameEvent.ScreenClose }
}

// ---- entities ----
check("targetedEntity + attack") {
    // A cleared pen with a floor to the south, so the pig can't suffocate and the chest isn't in the way.
    server("fill ${base.x - 1} ${base.y - 1} ${base.z + 2} ${base.x + 1} ${base.y - 1} ${base.z + 4} minecraft:stone")
    server("fill ${base.x - 1} ${base.y} ${base.z + 2} ${base.x + 1} ${base.y + 2} ${base.z + 4} minecraft:air")
    server("summon minecraft:pig ${base.x + 0.5} ${base.y} ${base.z + 3.5} {NoAI:1b}")
    waitTicks(10)
    val pig = entities(8.0).first { it.type == "minecraft:pig" }
    turnToward(pig.position.x, pig.position.y + 0.5, pig.position.z, ticks = 5)
    waitTicks(2)
    val target = targetedEntity()
    attack(pig.id)
    waitTicks(5)
    val after = entities(8.0).firstOrNull { it.id == pig.id }
    "targeted ${target?.type}, health ${pig.health} -> ${after?.health ?: "dead"}".takeIf { target?.id == pig.id }
        ?: "FALSE targeted=$target pig=$pig"
}

// ---- chat interception ----
interceptChat { if ("SECRET" in it) null else it.replace("hello", "HOWDY") }
interceptSend { it.replace("foo", "bar") }
check("interceptChat rewrites") {
    runBlocking {
        val got = async { withTimeoutOrNull(3000) { awaitChat { "there" in it } } }
        delay(100); server("tellraw @a \"hello there\"")
        got.await() ?: false
    }
}
check("interceptChat hides") {
    runBlocking {
        val got = async { withTimeoutOrNull(1500) { awaitChat { "SECRET" in it } } }
        delay(100); server("tellraw @a \"SECRET stuff\"")
        got.await() == null
    }
}
check("interceptSend rewrites what you send") {
    runBlocking {
        val got = async { withTimeoutOrNull(3000) { awaitChat { "test" in it } } }
        delay(100); chat("foo test")
        got.await() ?: false
    }
}

// ---- damage / death / respawn / dimension ----
server("gamemode survival $me")
waitTicks(10)
check("Damage event") {
    server("damage $me 4")
    runBlocking { withTimeoutOrNull(3000) { awaitEventOf<GameEvent.Damage>() } } ?: false
}
check("Death + Respawn events") {
    server("kill $me")
    runBlocking { withTimeoutOrNull(3000) { awaitEventOf<GameEvent.Death>() } } ?: return@check false
    game { it.player!!.respawn() }
    runBlocking { withTimeoutOrNull(5000) { awaitEventOf<GameEvent.Respawn>() } } ?: false
}
check("DimensionChange + ChunkLoad events") {
    server("execute in minecraft:the_nether run tp $me 0 100 0")
    val change = runBlocking { withTimeoutOrNull(15000) { awaitEventOf<GameEvent.DimensionChange>() } }
    waitTicks(40)
    "$change, ${seen.count { it is GameEvent.ChunkLoad }} chunk loads".takeIf { change != null } ?: false
}
check("world info in the nether") { world.dimension }

recorder.cancel()
echo("FEATURES DONE (${seen.size} events seen)")
sleep(1000)
game { it.stop() }
