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
2. Drop one of the two Minekt jars into `.minecraft/mods`:
   - `minekt-<version>.jar` (about 3 MB) gets the Kotlin compiler on first launch (see below).
   - `minekt-<version>-full.jar` (about 56 MB) has the compiler built in: for offline use, or modpacks that
     don't allow downloads.
3. Start the game. On first launch Minekt creates `.minecraft/minekt/` and fills it with example scripts.

**The compiler (small jar only).** Scripts are compiled by the Kotlin compiler, which is most of Minekt's size,
so the small jar fetches it once while the game loads. It needs exactly the version Minekt was built with, and
only accepts a file whose SHA-256 matches the one recorded in the build. It looks in this order:
1. `.minecraft/minekt/lib/`
2. Gradle and Maven caches (`~/.gradle/caches`, `~/.m2`), in case you've built Kotlin projects before
3. Maven Central (or a mirror), saving the file into `minekt/lib/`

After that, nothing is downloaded again until a Minekt update changes the Kotlin version, so scripts work
offline. If there's no compiler and no connection, the game runs normally and scripts explain how to fix it:
connect once, or put `kotlin-compiler-embeddable-<version>.jar` from Maven Central into `minekt/lib/`. A toast
shows when a download starts and finishes.

## Chat commands

| Command | |
|---|---|
| `\<script> [args…]` | run `minekt/<script>.kts` (`"quoted args"` are grouped) |
| `\eval <code>` | run one line of Kotlin and print its result, e.g. `\eval player.position` |
| `\menu` | open the script manager (also a key you can bind in Controls → Minekt) |
| `\jobs` | list running scripts, with their CPU use |
| `\kill <id\|all>` | stop scripts |
| `\scripts` | list available scripts |
| `\install <url> [name]` | install a script from a link, after a preview (see below) |
| `\install clipboard [name]` | install a script someone shared with you |
| `\update <script>` | get a newer version from where it was installed from |
| `\share <script>` | copy a script to the clipboard to share it |
| `\record <name>` / `\record stop` | record your movement and clicks as a macro script (see below) |
| `\help` | show this list |

Lines starting with `\` never reach the server.

**Script manager:** `\menu`, or a key you bind under Controls → Minekt → Script manager, opens a screen with
the running scripts (CPU use and a Stop button each), every script with Run and Edit (opens it in your editor),
arguments, New script, Open folder, installing from a link, and the last few errors.

**Autorun:** each `\command` line in `minekt/autorun.txt` runs whenever you join a world (`#` starts a comment).

## Script API

Everything below is in scope in every script, no imports needed.

| Area | API |
|---|---|
| Output | `echo(vararg parts)`, `chat(msg)` (a leading `/` makes it a command), `execute(cmd)`, `hud(vararg lines)` |
| Feedback | `playSound(id)`, `toast(title, message)`, `actionBar(text)`, `title(title, subtitle)`, `screenshot(name)` |
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
| Screens | `showScreen(title) { text(…); toggle(…); slider(…); textField(…); choice(…); button(…) { }; row { } }` |
| Messages | `publish(topic, value)`, `subscribe<T>(topic) { }`, `awaitMessage<T>(topic)`, `lastMessage<T>(topic)` |
| Timers | `every(5.seconds) { }`, `after(30.seconds) { }`, `everyTicks(n) { }`, `afterTicks(n) { }`, `at("18:00") { }`, `atDaytime(13000) { }` |
| Packets | `onPacket<T> { }`, `onPacketSent<T> { }`, `interceptPacket<T> { it or null }`, `interceptPacketSent<T> { }`, `awaitPacket<T>()`, `sendPacket(p)` |
| Method hooks | `hook<Class>("method") { call -> }`, `hookAfter<Class>("method") { call -> }` (change `args`, `cancel(value)`, replace `result`) |
| Walking | `walkTo(x, y, z)`, `walkTo(pos)`, `walkToNearest { pos -> … }`, `pathTo(x, y, z)` |
| Libraries | `@file:DependsOn("group:artifact:version")`, `@file:Repository("https://…")` |
| Macros | `macro(step(ticks, "forward jump", yaw, pitch, slot), …)`; `\record` writes these for you |
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

### Screens

Scripts can open real Minecraft screens: settings menus, pickers, little dashboards.

```kotlin
var autoEat by persistent { true }
var range by persistent { 32 }
var mode by persistent { "walk" }

onBinding(1) {
    showScreen("Miner settings") {
        text("Changes apply right away")
        text { "Health: ${player.health}" }                 // live: recomputed every tick
        toggle("Auto eat", ::autoEat)
        slider("Range", 8..64, ::range)
        choice("Mode", listOf("walk", "sprint"), ::mode)
        row {
            button("Start") { baritone.mine("diamond_ore") }
            button("Stop", tooltip = "Stops Baritone") { baritone.cancel() }
        }
    }
}
```

- Widgets: `text`, `button`, `toggle`, `slider` (whole numbers, or decimals with a `step`), `textField`, `choice`,
  and `row { }` to put several side by side. A **Done** button is added unless `doneButton = false`.
- Give a widget a property (`::autoEat`) and it changes that property as the player clicks. Or pass a value and
  a callback: `slider("Zoom", 1..10, zoom) { zoom = it }`. Combined with `persistent`, that's a settings menu
  that remembers itself.
- Callbacks run on the script's side, one at a time and alongside the script body, so they can use the whole
  API (`game { }`, `sleep`, `baritone`, …).
- `showScreen` returns a handle: `close()`, `refresh()` (runs the block again, e.g. after a list changed),
  `isOpen`, `onClose { }`, `awaitClose()` (suspend) and `waitForClose()` (blocking).
- The script stays alive while its screen is open. The screen doesn't pause the game.
- Screens taller than the window scroll with the mouse wheel.
- The bundled `waypoints.kts` is a complete example: a per-world waypoint list with add/delete buttons that
  redraws itself with `refresh()` and publishes the waypoints for other scripts.

### Messages between scripts

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

### Timers

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

### Packets

Raw network packets, both directions. The classes are Minecraft's own (`net.minecraft.network.protocol.game.*`):

```kotlin
import net.minecraft.network.protocol.game.*

onPacket<ClientboundSetHealthPacket> { echo("health now ${it.health}") }
onPacketSent<ServerboundMovePlayerPacket> { moves++ }
interceptPacket<ClientboundSoundPacket> { null }                              // drop every sound
interceptPacket<ClientboundSystemChatPacket> { if ("spam" in it.content().string) null else it }
sendPacket(ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS))
val stats = runBlocking { withTimeoutOrNull(5_000) { awaitPacket<ClientboundAwardStatsPacket>() } }
```

- `onPacket…` listeners run on the script thread after the body, like other events. `onPacket<Packet<*>>` sees
  everything.
- `intercept…` filters run right away on the network thread, before the game sees the packet: return it, a
  replacement, or `null` to drop it. Keep them quick. A filter that throws is removed and the packet goes through.
- Bundled packets are taken apart, so filters and listeners see each packet inside a bundle.

### Hooking game methods

Run code before or after any method of Minecraft or another mod, even though the game is already running:

```kotlin
hook<LivingEntity>("jumpFromGround") { call -> if (call.self is LocalPlayer) echo("jump!") }
hook<LivingEntity>("jumpFromGround") { it.cancel() }                         // skip the method
hookAfter<LivingEntity>("getSpeed") { it.result = (it.result as Float) * 2 }  // change what it returns
hook<Hud>("setOverlayMessage", Component::class.java, Boolean::class.java) {   // overloaded: name the types
    it.args[0] = Component.literal("rewritten")
}
```

- `call.self` is the object (null for static methods), `call.args` the arguments (change them in `hook`),
  `call.cancel(value)` skips the method and returns `value`, and in `hookAfter`, `call.result` can be replaced.
  Numbers are converted to the method's types for you.
- Handlers run right then, on whichever thread called the method (usually the game thread or the integrated
  server's), so keep them quick. Calls a handler makes to hooked methods don't trigger hooks again.
- Only the class that declares the method is hooked, not subclasses that override it: hook the class that has
  the code. A class name works too: `hook("net.minecraft.client.player.LocalPlayer", "aiStep") { … }`.
- Hooks come off when the script ends (or `handle.remove()`), and the method's original code is put back. A
  handler that throws is removed.
- How: Minekt attaches a Java agent at runtime and rewrites the method. That needs a Java runtime that allows
  attaching agents (a full JDK does; some minimal runtimes don't, and then `hook` says so).

### Macros

`\record farm` starts recording. Play normally, then `\record stop` saves `farm.kts`, and `\farm` replays it.
`\record cancel` throws a recording away. The saved script is plain Kotlin you can edit:

```kotlin
macro(
    step(20, "forward sprint", yaw = 90.0f, pitch = 10.0f, slot = 0),
    step(1, "forward sprint jump", yaw = 90.0f, pitch = 10.0f),
    step(8, "attack", yaw = 92.5f, pitch = 40.0f, slot = 2),
    speed = 1.0,
)
```

- Each step holds exactly the listed keys for some ticks (20 per second) while looking at yaw/pitch. A key that
  goes down at the start of a step also counts as a click, so `attack` hits mobs and `use` places blocks.
- Recorded: movement keys, jump, sneak, sprint, attack, use, drop, swap hands, the camera and the hotbar slot.
  A macro replays inputs, not positions, so start it where you started recording.
- Clicks shorter than a tick (50 ms) can be missed while recording.
- Replays are close, not exact: in testing, a recorded 6.2-block walk replayed as 6.0 to 7.6 blocks, because
  timing and momentum differ slightly between runs. Fine for routines, not for pixel-perfect parkour.

### Walking somewhere

```kotlin
walkTo(100, 64, -20)                                     // blocks until there, or until it gives up
val r = walkTo(chest.pos, sprint = true)                  // a block you can't stand in: stops next to it
if (!r.arrived) echo("couldn't get there: ${r.reason}")
walkToNearest { pos -> getBlock(pos.offset(dy = -1)) == "minecraft:grass_block" }
val route = pathTo(0, 64, 0)                              // just plan (a list of feet positions), e.g. to draw it
```

- Minekt plans the route itself through loaded chunks: walking (also diagonally, never cutting corners), jumping
  up one block, dropping down up to 3 blocks (any height into water) and swimming. It never walks into or onto
  lava, fire, cactus, magma, berry bushes, powder snow or cobwebs; add your own with `avoid = listOf("…")`.
- It walks the route with the normal movement keys, steered every tick on the game thread, and re-plans when it
  gets stuck or pushed off course. `showPath = true` draws the route while walking.
- `walkTo` returns a `WalkResult`: `arrived`, plus `status` (`ARRIVED`, `NO_PATH`, `STUCK`, `TIMED_OUT`) and a
  readable `reason`. The default `timeout` is 2 minutes.
- **Baritone fallback:** if Baritone is installed, targets beyond loaded chunks, or ones Minekt finds no route to,
  are handed to Baritone (`usedBaritone` in the result). Turn that off with `useBaritone = false`. Without
  Baritone, far targets are approached in stages: walk to the closest visible spot, look again as chunks load.
- It doesn't break or place blocks, open doors, climb ladders, or do parkour. For those, and for mining or
  building, use Baritone directly (`baritone.*`).
- A target that can't be reached at all is only known once the search has looked everywhere it could go (up to
  40,000 spots, spread over a few ticks), so `NO_PATH` can take a moment in open terrain.

### Libraries from Maven

Any JVM library on Maven Central (or another Maven repository) can be used from a script:

```kotlin
@file:DependsOn("org.apache.commons:commons-text:1.12.0")
@file:Repository("https://jitpack.io")                 // optional: more repositories

import org.apache.commons.text.WordUtils
echo(WordUtils.capitalizeFully("hello from maven"))
```

- Libraries are resolved with Kotlin's own Maven resolver, so the full Maven rules apply: the libraries they
  need in turn, parent POMs, BOMs, version conflicts, and your `~/.m2/settings.xml` (mirrors, private
  repositories with passwords).
- Downloads go into the standard Maven cache, `~/.m2/repository`: once per version, shared with any Java or
  Kotlin tools on the computer, and available offline afterwards.
- Each script sees only the libraries it asked for, so two scripts can use different versions of one library.
  Things the game already has (Gson, Guava, the Kotlin standard library, …) come from the game.
- Use exact versions (`1.12.0`, not `1.+`), so a script behaves the same everywhere.
- Kotlin multiplatform libraries need their JVM artifact: `org.jetbrains.kotlinx:kotlinx-datetime-jvm:…`.
- The resolver itself is a few MB of Maven code that's only loaded when a script first uses `@DependsOn`.
  The full jar has it built in; the small jar downloads it on first use (pinned and hash-checked, like the
  compiler).
- A library is code from the internet, like a script. The `\install` preview lists `@DependsOn` among what a
  script can do.

### Sharing code

```kotlin
@file:Import("lib/util.kts")   // path relative to this script

echo(shout("hello"))
```

## Installing and sharing scripts

```
\install https://github.com/someone/scripts/blob/main/autofarm.kts
```

Minekt downloads the script but **doesn't install it yet**. First it shows a preview in chat:
- the script's name and size, and whether it replaces one you already have
- a plain-language list of what it can do: "send chat messages or commands as you", "connect to the internet",
  "read or write files on your computer", "use raw game access", ...
- whether it compiles

Then click **[Install]**, **[View source]** (opens it in your editor) or **[Cancel]**, or type `\install yes` /
`\install no`. A preview expires after 10 minutes. Installed scripts remember where they came from, so
`\update autofarm` fetches the latest version through the same preview.

- GitHub, gist and pastebin page links are turned into raw-file links automatically; any raw link works.
- Only `https://` is accepted (plain `http` only for `localhost`), at most 512 KB, and web pages are rejected.
- **To share** a script, `\share autofarm` copies it to your clipboard. Paste it anywhere: Discord, a gist, a
  message. Others install it with `\install clipboard` (the name comes from its `// \autofarm` first line,
  or add one: `\install clipboard autofarm`).
- The capability list is a quick scan of the source, not a sandbox. A script can do anything the game can, so
  only install scripts from people you trust.

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
  job was killed.
- **Every script can be stopped**, even `while (true) {}`. Java can't force-stop a thread, so the compiled script
  is rewritten (with ASM) to check for `\kill` at every loop iteration. That also covers loops that catch every
  exception, coroutines, and helper functions.
- **CPU hogs are reported.** A script that keeps a CPU core busy gets one warning in chat with the `\kill`
  command to stop it, and `\jobs` shows each script's CPU use.

## Known limitations (PoC)

- Stopping works inside script code only: a script stuck inside a single call into the game, another mod or a
  Java library (a huge regex, say) stops once that call returns.
- Script code that runs on the game thread (`game { }` blocks, chat filters, live screen text) is never cut
  short. An endless loop there freezes the game, and `\kill` can't be typed while it's frozen.
- Screens are a single column.
- The small jar adds Minekt's compiler jar to Fabric's class loader through a Fabric Loader internal
  (`FabricLauncherBase.addToClassPath`), which a future loader version could change. The full jar doesn't
  use it.
- No sandbox: scripts can do anything the game can. Only run scripts you trust.
- Client-only and Fabric-only for now.
- Some servers ban automation mods. Check a server's rules before using it there.

## Building

Requires JDK 25.

```sh
./gradlew build          # both jars in build/libs/, runs the engine tests
./gradlew runClient -PsmallRun                         # dev client without the compiler, like the small jar
./gradlew runClient -PsmallRun -PsmallRunHome=/tmp/x   # …and with Gradle/Maven caches hidden: forces a download
./gradlew runClient      # dev client
```

`dev/selftest/` holds in-game self-tests. Copy them into `run/minekt/` and list them in `run/minekt/autorun.txt`,
then open a singleplayer world. Each one reports `OK`/`FAIL` lines in chat and closes the game.
- `\hooktest` then `\selftest`: core API, commands, keys and rendering. When it prints `PRESS KEYS NOW`, press
  J and K so the key hooks get exercised.
- `\sharing`: feedback, and installing, updating and sharing. Needs a local web server on 127.0.0.1:8765 (see
  the top of the file).
- `\persist`: run it in two separate launches. The first stores values; the second checks they survived the
  restart.
- `\features`: inventory and containers, interaction, events, chat filters, world info, scans and coroutines. It
  uses the singleplayer server to give items, deal damage and teleport.
- `\walk`: builds an obstacle course on a platform in the sky and walks it with `walkTo` / `walkToNearest`.
- `\more`: timers, packet hooks, method hooks, recording and replaying a macro, and the script manager. Needs
  `xdo-runner.sh` too.
- `\ui`: the watchdog (`\kill` on `while (true) {}`, the CPU warning), messages
  between scripts, and script screens. The screen part clicks and types for real: run the client with
  `dev/selftest/xdo-runner.sh --quickPlaySingleplayer <world>` (needs X11 and `xdotool`), which performs the
  `XDO …` steps the test prints.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
