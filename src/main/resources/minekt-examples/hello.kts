// \hello [name]
val who = args.firstOrNull() ?: player.name
echo("Hello, $who! You're at ${player.position} in ${player.dimension}.")
