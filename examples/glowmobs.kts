// \glowmobs — outlines mobs around you through walls: hostile ones red, others green, players white,
// plus chests and barrels yellow. Updated every second. Stops with \kill.
// Shows: glow on entities and blocks, changing a glow's color, findBlocks.

val range by setting(32, 8..64, label = "Range (blocks)")
val hostile = setOf(
    "minecraft:zombie", "minecraft:husk", "minecraft:drowned", "minecraft:skeleton", "minecraft:stray",
    "minecraft:creeper", "minecraft:spider", "minecraft:cave_spider", "minecraft:enderman", "minecraft:witch",
    "minecraft:slime", "minecraft:phantom", "minecraft:pillager", "minecraft:vindicator", "minecraft:blaze",
)

val mobGlows = HashMap<Int, Glow>()
val blockGlows = HashMap<BlockPos, Glow>()

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
    blockGlows.keys.filter { it !in containers }.forEach { blockGlows.remove(it)?.remove() }
    for (pos in containers) blockGlows.getOrPut(pos) { glowBlock(pos, Color.YELLOW) }
}
