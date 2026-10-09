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
| Player | `player` (name, position, yaw/pitch, health, dimension, selectedSlot), `position`, `setOrientation(yaw, pitch)`, `lookAt(x, y, z)`, `selectSlot(n)`, `inventory()` |
| Input | `press(Key.FORWARD)`, `release(key)`, `hold(key, ms)`; held keys are released when the script ends |
| World | `getBlock(x, y, z)`, `findBlocks(radius) { id -> … }`, `entities(radius)`, `players()` |
| Events | `onChat { msg -> }`, `onTick { }`, `onKey("k") { }` |
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

`dev/selftest/` holds an in-game self-test. Copy it into `run/minekt/`, add `\selftest` to
`run/minekt/autorun.txt`, then open a world. It reports `SELFTEST OK/FAIL` lines in chat and closes the game.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
