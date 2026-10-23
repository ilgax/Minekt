// \glowmobs — outlines mobs around you through walls: hostile ones red, others green, players white,
// plus chests and barrels yellow. Updated every second. Stops with \kill.
// Shows: glow on entities, highlight on blocks, changing a glow's color, findBlocks.
@file:OptIn(ExperimentalVisuals::class)

val range by setting(32, 8..64, label = "Range (blocks)")
val hostile = setOf(
    "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton", "minecraft:stray",
    "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider", "minecraft:enderman", "minecraft:witch",
    "minecraft:slime", "minecraft:phantom", "minecraft:pillager", "minecraft:vindicator", "minecraft:blaze",
)

val mobGlows = HashMap<Int, Glow>()
val chests = HashMap<BlockPos, ShapeHandle>()

every(1.seconds) {
    val near = entities(range.toDouble()).filter { it.health != null }
    // Glows of mobs that left go; new ones get one; a mob that changed side changes color.
    val ids = near.map { it.id }.toSet()
    mobGlows.keys.filter { it !in ids }.forEach { mobGlows.remove(it)?.remove() }
    for (mob in near) {
        val color = when {
            mob.type == "minecraft:player" -> Color.WHITE
            mob.type in hostile -> Color.RED
            else -> Color.GREEN
        }
        val existing = mobGlows[mob.id]
        if (existing == null) mobGlows[mob.id] = glow(mob, color) else existing.color = color
    }

    val containers = findBlocks(minOf(range, 16)) { it == "minecraft:chest" || it == "minecraft:barrel" || it == "minecraft:trapped_chest" }
        .map { it.pos }.toSet()
    chests.keys.filter { it !in containers }.forEach { chests.remove(it)?.remove() }
    for (pos in containers) chests.getOrPut(pos) { highlight(pos, Color.YELLOW, fill = false) }
}
