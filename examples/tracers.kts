// \tracers — lines from your view to hostile mobs nearby, drawn every frame. Toggle them with Y
// (change it under "Toggle tracers" in Options → Controls → Minekt). Red: closer than 8 blocks.
// Shows: onWorldRender, entityPosition (smooth between ticks), keybind, setting, your own Vec3 math.

val range by setting(48, 8..128, label = "Range (blocks)")
val hostile = setOf(
    "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton", "minecraft:stray",
    "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider", "minecraft:enderman", "minecraft:witch",
    "minecraft:slime", "minecraft:phantom", "minecraft:pillager", "minecraft:vindicator", "minecraft:blaze",
)

operator fun Vec3.plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
operator fun Vec3.times(k: Double) = Vec3(x * k, y * k, z * k)

/** The direction you look in, from yaw and pitch. */
fun look(yaw: Float, pitch: Float): Vec3 {
    val y = Math.toRadians(yaw.toDouble())
    val p = Math.toRadians(pitch.toDouble())
    return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
}

var on = true
keybind("Toggle tracers", default = "y").onPress {
    on = !on
    actionBar(if (on) "Tracers on" else "Tracers off")
}

// Work out what to draw once per tick, on the script's thread; only draw every frame.
@Volatile var targets = emptyList<EntityInfo>()
@Volatile var facing = Vec3(0.0, 0.0, 1.0)
onTick {
    targets = if (on) entities(range).filter { it.type in hostile } else emptyList()
    player.let { facing = look(it.yaw, it.pitch) }
}

onWorldRender { w ->
    val start = w.cameraPos + facing * 0.5 // a little in front of your eyes, so the line is visible
    for (mob in targets) {
        val feet = w.entityPosition(mob.id) ?: continue
        val color = if (mob.distance < 8) Color.RED else Color.YELLOW
        w.line(start, feet + Vec3(0.0, 1.0, 0.0), color, width = 2f, throughWalls = true)
    }
}
