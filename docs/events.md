# Events

## Events and lifetime

A script ends when its body finishes, unless it registered handlers. In that case it stays alive and keeps
handling events until it calls `stop()` or you `\kill` it:

```kotlin
// \chatbot
onChat { msg -> if (msg.endsWith("ping")) echo("pong!") }
onKey("k") { stop() }
```

Game events work the same way:

```kotlin
onDamage { amount, health -> if (health < 6) echo("Low health!") }
onItemGained { id, count -> if ("diamond" in id) echo("+$count diamond") }
onScreenOpen { screen -> if (screen.isContainer) echo("Opened ${screen.title}") }
onEvent { event -> if (event is GameEvent.DimensionChange) echo("Now in ${event.to}") }
```

Damage, death, respawn, world and dimension changes, screens and item gains are detected by comparing the
player's state each tick. `onItemGained` fires whenever the count of an item in your inventory goes up, from
pickups, crafting or taking items out of chests.

## More events

```kotlin
onBlockChange { pos, old, new -> if (new == "minecraft:air" && "ore" in old) echo("$old mined at $pos") }
onEntityAppear { e -> if (e.type == "minecraft:creeper") toast("Creeper!", "${e.distance.toInt()} blocks away") }
onEntityGone { e, died -> if (died) echo("${e.name} died") }
onSound { id, pos, category -> if (id == "minecraft:entity.creeper.primed") title("RUN") }
onParticle { id, pos, count -> … }                     // particles the server sends
onPlayerJoin { name -> echo("$name joined") }          // tab list changes
onPlayerLeave { name -> echo("$name left") }
onScoreboardChange { sidebar -> hud(sidebar.title, *sidebar.lines.toTypedArray()) }
echo(tabList(), sidebar())                              // read them any time
```

- `onBlockChange` sees every block that turns into another block in loaded chunks: placed, broken, grown,
  burnt, from you or anyone else. Chunks loading don't count, and neither do changes within a block (a door
  opening).
- `onEntityAppear` fires when an entity comes into range (spawned, or walked into loaded chunks), once its
  name and health are known. `onEntityGone` says whether it `died`, or just despawned or went out of range.
- `onSound` hears what the game plays for you, with its category (`hostile`, `blocks`, `players`, `ui`, …).
  `onParticle` only sees particles the server sends (potions, `/particle`, many mob effects), not ones the
  client makes up itself (footsteps, block breaking).
- The tab list and the sidebar are checked twice a second.
- Each event type costs nothing until a script listens for it.

## Coroutines

Coroutines let one script do several things at once and wait for things without nested callbacks:

```kotlin
launch {                                   // runs alongside everything else
    while (true) { hud("HP ${player.health}"); awaitTicks(10) }
}
launch {
    val msg = awaitChat { "ready" in it }  // suspends until a matching message arrives
    echo("Got: $msg")
}
val reply = runBlocking { withTimeoutOrNull(5_000) { awaitChat() } }   // wait in the script body
```

- A script stays alive while any of its coroutines run; `\kill` cancels them all.
- They run on the script's own threads, so blocking calls (`game { }`, `sleep`, `breakBlock`) are fine inside.
- `stop()` or an error inside a coroutine ends the whole script.
- `await…` only sees things that happen after it starts waiting.
- `kotlinx.coroutines.*` is imported by default (`delay`, `withTimeout`, `async`, `coroutineScope`, ...).

## Keys

`keybind` makes a real keybind: it shows up in Options → Controls → Minekt, where the player can bind it to any
key, and conflicts with other bindings are flagged there.

```kotlin
val fly = keybind("Toggle fly", default = "f")   // default is optional: no key until the player picks one
fly.onPress { flying = !flying }                 // on the script's thread, every press
onTick { if (fly.isDown) … }                     // or check whether it's held
echo("Press ${fly.key} to fly")                  // the key it's bound to, as the player sees it: "F"
launch { fly.awaitPress(); echo("pressed") }
```

- Holding the key down repeats `onPress` at the keyboard's repeat rate, like vanilla keys (holding Q drops item
  after item). For a toggle that shouldn't repeat, check `isDown` in `onTick` and act when it changes.
- A keybind exists while its script runs (or until `remove()`), and `onPress` keeps the script alive. Calling
  `keybind` again with the same name returns the same keybind.
- The key the player picks is remembered for the next time the script makes that keybind, in
  `minekt/settings/keybinds.json` (Minecraft forgets keybinds it doesn't know at startup, so Minekt keeps them).
- Key names: `"f"`, `"f6"`, `"left.shift"`, `"space"`, `"mouse.middle"`, or the full `"key.keyboard.f"`.
- If another script already has a keybind with the same name, Controls shows yours as `Name (script)`.

`onKey("k") { }` watches a fixed key instead, without anything in Controls: handy for quick personal scripts.
It only fires when no screen is open.

## Commands

`command(...)` registers a real client-side `/` command, with Tab completion, for as long as the script runs:

```kotlin
command("mark", completions = listOf("home", "mine")) { args ->
    echo("marked ${args.firstOrNull() ?: "home"} at ${player.position}")
}
```

Words after the command arrive as `args` (`"quoted words"` stay together). While the script runs, its
command takes priority over a server command with the same name. When the script ends, the command
disappears and `/name` goes to the server again. Two scripts can't own the same name.

## Chat filters

```kotlin
interceptChat { text -> if ("[AD]" in text) null else text.replace(":)", "☺") }   // incoming: hide or rewrite
interceptSend { text -> text.replace("teh", "the") }                               // outgoing chat
```

Return the text to show/send, or `null` to drop it. Unlike other handlers these run **synchronously on the
game thread**, because they decide what happens to the message, so keep them fast. Rewritten incoming
messages lose their original formatting.

## Timers

```kotlin
every(5.seconds) { hud("HP ${player.health}") }
after(30.seconds) { echo("half a minute later") }
everyTicks(10) { … }
at("18:00") { toast("Dinner time") }               // your computer's clock, every day
atDaytime(13000) { echo("Night is falling") }       // in-game time, every Minecraft day
```

- `5.seconds`, `200.milliseconds`, `2.minutes` and `1.hours` work without imports.
- Timer actions run alongside the script body, one at a time (together with screen callbacks), so they can use
  the whole API. The script stays alive while it has timers; each returns a handle with `cancel()`.

## Messages between scripts

One script publishes, any number of others listen:

```kotlin
// stats.kts
data class Stats(val health: Float, val dimension: String)
onTick { publish("stats", Stats(player.health, player.dimension)) }

// statshud.kts
data class Stats(val health: Float, val dimension: String)
subscribe<Stats>("stats", latest = true) { hud("HP: ${it.health}", "In: ${it.dimension}") }
```

- Topics are any name you like. `subscribe` handlers run after the script body; the script stays alive while
  subscribed. `subscribe` returns a handle with `cancel()`.
- `latest = true` also delivers the topic's current value right away. `lastMessage<T>(topic)` reads it at any
  time, even if it was published before this script started.
- `awaitMessage<T>(topic)` waits for the next one inside a coroutine.
- Each script has its own classes, so declare the same data class in both scripts (or use lists, maps,
  strings, numbers, Minekt's types). When the types differ, the value is copied across by its fields.
