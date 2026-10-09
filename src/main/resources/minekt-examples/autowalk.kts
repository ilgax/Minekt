// \autowalk [seconds] — walk forward, sprinting, for a while (Ctrl-free AFK walking)
val seconds = args.firstOrNull()?.toLongOrNull() ?: 5
press(Key.SPRINT)
hold(Key.FORWARD, seconds * 1000)
echo("Walked for $seconds s, now at ${player.position}")
