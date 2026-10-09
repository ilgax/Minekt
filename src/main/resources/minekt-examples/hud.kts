// \hud — live coordinates overlay. Turn it off with "Script key 1" (bind it in Options → Controls → Minekt)
// or with K.
onTick {
    val p = player
    hud(
        "XYZ: %.1f / %.1f / %.1f".format(p.position.x, p.position.y, p.position.z),
        "Facing: %.0f° / %.0f°".format(p.yaw, p.pitch),
        "Health: %.0f / %.0f".format(p.health, p.maxHealth),
    )
}
onBinding(1) { stop() } // player-chosen key, set in Controls
onKey("k") { stop() }    // fixed key, chosen by the script
