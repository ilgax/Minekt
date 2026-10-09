// \ores [radius] — list ores near you (only what the server has sent to your client)
val radius = args.firstOrNull()?.toIntOrNull() ?: 16
val ores = findBlocks(radius) { it.endsWith("_ore") }
if (ores.isEmpty()) echo("No ores within $radius blocks")
ores.groupBy { it.id }.forEach { (id, blocks) ->
    echo("${id.removePrefix("minecraft:")}: ${blocks.size}, nearest at ${blocks.first().pos}")
}
