// \hud — live coordinates panel. Turn it off with its key (bind "Hide coordinates" in Options → Controls → Minekt)
// or with K.
hudPanel(Anchor.TOP_LEFT) {
    title("Where am I")
    text { player.position.let { "XYZ: %.1f / %.1f / %.1f".format(it.x, it.y, it.z) } }
    text { "Facing: %.0f° / %.0f°".format(player.yaw, player.pitch) }
    bar("Health", Color.RED) { player.health / player.maxHealth.toDouble() }
}
keybind("Hide coordinates").onPress { stop() } // the player picks the key in Controls → Minekt
onKey("k") { stop() }    // fixed key, chosen by the script
