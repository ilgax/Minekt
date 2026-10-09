// \hud — live coordinates panel. Turn it off with "Script key 1" (bind it in Options → Controls → Minekt)
// or with K.
hudPanel(Anchor.TOP_LEFT) {
    title("Where am I")
    text { player.position.let { "XYZ: %.1f / %.1f / %.1f".format(it.x, it.y, it.z) } }
    text { "Facing: %.0f° / %.0f°".format(player.yaw, player.pitch) }
    bar("Health", Color.RED) { player.health / player.maxHealth.toDouble() }
}
onBinding(1) { stop() } // player-chosen key, set in Controls
onKey("k") { stop() }    // fixed key, chosen by the script
