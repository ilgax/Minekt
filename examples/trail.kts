// \trail [seconds] — leaves a fading breadcrumb trail behind you, so you can find your way back.
// Shows: onWorldRender lines and points, colors with alpha, a ring buffer in plain Kotlin.

val seconds = args.firstOrNull()?.toIntOrNull() ?: 120
val points = ArrayDeque<Vec3>() // only touched by the timer and the keybind, both on the script's thread
@Volatile var snapshot = emptyList<Vec3>()

// Drop a point every half second if you moved at least a block.
everyTicks(10) {
    val here = player.position
    if (points.lastOrNull()?.let { it.distanceTo(here) < 1.0 } != true) points.addLast(here)
    while (points.size > seconds * 2) points.removeFirst()
    snapshot = points.toList()
}

onWorldRender { w ->
    val trail = snapshot
    for (i in 1 until trail.size) {
        val age = i.toDouble() / trail.size // 0 old … 1 new
        val alpha = (40 + 215 * age).toInt()
        val color = Color.rgb(64, (128 + 127 * age).toInt(), 255, alpha)
        val a = trail[i - 1]
        val b = trail[i]
        w.line(Vec3(a.x, a.y + 0.1, a.z), Vec3(b.x, b.y + 0.1, b.z), color, width = 3f)
    }
    trail.firstOrNull()?.let { w.point(Vec3(it.x, it.y + 0.1, it.z), Color.ORANGE, size = 10f) }
}
keybind("Clear trail").onPress { points.clear(); snapshot = emptyList() }
echo("Trail on for the last $seconds s of walking. \\kill trail to stop.")
