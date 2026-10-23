// \preview [block] [size] — shows a square floor of [block] (glass by default, 5 × 5) where you look,
// only on your screen, to try out a build before placing it. P moves it to where you look now,
// O swaps in the block in your hand. Stops with \kill, and the real blocks come back.
// Shows: fakeBlocks (many at once), targetedBlock, keybind.

var block = args.getOrNull(0)?.let { if (":" in it) it else "minecraft:$it" } ?: "minecraft:glass"
val size = args.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 32) ?: 5
var shown = emptyList<FakeBlock>()
var center: BlockPos? = null

fun show() {
    val at = center ?: return
    shown.forEach { it.remove() }
    val half = size / 2
    val plan = buildMap {
        for (dx in -half until size - half) for (dz in -half until size - half) put(at.offset(dx = dx, dz = dz), block)
    }
    shown = fakeBlocks(plan)
    actionBar("Preview: $size × $size of ${block.removePrefix("minecraft:")}")
}

fun moveHere() {
    val target = targetedBlock ?: return actionBar("Look at a block first")
    center = target.pos.offset(dy = 1)
    show()
}

keybind("Move preview", default = "p").onPress { moveHere() }
keybind("Preview held block", default = "o").onPress {
    val held = heldItem
    if (held == null) actionBar("Hold a block first")
    else {
        block = held.id
        runCatching { show() }.onFailure { actionBar("${held.id} isn't a block") }
    }
}
moveHere()
