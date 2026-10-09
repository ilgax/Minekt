// \deathpoint — remembers where you die and points you back after respawning.
// Shows: game events, coroutines, world rendering, persistent values, toasts and sounds.
var lastDeath by persistent<Vec3?>(perWorld = true) { null } // survives restarts
var marker: List<ShapeHandle> = emptyList()

onDeath {
    lastDeath = position
    echo("Died at ${position.blockPos} in ${world.dimension}")
    toast("You died", "at ${position.blockPos}")
}
onRespawn {
    val spot = lastDeath ?: return@onRespawn
    marker.forEach { it.remove() }
    marker = listOf(
        highlight(spot.blockPos, Color.RED),
        text(Vec3(spot.x, spot.y + 2, spot.z), "Your stuff", Color.RED, scale = 2f),
    )
    // Keep an arrow from you to the spot updated twice a second, in the background.
    launch {
        var arrow: ShapeHandle? = null
        while (position.distanceTo(spot) > 3) {
            arrow?.remove()
            val from = position
            arrow = arrow(Vec3(from.x, from.y + 1, from.z), Vec3(spot.x, spot.y + 1, spot.z), Color.YELLOW, 3f)
            awaitTicks(10)
        }
        arrow?.remove()
        marker.forEach { it.remove() }
        playSound("minecraft:entity.player.levelup")
        actionBar("Found it!")
    }
}
echo("Death point tracker running (\\kill $jobId to stop)")
