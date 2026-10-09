// \hud — live coordinates overlay; press K to toggle it off.
onTick {
    val p = player
    hud(
        "XYZ: %.1f / %.1f / %.1f".format(p.position.x, p.position.y, p.position.z),
        "Facing: %.0f° / %.0f°".format(p.yaw, p.pitch),
        "Health: %.0f / %.0f".format(p.health, p.maxHealth),
    )
}
onKey("k") { stop() }
