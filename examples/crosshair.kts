// \crosshair — a custom crosshair: a dot that turns red when you're looking at a mob, with its name
// and health underneath, and your speed in blocks per second.
// Shows: onHudRender, the game's own hit result in game { }, player.velocity.

import net.minecraft.world.phys.EntityHitResult

@Volatile var target: EntityInfo? = null
@Volatile var speed = 0.0
onTick {
    target = game { mc -> (mc.hitResult as? EntityHitResult)?.entity?.id }?.let { entity(it) }
    val v = player.velocity
    speed = Math.hypot(v.x, v.z) * 20 // blocks per tick → per second
}

onHudRender { g ->
    val cx = g.width / 2
    val cy = g.height / 2
    val mob = target
    val color = if (mob != null) Color.RED else Color.WHITE
    g.rect(cx - 1, cy - 1, 3, 3, color)
    g.outline(cx - 3, cy - 3, 7, 7, 0x80000000.toInt())

    if (mob != null) {
        val health = mob.health?.let { " ${it.toInt()}/${mob.maxHealth?.toInt()} ♥" }.orEmpty()
        val label = (mob.customName ?: mob.name) + health
        g.text(label, cx - g.textWidth(label) / 2, cy + 8, Color.WHITE)
    }
    val speedText = "%.1f m/s".format(speed)
    g.text(speedText, cx - g.textWidth(speedText) / 2, cy + 20, 0xAAAAAA)
}
