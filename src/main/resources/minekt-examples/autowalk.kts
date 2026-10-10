// \autowalk [seconds] — walk forward, sprinting, for a while (Ctrl-free AFK walking)
val seconds = args.firstOrNull()?.toLongOrNull() ?: 5
press(Key.SPRINT)
press(Key.FORWARD)
sleep(seconds * 1000)
release(Key.FORWARD)
release(Key.SPRINT)
echo("Walked for $seconds s, now at ${player.position}")
