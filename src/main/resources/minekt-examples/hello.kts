// \hello [name]
val who = args.firstOrNull() ?: player.name
echo("Hello, $who! You're at ${player.position.toShortString()} in ${player.dimension}.")
