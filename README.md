# Minekt

**Kotlin scripting for Minecraft.** Like [Minescript](https://github.com/maxuser0/minescript), but scripts are
Kotlin (`.kts`) instead of Python. They run inside the game, with full type checking and direct access to
Minecraft and every installed mod.

> Proof of concept · Fabric · Minecraft 26.3 · Kotlin 2.4.20 · GPL-3.0-or-later

```kotlin
// .minecraft/minekt/ores.kts   →   type  \ores 24  in chat
val radius = args.firstOrNull()?.toIntOrNull() ?: 16
findBlocks(radius) { it.endsWith("_ore") }
    .groupBy { it.id }
    .forEach { (id, blocks) -> echo("$id: ${blocks.size}, nearest at ${blocks.first().pos}") }
```

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3, plus
   [Fabric API](https://modrinth.com/mod/fabric-api) and
   [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin).
2. Drop `minekt-<version>.jar` into `.minecraft/mods`.
3. Start the game. On first launch Minekt creates `.minecraft/minekt/` and fills it with example scripts.

## Chat commands

| Command | |
|---|---|
| `\<script> [args…]` | run `minekt/<script>.kts` (`"quoted args"` are grouped) |
| `\eval <code>` | run one line of Kotlin and print its result, e.g. `\eval player.position` |
| `\jobs` | list running scripts |
| `\kill <id\|all>` | stop scripts |
| `\scripts` | list available scripts |
| `\help` | show this list |

Lines starting with `\` never reach the server.

**Autorun:** each `\command` line in `minekt/autorun.txt` runs whenever you join a world (`#` starts a comment).

## Script API

Everything below is in scope in every script, no imports needed.

| Area | API |
|---|---|
| Output | `echo(vararg parts)`, `chat(msg)` (a leading `/` makes it a command), `execute(cmd)`, `hud(vararg lines)` |
| Control | `args`, `jobId`, `sleep(ms)`, `waitTicks(n)`, `stop()` |
| Player | `player` (name, position, yaw/pitch, health, dimension, selectedSlot), `position`, `setOrientation(yaw, pitch)`, `lookAt(x, y, z)`, `turnTo(yaw, pitch, ticks)`, `turnToward(x, y, z, ticks)` |
| Input | `press(Key.FORWARD)`, `release(key)`, `hold(key, ms)`; held keys are released when the script ends |
| Inventory & screens | `inventory()`, `heldItem()`, `offhandItem()`, `selectSlot(n)`, `screen`, `container()`, `clickSlot(i)`, `shiftClick(i)`, `swapWithHotbar(i, n)`, `dropSlot(i)`, `openInventory()`, `closeScreen()` |
| Targeting & interaction | `targetedBlock()`, `targetedEntity()`, `useItem()`, `useOn(pos, face)`, `placeBlock(pos)`, `breakBlock(pos)`, `attack(entityId)`, `interact(entityId)` |
| World | `world` (dimension, timeOfDay, gameTime, isDay, rain, thunder), `getBlock(x, y, z)`, `findBlocks(radius) { id -> … }`, `scanRegion(from, to) { id -> … }`, `isLoaded(x, z)`, `entities(radius)`, `players()` |
| Events | `onChat`, `onTick`, `onKey("k")`, `onBinding(1)`, `onDamage`, `onDeath`, `onRespawn`, `onJoinWorld`, `onLeaveWorld`, `onDimensionChange`, `onScreenOpen`, `onScreenClose`, `onChunkLoad`, `onItemGained`, `onEvent` |
| Chat filters | `interceptChat { text -> text or null }` (incoming), `interceptSend { … }` (outgoing) |
| Persistence | `val x by persistent { default }` (options `perWorld`, `store`, `key`), `save()` |
| Coroutines | `launch { }`, `async { }`, `awaitTicks(n)`, `awaitChat { }`, `awaitEvent { }`, `awaitEventOf<T>()`, `awaitScreen { }`, `awaitBinding(n)` (+ all of `kotlinx.coroutines`) |
| Commands | `command("name", completions) { args -> }` registers a real `/name` |
| World rendering | `highlight(pos)`, `box(min, max)`, `line(a, b)`, `arrow(a, b)`, `text(pos, "…")`, `clearShapes()` |
| Mods | `isModLoaded(id)`, `modVersion(id)`, `baritone.*` |
| Raw access | `game { mc -> … }` runs on the client thread with the live `Minecraft` instance |

Game state comes back as plain data (`Vec3`, `BlockPos`, `PlayerInfo`, `EntityInfo`, …). Those are copies, so
they're safe to keep and read from the script's thread.

### Events and lifetime

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

### Coroutines

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

### Inventory, containers and interaction

```kotlin
val target = targetedBlock() ?: stop()                 // what the crosshair is on
useOn(target.pos, target.face)                         // right-click it (opens a chest)
val chest = container()                                // slots of the open menu
chest.playerSlots.filter { it.id == "minecraft:cobblestone" }.forEach { shiftClick(it.index) }
closeScreen()

placeBlock(BlockPos(10, 64, 10))                       // places the held block against a neighbour
breakBlock(BlockPos(10, 64, 10))                       // mines it, taking as long as survival would
attack(targetedEntity()!!.id)
turnToward(0.5, 64.0, 0.5, ticks = 10)                 // smooth turn instead of a snap
```

Slot numbers are the open menu's indices, as listed by `container().slots`. Each slot says whether it
belongs to the player's inventory or to the container. The server applies its usual reach and permission
checks to every action.

### Chat filters

```kotlin
interceptChat { text -> if ("[AD]" in text) null else text.replace(":)", "☺") }   // incoming: hide or rewrite
interceptSend { text -> text.replace("teh", "the") }                               // outgoing chat
```

Return the text to show/send, or `null` to drop it. Unlike other handlers these run **synchronously on the
game thread**, because they decide what happens to the message, so keep them fast. Rewritten incoming
messages lose their original formatting.

### World info and large scans

```kotlin
if (!world.isDay) echo("Night falls (${world.timeOfDay})")
val diamonds = scanRegion(BlockPos(-128, -64, -128), BlockPos(128, 16, 128)) { it == "minecraft:deepslate_diamond_ore" }
```

`scanRegion` works on big areas without freezing the game. It skips unloaded chunks and all-air sections, and
spreads the work over several frames. In testing, a 96 × 384 × 96 region took about 2 s while the game kept
running.

### Keys: `onKey` or `onBinding`, your choice

| | `onKey("k") { }` | `onBinding(1) { }` |
|---|---|---|
| Who picks the key | the script author | the player, in Options → Controls → Minekt → "Script key 1–5" |
| Shows up in Controls | no | yes, and conflicts with other bindings are flagged there |
| Works in menus | no (only when no screen is open) | no (same as any game keybind) |
| Good for | personal scripts, quick hacks | scripts you share |

`bindingKey(1)` returns what the player bound (e.g. `"J"` or `"Not Bound"`), so a script can tell them which key to press.
Both can be used in the same script:

```kotlin
onBinding(1) { stop() } // whatever the player bound
onKey("k") { stop() }   // always K
```

### Commands

`command(...)` registers a real client-side `/` command, with Tab completion, for as long as the script runs:

```kotlin
command("mark", completions = listOf("home", "mine")) { args ->
    echo("marked ${args.firstOrNull() ?: "home"} at ${player.position}")
}
```

Words after the command arrive as `args` (`"quoted words"` stay together). While the script runs, its
command takes priority over a server command with the same name. When the script ends, the command
disappears and `/name` goes to the server again. Two scripts can't own the same name.

### Drawing in the world

Boxes, lines, arrows and floating text, drawn in the 3D world until removed or the script ends:

```kotlin
val ores = findBlocks(16) { it.endsWith("_ore") }
ores.forEach { highlight(it.pos, Color.CYAN) }                 // filled box around each block
val label = text(Vec3(0.5, 80.0, 0.5), "Spawn", Color.YELLOW, scale = 2f)
line(player.position, Vec3(0.5, 64.0, 0.5), Color.RED, width = 3f)
label.remove()                                                  // every call returns a handle
clearShapes()                                                   // or drop them all
```

- Every shape takes `color` (ARGB: `Color.RED`, `Color.rgb(r, g, b, a)`) and `throughWalls` (default `true`:
  drawn on top of terrain, so you can see it behind blocks). Boxes take `fill`; lines and arrows take `width`;
  text takes `scale`.
- Fills come out solid rather than see-through when drawn through walls (that's how the game's gizmo renderer
  works). Use `fill = false` for outlines only.
- A script that only draws ends straight away and takes its shapes with it. Keep it alive with a handler
  (`onBinding(1) { stop() }`) to leave them up until you're done (see `ores.kts`).
- Shapes use Minecraft's built-in gizmo renderer. They're redrawn every tick from data the script sets, so
  scripts never run code on the render thread.

### Remembering things between runs

```kotlin
val homes by persistent { mutableMapOf<String, Vec3>() }               // in-place changes are saved too
var runs by persistent { 0 }; runs++
val seen by persistent(perWorld = true) { mutableSetOf<String>() }     // one value per world/server
var motd by persistent(store = "shared") { "" }                         // shared between scripts
```

- The value is restored when the script starts, or created by the `{ default }` the first time.
- Saved to `.minecraft/minekt/data/<script>.json` (readable JSON). That happens when you assign a `var`, every
  few seconds for in-place changes, when the script ends, and when the game closes. Call `save()` to force it.
- Lists, sets, maps, strings, numbers, booleans, Minekt's types (`Vec3`, `BlockPos`, ...) and your own data
  classes all work, and come back as their declared types.
- `perWorld` keys singleplayer worlds by save folder and servers by address.
- If a saved value no longer fits the declared type (you changed it), the script warns and starts from the
  default. An unreadable file is moved aside to `<name>.json.broken`, not deleted.

### Sharing code

```kotlin
@file:Import("lib/util.kts")   // path relative to this script

echo(shout("hello"))
```

## Other mods (Baritone and anything else)

Scripts compile against **every loaded mod's jar**, so any mod's public API can be used directly:

```kotlin
import baritone.api.BaritoneAPI

game { BaritoneAPI.getProvider().primaryBaritone.commandManager.execute("mine diamond_ore") }
```

For Baritone there is also a built-in helper. It uses reflection, so it works with whatever Baritone version
is installed, and Minekt runs fine without it:

```kotlin
if (!baritone.isAvailable) stop()
baritone.goto(100, 64, -20)        // also: mine(…), follow(player), explore(), cancel(), command("…")
baritone.await()                   // block until Baritone is done
echo("arrived at ${player.position}")
```

Notes:
- **Only what the client has.** In multiplayer, server-side mods aren't reachable. In singleplayer the built-in
  server runs in the same process, so `game { it.singleplayerServer }` gets you there.
- **Missing mods are compile errors.** A script that imports a mod which isn't installed won't compile. Guard
  it with `isModLoaded(…)` plus reflection if it should run either way.
- **Threads.** Scripts run on their own thread. Touch the game or another mod only inside `game { }`.

## How it works

- `MinektScript` is a Kotlin [script definition](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).
  The `Minekt` API object is the script's implicit receiver.
- The Kotlin scripting host and embeddable compiler are bundled (jar-in-jar) and run in the game's JVM. A warm-up
  compile at startup means the first script doesn't pay for compiler start-up. Compiled scripts are cached by
  content, so editing a file and re-running it picks up the change without restarting the game.
- Each run is a **job** on its own thread. API calls marshal onto the client thread and check whether the
  job was killed, so loops that call the API can always be `\kill`ed.

## Known limitations (PoC)

- A loop that never calls the API (`while (true) {}`) can't be killed, because the JVM has no safe way to stop a thread.
- The jar is about 55 MB because it bundles the Kotlin compiler.
- No sandbox: scripts can do anything the game can. Only run scripts you trust.
- Client-only and Fabric-only for now.
- Some servers ban automation mods. Check a server's rules before using it there.

## Building

Requires JDK 25.

```sh
./gradlew build          # jar in build/libs/, runs the engine tests
./gradlew runClient      # dev client
```

`dev/selftest/` holds in-game self-tests. Copy them into `run/minekt/` and list them in `run/minekt/autorun.txt`,
then open a singleplayer world. Each one reports `OK`/`FAIL` lines in chat and closes the game.
- `\hooktest` then `\selftest`: core API, commands, keys and rendering. When it prints `PRESS KEYS NOW`, press
  J and K so the key hooks get exercised.
- `\persist`: run it in two separate launches. The first stores values; the second checks they survived the
  restart.
- `\features`: inventory and containers, interaction, events, chat filters, world info, scans and coroutines. It
  uses the singleplayer server to give items, deal damage and teleport.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
