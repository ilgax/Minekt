// \commands — adds real /commands (with Tab completion) while this script runs.
//   /coords                 → print your position
//   /mark <name>, /goto <name>  → remember a spot and look towards it
val marks by persistent(perWorld = true) { mutableMapOf<String, Vec3>() } // kept per world, across restarts

command("coords") { echo("You're at", player.position) }
command("mark") { args ->
    val name = args.firstOrNull() ?: "home"
    marks[name] = player.position
    echo("Marked '$name' at ${marks[name]}")
}
command("goto", completions = listOf("home")) { args ->
    val target = marks[args.firstOrNull() ?: "home"] ?: return@command echo("No such mark")
    lookAt(target)
    echo("%.0f blocks that way".format(player.position.distanceTo(target)))
}
echo("Commands ready: /coords, /mark, /goto (\\kill $jobId to remove them)")
