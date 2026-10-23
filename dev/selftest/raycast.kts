// In-game test for raycast: blocks, entities, see-through blocks, line of sight. Singleplayer test world.

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("RC ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

var waited = 0
while (blockId(player.position.blockPos.below()) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode survival $me")
server("fill -6 199 -6 6 199 16 minecraft:stone")
server("fill -6 200 -6 6 204 16 minecraft:air")
server("fill -2 200 6 2 203 6 minecraft:stone")               // a wall 6 blocks ahead
server("setblock 0 201 3 minecraft:cobweb")                   // see-through, at eye height
server("summon minecraft:armor_stand 0.5 200 2 {NoGravity:1b,Tags:[\"rctest\"]}")
server("tp $me 0.5 200 0.5 0 0")                              // facing +z, towards all of it
waitTicks(40)

val p = player
val eyes = p.eyePosition
val ahead = eyes + p.lookDirection * 20.0

check("player.eyePosition and lookDirection") {
    (Math.abs(eyes.y - 201.62) < 0.01 && p.lookDirection.z > 0.99 && Math.abs(p.lookDirection.length() - 1) < 1e-6) then "eyes $eyes, looking ${p.lookDirection}"
}
check("the nearest thing is the armor stand") {
    val hit = raycast(eyes, ahead)
    (hit is RayHit.Entity && hit.entity.type == "minecraft:armor_stand" && hit.distance in 1.0..2.0) then hit
}
check("entities = false: the cobweb, hit on its north face") {
    val hit = raycast(eyes, ahead, entities = false)
    (hit is RayHit.Block && hit.id == "minecraft:cobweb" && hit.face == Face.NORTH && hit.block == BlockPos(0, 201, 3)) then hit
}
check("solidOnly: through the cobweb to the wall") {
    val hit = raycast(eyes, ahead, entities = false, solidOnly = true)
    (hit is RayHit.Block && hit.id == "minecraft:stone" && hit.block.z == 6 && Math.abs(hit.distance - 5.5) < 0.01) then hit
}
check("blocks = false: only entities") {
    val hit = raycast(eyes, eyes + Vec3(0.0, -0.8, 1.0) * 10.0, blocks = false)
    (hit is RayHit.Entity) then hit
}
check("a clear line is null") {
    val hit = raycast(eyes, eyes + Vec3(0.0, 30.0, 0.0))
    (hit == null) then "nothing above"
}
check("line of sight: a point behind the wall can't see you") {
    val behind = Vec3(0.5, 201.5, 10.5)
    val blocked = raycast(behind, eyes, entities = false, solidOnly = true)
    val beside = Vec3(7.5, 201.5, 10.5) // the line passes the wall (x -2..3) at x ≈ 4.4
    val clear = raycast(beside, eyes, entities = false, solidOnly = true)
    if (blocked is RayHit.Block && clear == null) "blocked by ${(blocked as RayHit.Block).id} at ${blocked.block}, clear from the side" else "FALSE blocked=$blocked clear=$clear"
}
check("includeSelf: a line through you hits you") {
    val through = raycast(eyes + Vec3(-3.0, -0.5, 0.0), eyes + Vec3(3.0, -0.5, 0.0), blocks = false, includeSelf = true)
    val without = raycast(eyes + Vec3(-3.0, -0.5, 0.0), eyes + Vec3(3.0, -0.5, 0.0), blocks = false)
    (through is RayHit.Entity && through.entity.name == me && without == null) then "hit ${(through as RayHit.Entity).entity.name}"
}

server("kill @e[tag=rctest]")
server("fill -2 200 6 2 203 6 minecraft:air")
server("setblock 0 201 3 minecraft:air")
echo("RC DONE")
sleep(1000)
game { it.stop() }
