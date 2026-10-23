// \autowalk [seconds] — walk forward, sprinting, for a while (Ctrl-free AFK walking)
val seconds = args.firstOrNull()?.toLongOrNull() ?: 5
press("sprint")
press("forward")
sleep(seconds * 1000)
release("forward")
release("sprint")
echo("Walked for $seconds s, now at", player.position)
