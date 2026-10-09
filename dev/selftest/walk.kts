// In-game test for walkTo / walkToNearest / pathTo. Builds an obstacle course on a platform in the
// sky (so the terrain is known) and walks it for real. Singleplayer only (uses the built-in server).
import java.util.concurrent.CopyOnWriteArrayList

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("WALK ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

/** `condition then detail`: the detail either way, marked FALSE when the condition fails. */
infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail (now at ${player.position})"

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

var waited = 0
while (getBlock(player.position.blockPos.offset(dy = -1)) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)
val me = player.name
server("gamemode survival $me")
server("difficulty peaceful")

// The course: a stone platform 40 blocks up, x from -2 to +30 and z from -12 to +12 around B.
val start = player.position.blockPos
val B = BlockPos(start.x, start.y + 40, start.z)
val y = B.y
fun at(dx: Int, dy: Int, dz: Int) = "${B.x + dx} ${y + dy} ${B.z + dz}"
fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, block: String) = server("fill ${at(x1, y1, z1)} ${at(x2, y2, z2)} $block")
fill(-2, -1, -12, 30, -1, 12, "minecraft:stone")
fill(-2, 0, -12, 30, 6, 12, "minecraft:air")
server("tp $me ${B.x + 0.5} $y ${B.z + 0.5} -90 0")
waitTicks(40)

fun feet() = player.position
fun near(x: Int, z: Int, tolerance: Double = 0.8) = feet().let { Math.abs(it.x - (B.x + x + 0.5)) < tolerance && Math.abs(it.z - (B.z + z + 0.5)) < tolerance }

check("flat walk") {
    val r = walkTo(B.x + 8, y, B.z)
    (r.arrived && near(8, 0)) then "$r at ${feet()}"
}

check("around a wall") {
    fill(10, 0, -6, 10, 2, 6, "minecraft:stone_bricks") // too tall to jump, 13 wide
    val route = pathTo(B.x + 13, y, B.z) ?: return@check false
    val r = walkTo(B.x + 13, y, B.z, showPath = true)
    (r.arrived && near(13, 0) && route.none { it.x == B.x + 10 && it.z in (B.z - 6)..(B.z + 6) }) then "$r, ${route.size} steps"
}

check("jump up onto a ledge, then down again") {
    fill(16, 0, -3, 18, 0, 3, "minecraft:oak_planks") // a 1-high ledge
    val up = walkTo(B.x + 17, y + 1, B.z)
    val onTop = feet().y >= y + 1 - 0.01
    val down = walkTo(B.x + 20, y, B.z)
    (up.arrived && onTop && down.arrived && near(20, 0)) then "up: $up, down: $down"
}

check("detour around magma, never stepping on it") {
    fill(22, -1, -4, 22, -1, 4, "minecraft:magma_block")
    val trail = CopyOnWriteArrayList<BlockPos>()
    val recorder = launch { while (true) { trail += feet().blockPos; awaitTicks(1) } }
    val r = walkTo(B.x + 24, y, B.z)
    recorder.cancel()
    val stepped = trail.filter { it.x == B.x + 22 && it.z in (B.z - 4)..(B.z + 4) && it.y == y }
    (r.arrived && stepped.isEmpty()) then "$r, ${trail.size} ticks, never on magma"
}

check("swim across a moat") {
    fill(26, -3, -12, 27, -1, 12, "minecraft:water") // full width: has to swim
    val r = walkTo(B.x + 29, y, B.z)
    (r.arrived && near(29, 0)) then r
}

check("walk to a chest: stops next to it") {
    server("setblock ${at(25, 0, 8)} minecraft:chest")
    val chest = BlockPos(B.x + 25, y, B.z + 8)
    val r = walkTo(chest)
    val d = feet().distanceTo(Vec3(chest.x + 0.5, chest.y.toDouble(), chest.z + 0.5))
    (r.arrived && d <= 2.0) then "$r, %.2f blocks from the chest".format(d)
}

check("walkToNearest: stand on the gold block") {
    server("setblock ${at(5, -1, 9)} minecraft:gold_block")
    val r = walkToNearest { getBlock(it.offset(dy = -1)) == "minecraft:gold_block" }
    (r.arrived && getBlock(feet().blockPos.offset(dy = -1)) == "minecraft:gold_block") then r
}

check("a walled-in target has no path") {
    fill(1, 0, -10, 3, 2, -8, "minecraft:stone") // solid 3x3x3 box
    server("setblock ${at(2, 0, -9)} minecraft:air"); server("setblock ${at(2, 1, -9)} minecraft:air")
    val started = System.currentTimeMillis()
    val r = walkTo(B.x + 2, y, B.z - 9)
    (r.status == WalkStatus.NO_PATH) then "$r in ${System.currentTimeMillis() - started} ms"
}

check("screenshot with the route drawn") {
    val route = pathTo(B.x, y, B.z) ?: return@check false
    val lines = route.zipWithNext().map { (a, b) -> line(Vec3(a.x + 0.5, a.y + 0.1, a.z + 0.5), Vec3(b.x + 0.5, b.y + 0.1, b.z + 0.5), Color.CYAN) }
    setOrientation(90f, 35f)
    waitTicks(10)
    val shot = screenshot("minekt-walk")
    lines.forEach { it.remove() }
    shot
}

echo("WALK DONE")
sleep(1000)
game { it.stop() }
