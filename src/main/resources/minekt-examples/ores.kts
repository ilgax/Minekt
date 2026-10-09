// \ores [radius] [all] — list valuable ores near you (add "all" to include coal and copper) and highlight them through walls until you \kill the script
// (or press "Script key 1"). Only finds what the server has sent to your client.
val radius = args.firstOrNull()?.toIntOrNull() ?: 16
val colors = mapOf("diamond" to Color.CYAN, "gold" to Color.YELLOW, "iron" to Color.ORANGE, "redstone" to Color.RED, "emerald" to Color.GREEN)

val common = if ("all" in args) emptyList() else listOf("coal", "copper")
val ores = findBlocks(radius) { id -> id.endsWith("_ore") && common.none { it in id } }
if (ores.isEmpty()) {
    echo("No ores within $radius blocks")
    stop()
}
ores.groupBy { it.id }.forEach { (id, blocks) ->
    echo("${id.removePrefix("minecraft:")}: ${blocks.size}, nearest at ${blocks.first().pos}")
}
for (ore in ores) {
    val color = colors.entries.firstOrNull { it.key in ore.id }?.value ?: Color.WHITE
    highlight(ore.pos, color)
}
val nearest = ores.first()
text(Vec3(nearest.pos.x + 0.5, nearest.pos.y + 1.5, nearest.pos.z + 0.5), nearest.id.removePrefix("minecraft:"))
echo("Highlighting ${ores.size} ores — \\kill $jobId to clear")
onBinding(1) { stop() }
