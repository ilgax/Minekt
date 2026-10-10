// \hud — live coordinates panel. Turn it off with its key (K, or whatever you pick for "Hide coordinates" in
// Options → Controls → Minekt).
hudPanel(Anchor.TOP_LEFT) {
    title("Where am I")
    text { player.position.let { "XYZ: %.1f / %.1f / %.1f".format(it.x, it.y, it.z) } }
    text { "Facing: %.0f° / %.0f°".format(player.yaw, player.pitch) }
    bar("Health", Color.RED) { player.health / player.maxHealth.toDouble() }
}
keybind("Hide coordinates", default = "k").onPress { stop() }
