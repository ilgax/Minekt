// \effects — your potion effects in the top right corner, with time left and a bar that runs down.
// Shows: onHudRender, player.effects, item icons.

val icons = mapOf(
    "minecraft:speed" to "minecraft:sugar",
    "minecraft:strength" to "minecraft:blaze_powder",
    "minecraft:regeneration" to "minecraft:ghast_tear",
    "minecraft:fire_resistance" to "minecraft:magma_cream",
    "minecraft:night_vision" to "minecraft:golden_carrot",
    "minecraft:water_breathing" to "minecraft:pufferfish",
    "minecraft:invisibility" to "minecraft:fermented_spider_eye",
    "minecraft:poison" to "minecraft:spider_eye",
)

@Volatile var effects = emptyList<EffectInfo>()
// Remember the longest each effect has had, so the bar shows how much is left.
val longest = java.util.concurrent.ConcurrentHashMap<String, Int>()
onTick {
    effects = player.effects.sortedBy { it.id }
    for (e in effects) if (e.duration > (longest[e.id] ?: 0)) longest[e.id] = e.duration
    longest.keys.retainAll(effects.map { it.id }.toSet())
}

fun time(ticks: Int) = if (ticks < 0) "∞" else "%d:%02d".format(ticks / 20 / 60, ticks / 20 % 60)

fun roman(level: Int) = listOf("I", "II", "III", "IV", "V").getOrElse(level) { "${level + 1}" }

onHudRender { g ->
    var y = 4
    for (e in effects) {
        val name = e.id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() } + " " + roman(e.amplifier)
        val line = "$name  ${time(e.duration)}"
        val x = g.width - g.textWidth(line) - 24
        g.rect(x - 2, y - 2, g.textWidth(line) + 24, 20, 0x90000000.toInt())
        icons[e.id]?.let { g.item(it, x, y) }
        g.text(line, x + 18, y + 4, if (e.duration in 0..200) Color.RED else Color.WHITE)
        val full = longest[e.id] ?: e.duration
        if (e.duration >= 0 && full > 0) g.rect(x + 18, y + 14, (g.textWidth(line) * e.duration / full), 1, Color.CYAN)
        y += 22
    }
}
