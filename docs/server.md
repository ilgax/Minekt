# Server scripts

The same Minekt jar runs scripts on a server too: on a dedicated server, and in singleplayer on the world's
own (integrated) server. Server scripts run next to the game's logic instead of in your client, so they can
change the world for everyone, answer players' commands, and react to what anyone does.

```kotlin
// <server>/minekt/greeter.kts   →   /mks run greeter
onEvent<ServerEvent.PlayerJoin> { (name) ->
    tell(name, chatText("Welcome, $name!", Color.GREEN))
    inventory(name).give("minecraft:bread", 3)
}
onEvent<ServerEvent.Chat> { (player, message) ->
    if (message == "!spawn") runCommand("tp $player 0.5 80 0.5")
}
command("day", CommandPermission.OPS) { call ->
    runCommand("time set day")
    call.reply("Good morning")
}
```

## Mod code works here

A server script is written like a mod's code, without the project around it: listen to Fabric API events, import
any class of the game or another mod, and reach the live server with `server { s -> … }`. What a mod would do in
one line, a script does in the same line. Minekt only adds what a mod would need a lot of code for: running and
stopping scripts while the server runs, taking their listeners and commands out again when they stop, a thread of
their own, permissions, saved values and settings, and helpers that replace many lines (finding entities, editing
inventories, spawning like `/summon`…).

```kotlin
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags

// No damage from other players' swords, written exactly like in a mod; gone again after /mks kill.
ServerLivingEntityEvents.ALLOW_DAMAGE.register { victim, source, _ ->
    val attacker = source.entity
    !(victim is ServerPlayer && attacker is ServerPlayer && source.directEntity === attacker && attacker.mainHandItem.`is`(ItemTags.SWORDS))
}

// Anything without a helper: the game's own call, on the server thread.
server { s -> s.playerList.getPlayerByName("Steve")?.let { it.teleportTo(s.overworld(), 0.5, 80.0, 0.5, emptySet(), it.yRot, it.xRot, true) } }
```

Event listeners (Fabric's, like the one above) run on the server thread, right then, and can cancel what they're
about: keep them quick, and they can't wait (`sleep`, `waitTicks`). Minekt's `onEvent<ServerEvent.Damage> { }`,
`onEvent<ServerEvent.Chat> { }`… run on the script's thread just after, with plain copies of what happened: they can
take their time, but can't cancel anything. A script with listeners or handlers keeps running until it stops itself
or is killed; then they're all taken out again.

## Installing

Put the Minekt jar, [Fabric API](https://modrinth.com/mod/fabric-api) and
[Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) into the server's `mods` folder, as for
the client. The small jar downloads the Kotlin compiler on the server's first start (into `minekt/lib`); the full
jar has it built in. Players don't need Minekt to join, and players with the client mod keep their own client
scripts: the two don't interfere.

## Where scripts live

| Server | Script folder |
|---|---|
| Dedicated server | `<server folder>/minekt/` |
| Singleplayer world | `<world folder>/minekt/` (for example `.minecraft/saves/My World/minekt/`) |

The folder is created when the server starts. Like on the client, it holds `logs/`, `data/` (persistent
values) and `settings/` for its scripts.

`autorun.txt` in that folder starts scripts when the server has started: one per line, with its arguments.
`#` starts a comment; a leading `\` or `/mks run` is allowed too.

```
# minekt/autorun.txt
greeter
backup 30m
```

## `/mks`

The server console and operators run scripts with `/mks`. Each subcommand has its own permission (below): by
default the ones that run code need permission level 4 (like `/stop`; in singleplayer, the host with cheats on),
and the ones that only look need level 2 (like `/gamemode`).

| Command | |
|---|---|
| `/mks run <script> [args]` | run `<script>.kts` from the script folder |
| `/mks eval <code>` | run a line of Kotlin |
| `/mks jobs` | list running scripts |
| `/mks kill <id\|all>` | stop scripts |
| `/mks log <script>` | the script's recent log; the whole log is `minekt/logs/<script>.log` |
| `/mks scripts` | list the scripts in the folder you may run |
| `/mks settings <script>` | show a script's settings (they appear once it has run); click one to change it |
| `/mks settings <script> <key> <value>` | change a setting (`on`/`off`, a number, a choice…); a running script gets `onSettingChange` |
| `/mks install <url> [name]` | download a script, check it, and show what it can do; `/mks install confirm` saves it, `/mks install cancel` doesn't |
| `/mks share <script>` | a button that copies the script (players install it with `\install clipboard`), and its link if it has one |

It isn't `/minekt` because client scripts already use that name.

`/mks install` works like the client's `\install`: links to GitHub, gists and pastebin pages are turned into links
to the raw file, only `https://` is allowed, and nothing is saved before you've seen what the script can do and
whether it compiles, and said yes.

What scripts print with `echo`, and their errors, go to the server log and to the players online with
`minekt:notify`. `ChatClick.run` actions work for them (they run `/mks click`).

### Permissions

`/mks` checks permission nodes through Fabric's permission API, so a permission manager mod (like LuckPerms) can
give them to players or take them away. Without one, or when it doesn't say, each falls back to a vanilla permission
level. The console is always allowed. A subcommand someone may not use isn't in their Tab completion.

| Node | Fallback | |
|---|---|---|
| `minekt.command.jobs` | level 2 | `/mks jobs` |
| `minekt.command.log` | level 2 | `/mks log` |
| `minekt.command.run` | level 4 | `/mks run` and `/mks scripts`, for every script |
| `minekt.script.run.<script>` | `minekt.command.run` | `/mks run <script>` for that one script (its name in lowercase) |
| `minekt.command.kill` | level 4 | `/mks kill` |
| `minekt.command.settings` | level 4 | `/mks settings` |
| `minekt.command.eval` | level 4 | `/mks eval` |
| `minekt.command.install` | level 4 | `/mks install` |
| `minekt.command.share` | level 4 | `/mks share` |
| `minekt.notify` | level 2 | seeing scripts' messages and errors, and clicking their chat buttons |
| `minekt.script.command.<name>` | the command's `EVERYONE` / `OPS` | a script's own `/name` command |

The nodes are ids in the `minekt` namespace (`minekt:command.run`); permission managers usually show them with
a dot, as above.

## What server scripts can call

Server scripts get a [`ServerMinekt`](../src/main/kotlin/dev/ilgax/minekt/api/server/ServerMinekt.kt). Everything
that isn't about your own client works the same as in client scripts:

- `args`, `echo`, `writeLog`, `sleep`, `waitTicks` (server ticks), `stop()`, `onTick` (every server tick), `onStop`
- timers: `every`, `after`, `everyTicks`, `afterTicks`, `atDaytime`; coroutines: `launch`, `async`, `awaitTicks`
- messages between scripts (`publish`, `subscribe`), only between scripts of the same server
- `setting(…)` and `persistent { … }` (`perWorld` keys by the world folder), `save()`
- `http`; `@file:DependsOn` and `@file:Import`
- `eval`, `startScript` (scripts from the server's folder)
- `chatText` and `Color` for styled messages
- Fabric API events, like `ServerTickEvents.END_SERVER_TICK.register { … }`, with no opt-in
- the experimental power tools, with the same opt-ins as on the client: `hook`, `hookAfter`, `rewriteClass`,
  `transformClass`, `classBytes` (`ExperimentalHooks`)

And on top, the server API:

| Call | |
|---|---|
| `server { s -> … }` | run code on the server thread with the live `MinecraftServer`: anything the game or a mod offers |
| `players` | who's online, as entities with their `gameMode` and `isOp`: `players.find { it.name == "Steve" }` |
| `broadcast(…)`, `tell(player, …)` | messages to everyone, or one player |
| `runCommand("time set day")` | run a command as the console; returns what it answered |
| `setBlock(x, y, z, "minecraft:oak_log[axis=x]", dimension)` | place a block, with its state; also by `BlockPos` |
| `blockAt(pos, dimension)` | everything about a block: its `id`, state, light, biome, a sign's text |
| `findBlocks(center, radius, dimension) { id -> }` | blocks whose id matches, nearest first (radius up to 64, loaded chunks only) |
| `raycast(from, to, dimension)`, `raycast(player, distance)` | the first block or entity on a line, or what a player looks at |
| `world(dimension)` | time of day and weather (`runCommand("time set 13000")`, `runCommand("weather rain")` change them) |
| `entities(dimension, near, radius, type)`, `entity(id)` | loaded entities (players too), nearest to `near` first; by UUID: `entities().find { it.uuid == … }` |
| `spawn("minecraft:zombie", pos, dimension)` | spawn an entity like `/summon`; returns it |
| `killEntity(id)`, `removeEntity(id)` | kill it (loot, a death), or remove it without dying |
| `inventory(player)`, `enderChest(player)`, `container(pos, dimension)` | items, with `items`, `inv[slot]`, `count`, `setSlot(slot, item, count, name, lore, enchantments, damage)`, `clearSlot`, `give`, `take` |
| `onEvent<ServerEvent.Chat> { (player, message) -> }` | players' chat messages |
| `onEvent<ServerEvent.PlayerJoin> { (name) -> }`, `ServerEvent.PlayerLeave` | players coming and going |
| `onEvent<ServerEvent.BlockBreak> { (player, pos, block, dimension) -> }` | a player broke a block |
| `onEvent<ServerEvent.EntitySpawn> { (entity) -> }`, `ServerEvent.EntityRemoved` `(entity, died, reason)` | entities coming into the world (spawned, or their chunk loaded) and leaving it |
| `onEvent<ServerEvent.Damage> { (entity, amount, health, cause, attacker, projectile) -> }` | any living entity lost health |
| `onEvent<ServerEvent.Death> { (entity, cause, killer) -> }` | any living entity (players too) died |
| `onEvent<ServerEvent> { when (it) { … } }` | every server event |
| `command(name, permission) { call -> }` | a server command for `EVERYONE` or only `OPS` (permission level 2, like `/gamemode`): `call.sender`, `call.args`, `call.reply(…)` |
| `onPacket<T> { packet, player -> }`, `onPacketSent`, `interceptPacket`, `interceptPacketSent`, `sendPacket(player, packet)` | players' packets (`ExperimentalPackets`) |

Dimensions are ids like `minecraft:the_nether`, or just `the_nether`; the default is the overworld. A player's
inventory slots are 0–8 for the hotbar, 9–35 for the rest, 36–39 for armor (feet, legs, chest, head) and 40 for the
offhand.


Client calls (`player.position`, `game { }`, HUD panels, keys, screens…) don't compile in a server script, and
server calls don't compile in a client script, so a script can't end up on the wrong side by accident.

A script's commands are like `/gamemode`: they show up in players' Tab completion when they may use them, and
disappear when the script ends. A name that's already a command of the game or a mod can't be taken.

## Client and server

| | Client scripts | Server scripts |
|---|---|---|
| Output, waiting, timers, coroutines, messages, settings, persistent values, web requests, eval | ✔ | ✔ |
| Chat, players joining and leaving | ✔ (`onChat`, tab list) | ✔ (`ServerEvent.Chat`, `PlayerJoin`, `PlayerLeave`) |
| Blocks | what's loaded around you; `onBlockChange` | any dimension; `ServerEvent.BlockBreak` with who broke it |
| Entities | what's loaded around you; appear / gone | every loaded one; spawn, kill, remove; spawn / removed |
| Damage and death | your own | any living entity |
| Inventories | yours, and the open container | any player's, ender chests, any container |
| Raycasts, time, weather | ✔ | ✔ |
| Fabric events, exactly like in a mod | ✔ | ✔ |
| Hooks, class rewrites, packets (experimental) | ✔ | ✔ |
| Moving, looking, keys, clicking, HUD, drawing, screens, glow, fake blocks, Baritone | ✔ | — (they're about your own game) |

> Scripts can do anything on the server's computer, not just in the game. Only run scripts you trust.

## For other mods: `minekt:host`

Another server-side mod (a server panel, a Discord bridge…) can list, run, watch and stop this server's scripts
without depending on Minekt. While a server runs, Minekt puts one value into Fabric's object share under the key
`minekt:host`, and removes it when the server stops. It's an unmodifiable `Map<String, Object>`, and everything in it
is a JDK type, so your mod needs none of Minekt's classes:

| Key | Type | Meaning |
|---|---|---|
| `version` | `Integer` | `1`. Changes only add keys; a breaking change bumps it. |
| `scriptDir` | `Supplier<String>` | absolute path of this server's script folder |
| `scripts` | `Supplier<List<String>>` | runnable script names (same as `/mks scripts` for the console), sorted |
| `run` | `BiFunction<String, List<String>, CompletableFuture<Map<String, Object>>>` | starts a script with the args exactly as given (no splitting). Returns at once. The future completes normally (never exceptionally) once the script compiled and its body began, or once it failed before that. |
| `jobs` | `Supplier<List<Map<String, Object>>>` | running jobs: `id` (Integer), `name`, `args` (List<String>), `startedAt` (Long epoch ms) |
| `kill` | `Function<Integer, Boolean>` | stops a job; false when no such job |
| `logTail` | `BiFunction<String, Integer, List<String>>` | the last n lines of `logs/NAME.log`, formatted as in the file, oldest first. Detail lines included. Empty if there is no log. n is capped at 200. |

`run`'s future completes with a map of:

- `started` (Boolean)
- `job` (Integer, or null if it never got a job)
- `error` (String or null): the reason in words, the same text the chat message shows. For example
  `No script named 'x' (looked for x.kts in …)`, `[x] Scripts can't run yet: …` while the compiler is still
  downloading, or the first compile error with its line, like `[x:3:9] Unresolved reference 'foo'`.
- `ended` (`CompletableFuture<Map<String, Object>>`, only when `started` is true): completes when the job ends, with
  `reason` (one of `finished`, `stopped`, `error`, `killed`, `server_stopping`), `error` (String or null, the first
  error the script showed when `reason` is `error`) and `seconds` (Double, the run time). By then the job is gone
  from `jobs`.

Everything can be called from any thread and nothing blocks; futures may complete on Minekt's threads, so hop to the
server thread (`server.execute`) before touching the game. A name that isn't a script in the folder doesn't throw:
`run` completes with `started = false` and an error. Scripts started this way are like ones the console starts with
`/mks run`: the same log, the same chat notifications, they're in `/mks jobs`, and `/mks kill` stops them.

There's a new map for every server run (in singleplayer, every time a world opens), so look it up when you need it
rather than keeping it. `whenAvailable` tells you when the first one appears.

Java:

```java
@SuppressWarnings("unchecked")
static void runScript(String name, List<String> args) {
    var host = (Map<String, Object>) FabricLoader.getInstance().getObjectShare().get("minekt:host");
    if (host == null) return; // no Minekt, or no server running
    var run = (BiFunction<String, List<String>, CompletableFuture<Map<String, Object>>>) host.get("run");
    run.apply(name, args).thenAccept(result -> {
        if (!(Boolean) result.get("started")) {
            LOGGER.warn("{} didn't start: {}", name, result.get("error"));
            return;
        }
        var ended = (CompletableFuture<Map<String, Object>>) result.get("ended");
        ended.thenAccept(end -> LOGGER.info("job {} ended: {}", result.get("job"), end.get("reason")));
    });
}

// in onInitialize
FabricLoader.getInstance().getObjectShare().whenAvailable("minekt:host", (key, value) -> LOGGER.info("Minekt is here"));
```

Kotlin:

```kotlin
@Suppress("UNCHECKED_CAST")
fun minektHost(): Map<String, Any?>? = FabricLoader.getInstance().objectShare.get("minekt:host") as Map<String, Any?>?

@Suppress("UNCHECKED_CAST")
fun scriptNames(): List<String> = (minektHost()?.get("scripts") as Supplier<List<String>>?)?.get().orEmpty()

@Suppress("UNCHECKED_CAST")
fun lastLines(script: String): List<String> =
    (minektHost()?.get("logTail") as BiFunction<String, Int, List<String>>?)?.apply(script, 20).orEmpty()

FabricLoader.getInstance().objectShare.whenAvailable("minekt:host") { _, _ -> logger.info("Minekt is here") }
```

Version 1 is frozen: these keys keep their types and meaning, and later versions only add keys (a breaking change
would bump `version`). Everything else in Minekt (`JobManager`, `Job`, `ServerScripts`…) is internal and may change
in any release.