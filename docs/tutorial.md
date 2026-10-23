# Your first scripts

Six short scripts, each adding one idea. Save each as a file in `.minecraft/minekt/` and run it by typing its
name after a backslash in chat: `hello.kts` runs with `\hello`. Edit and run again: there's no need to restart
the game.

## 1. Say something

```kotlin
// hello.kts
echo("Hello, ${player.name}!")
echo("You're at ${player.position.toShortString()} in ${player.dimension}")
```

`echo` prints to your own chat; nobody else sees it. `player` is a snapshot of you: name, position, health, …

## 2. Use what you type after the name

```kotlin
// ores.kts: \ores 24
val radius = args.firstOrNull()?.toIntOrNull() ?: 16
val ores = findBlocks(radius) { it.endsWith("_ore") }
echo("${ores.size} ores within $radius blocks")
ores.groupBy { it.id }.forEach { (id, found) -> echo("  $id × ${found.size}") }
```

`args` holds the words after the script name. This is plain Kotlin: lists, `groupBy`, string templates, all of it.

## 3. Keep running and react

```kotlin
// lowhealth.kts
onDamage { (_, health) ->
    if (health < 6) {
        title("Low health!")
        playSound("minecraft:block.note_block.bell")
    }
}
echo("Watching your health. \\kill to stop.")
```

A script ends when its last line has run, unless it registered handlers like `onDamage`, `onChat` or `onTick`.
Then it keeps running until it calls `stop()` or you `\kill` it (`\jobs` lists what's running).

## 4. Your own key

```kotlin
// fullbright.kts
var on = false
val toggle = keybind("Toggle full bright", default = "b")
toggle.onPress {
    on = !on
    game { mc -> mc.options.gamma().set(if (on) 1.0 else 0.5) }
    actionBar(if (on) "Full bright on" else "Full bright off")
}
```

`keybind` adds an entry to Options → Controls → Minekt that the player can rebind. `game { mc -> … }` runs code on
the game's thread with the real `Minecraft` object: anything the game can do, a script can do from there.

## 5. Show things on screen

```kotlin
// compass.kts
hudPanel(Anchor.TOP_RIGHT) {
    title("Compass")
    text { "XYZ %.0f %.0f %.0f".format(player.position.x, player.position.y, player.position.z) }
    text { "Facing ${(((player.yaw % 360) + 360) % 360).toInt()}°" }
    bar("Health", Color.RED) { player.health / player.maxHealth.toDouble() }
}
keybind("Hide compass").onPress { stop() }
```

The parts in `{ }` are re-read every tick. The panel goes away when the script stops.

## 6. Settings and memory

```kotlin
// greeter.kts
var greeting by setting("Welcome back", label = "Greeting")
var times by persistent { 0 }

times++
echo("$greeting, ${player.name}! (run $times)")
onPlayerJoin { name -> echo("$greeting, $name!") }
```

`setting` values show up under the script's Settings button in the script manager (`\menu`), where the player
can change them without touching the code. `persistent` values are saved and come back next time, even after
restarting the game.

## Where next

- When something goes wrong, the chat says where; click it for the full log (`\log greeter`).
- [API overview](api.md) lists everything a script can call, and the topic pages explain each part.
- [More example scripts](../examples/README.md): tracers, a radar, a custom crosshair, clickable coordinates, …
- `\install <link>` installs scripts other people share; `\share <script>` shares yours.
