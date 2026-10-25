// \waypoints — a waypoint menu. Open it with J (change it under "Waypoints menu" in Options → Controls → Minekt).
// Waypoints are saved per world and drawn in the world. Other scripts can follow them on the
// "waypoints" topic: subscribe<Map<String, Vec3>>("waypoints", latest = true) { points -> … }
val points by persistent(perWorld = true) { mutableMapOf<String, Vec3>() }
var showInWorld by persistent { true }
var newName = "home"

val shapes = mutableListOf<ShapeHandle>()
fun changed() {
    shapes.forEach { it.remove() }
    shapes.clear()
    if (showInWorld) for ((name, pos) in points) {
        shapes += highlight(BlockPos.containing(pos), Color.CYAN)
        shapes += text(Vec3(pos.x, pos.y + 1.5, pos.z), name, Color.CYAN)
    }
    publish("waypoints", points.toMap())
}

lateinit var menu: ScriptScreen
fun openMenu() {
    menu = showScreen("Waypoints") {
        if (points.isEmpty()) text("No waypoints yet: name one below and add it")
        for ((name, pos) in points.toList()) row {
            button("$name  (%.0f, %.0f, %.0f)".format(pos.x, pos.y, pos.z), tooltip = "Look towards it") {
                menu.close()
                lookAt(pos.x, pos.y, pos.z)
            }
            button("Delete") {
                points.remove(name)
                changed()
                menu.refresh()
            }
        }
        textField("Name", ::newName, maxLength = 32)
        row {
            button("Add here") {
                points[newName.ifBlank { "waypoint ${points.size + 1}" }] = player.position
                changed()
                menu.refresh()
            }
            toggle("Show in world", showInWorld) {
                showInWorld = it
                changed()
            }
        }
    }
}

changed()
val menuKey = keybind("Waypoints menu", default = "j")
menuKey.onPress { openMenu() }
echo("Waypoints: press ${menuKey.key} for the menu (${points.size} saved here)")
