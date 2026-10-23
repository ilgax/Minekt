// \baritone_mine [block] [count] — mine with Baritone, then report back. Needs Baritone installed.
@file:OptIn(ExperimentalBaritone::class)

if (!baritone.isAvailable) {
    echo("Baritone isn't installed")
    stop()
}
val block = args.getOrNull(0) ?: "diamond_ore"
val count = args.getOrNull(1) ?: ""
baritone.command("mine $block $count".trim())
echo("Mining $block…")
baritone.await()
echo("Baritone finished; inventory now has ${inventory.filter { it.id.contains(block.removeSuffix("_ore")) }.sumOf { it.count }} matching items")

// Full Baritone API is also available directly, e.g.:
//   import baritone.api.BaritoneAPI
//   game { BaritoneAPI.getProvider().primaryBaritone.settings.allowSprint.value = true }
