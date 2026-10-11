// \coords — your position in chat, with buttons: copy it, put it in the chat box to share, or save it
// as a waypoint you can click to look at later.
// Shows: chatText, every kind of ChatClick, persistent values.

val saved by persistent(perWorld = true) { mutableMapOf<String, Vec3>() }
val p = player.position
val here = "%d %d %d".format(p.x.toInt(), p.y.toInt(), p.z.toInt())

fun lookAtSaved(name: String) {
    val spot = saved[name] ?: return
    lookAt(spot)
    echo(chatText("Looking at $name", Color.YELLOW), "(${spot.distanceTo(player.position).toInt()} blocks away)")
}

echo(
    chatText("$here ", Color.GREEN, bold = true),
    chatText("[copy]", Color.CYAN, hover = "Copy \"$here\"", click = ChatClick.copy(here)),
    chatText("[share]", Color.CYAN, hover = "Put it in the chat box", click = ChatClick.suggest("I'm at $here")),
    chatText("[save]", Color.CYAN, hover = "Save as a waypoint", click = ChatClick.run {
        val name = "spot ${saved.size + 1}"
        saved[name] = p
        save()
        echo(chatText("Saved ", 0xAAAAAA) + chatText(name, Color.YELLOW, click = ChatClick.run { lookAtSaved(name) }, hover = "Look at it"))
    }),
)
if (saved.isNotEmpty()) {
    echo(
        chatText("Saved: ", 0xAAAAAA),
        *saved.keys.map { name -> chatText("[$name]", Color.YELLOW, hover = "Look at $name", click = ChatClick.run { lookAtSaved(name) }) }.toTypedArray(),
    )
}

// Clicks run this script's code, so it stays running for a minute to answer them.
after(60.seconds) { stop() }
