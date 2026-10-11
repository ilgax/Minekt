// \fpsgraph — a small graph of your frame rate over the last few seconds, bottom left.
// Shows: onHudRender drawing your own chart from rectangles, game { } for live values.

val history = IntArray(100)
var next = 0
everyTicks(2) {
    history[next] = game { it.fps }
    next = (next + 1) % history.size
}

onHudRender { g ->
    val w = history.size
    val h = 40
    val x0 = 4
    val y0 = g.height - h - 50
    val top = maxOf(60, history.max())
    g.rect(x0 - 2, y0 - 12, w + 4, h + 14, 0x90000000.toInt())
    for (i in 0 until w) {
        val fps = history[(next + i) % w]
        val bar = h * fps / top
        val color = when {
            fps >= 60 -> Color.GREEN
            fps >= 30 -> Color.YELLOW
            else -> Color.RED
        }
        g.rect(x0 + i, y0 + h - bar, 1, bar, color)
    }
    g.text("${history[(next + w - 1) % w]} fps", x0, y0 - 10, Color.WHITE)
}
