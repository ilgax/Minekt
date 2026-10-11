// \mobradar — a round radar in the top left: you in the middle, facing up, with nearby mobs as dots
// (red hostile, green passive, white players). Toggle it with R ("Toggle radar" in Controls).
// Shows: onHudRender, entities() fields, a little trigonometry.

val radius by setting(32, 8..64, label = "Radar range (blocks)")
val hostile = setOf("minecraft:zombie", "minecraft:skeleton", "minecraft:creeper", "minecraft:spider", "minecraft:enderman", "minecraft:witch", "minecraft:drowned", "minecraft:husk", "minecraft:stray", "minecraft:slime", "minecraft:phantom")

@Volatile var mobs = emptyList<EntityInfo>()
@Volatile var me = player
var shown = true
keybind("Toggle radar", default = "r").onPress { shown = !shown }
onTick {
    me = player
    mobs = if (shown) entities(radius).filter { it.health != null } else emptyList()
}

onHudRender { g ->
    if (!shown) return@onHudRender
    val size = 64
    val cx = 4 + size / 2
    val cy = 4 + size / 2
    g.rect(4, 4, size, size, 0x90000000.toInt())
    g.outline(4, 4, size, size, 0x60FFFFFF)
    g.rect(cx - 1, cy - 1, 3, 3, Color.CYAN)
    val turn = Math.toRadians(me.yaw.toDouble())
    for (mob in mobs) {
        val dx = mob.position.x - me.position.x
        val dz = mob.position.z - me.position.z
        // Rotate so the way you face is up.
        val rx = -(dx * cos(turn) + dz * sin(turn))
        val ry = -(dz * cos(turn) - dx * sin(turn))
        val px = cx + (rx / radius * size / 2).toInt()
        val py = cy + (ry / radius * size / 2).toInt()
        if (px !in 5 until 3 + size || py !in 5 until 3 + size) continue
        val color = when {
            mob.type == "minecraft:player" -> Color.WHITE
            mob.type in hostile -> Color.RED
            else -> Color.GREEN
        }
        g.rect(px - 1, py - 1, 2, 2, color)
    }
}
