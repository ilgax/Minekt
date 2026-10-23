// \minimap — a map of the ground around you in the top right, in the colors of real maps, with you
// as a white dot and north up. Redrawn once a second. Toggle it with M ("Toggle minimap" in Controls).
// Shows: pixels (a grid drawn on the HUD), reading the world through game { }, setting.
@file:OptIn(ExperimentalVisuals::class)

import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor

val size = 96 // blocks across, and pixels
val scale by setting(1, 1..3, label = "Size on screen")
val map = pixels(size, size)
var shown = true
keybind("Toggle minimap", default = "m").onPress { shown = !shown }

/** Read the top block of every column around the player, darker where the ground is lower than its neighbor to the north, like maps. */
fun redraw() = game { mc ->
    val level = mc.level ?: return@game
    val me = mc.player!!.blockPosition()
    val pos = BlockPos.MutableBlockPos()
    for (dx in 0 until size) {
        var northHeight = Int.MIN_VALUE
        for (dz in 0 until size) {
            val x = me.x - size / 2 + dx
            val z = me.z - size / 2 + dz
            val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
            pos.set(x, top, z)
            val color = level.getBlockState(pos).getMapColor(level, pos)
            map.data[dz * size + dx] = if (color == MapColor.NONE) 0xFF000000.toInt() else {
                val brightness = when {
                    northHeight == Int.MIN_VALUE || top == northHeight -> MapColor.Brightness.NORMAL
                    top > northHeight -> MapColor.Brightness.HIGH
                    else -> MapColor.Brightness.LOW
                }
                color.calculateARGBColor(brightness)
            }
            northHeight = top
        }
    }
    map[size / 2, size / 2] = Color.WHITE
    map.changed()
}

every(1.seconds) { if (shown) redraw() }
redraw()

onHudRender { g ->
    if (!shown) return@onHudRender
    val side = size * scale
    val x = g.width - side - 4
    g.rect(x - 1, 3, side + 2, side + 2, 0xA0000000.toInt())
    g.pixels(map, x, 4, side, side)
    g.text("N", x + side / 2 - 2, 6, Color.WHITE)
}
