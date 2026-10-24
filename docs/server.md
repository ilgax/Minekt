# Server scripts

The same Minekt jar runs scripts on a server too: on a dedicated server, and in singleplayer on the world's
own (integrated) server. Server scripts run next to the game's logic instead of in your client, so they can
change the world for everyone, answer players' commands, and react to what anyone does.

```kotlin
// <server>/minekt/greeter.kts   →   /mks run greeter
onPlayerJoin { name ->
    tell(name, chatText("Welcome, $name!", Color.GREEN))
    inventory(name).give("minecraft:bread", 3)
}
onChat { (player, message) ->
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
about: keep them quick, and they can't wait (`sleep`, `waitTicks`). Minekt's own handlers (`onDamage`, `onChat`…)
run on the script's thread just after, with plain copies of what happened: they can take their time, but can't
cancel anything.

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
- `httpGet`, `httpPost`, `http`; `@file:DependsOn` and `@file:Import`
- `eval`, `startScript`, `runScript` (scripts from the server's folder)
- `chatText` and `Color` for styled messages
- Fabric API events, like `ServerTickEvents.END_SERVER_TICK.register { … }`, with no opt-in
- the experimental power tools, with the same opt-ins as on the client: `hook`, `hookAfter`, `rewriteClass`,
  `transformClass`, `classBytes` (`ExperimentalHooks`)

And on top, the server API:

| Call | |
|---|---|
| `server { s -> … }` | run code on the server thread with the live `MinecraftServer`: anything the game or a mod offers |
| `players`, `player(name)` | who's online: `name`, `uuid`, `position`, `dimension`, `health`, `gameMode`, `isOp` |
| `broadcast(…)`, `tell(player, …)` | messages to everyone, or one player |
| `runCommand("time set day")` | run a command as the console; returns what it answered |
| `setBlock(x, y, z, "minecraft:oak_log[axis=x]", dimension)` | place a block, with its state; also by `BlockPos` |
| `blockAt(pos, dimension)` | everything about a block: its `id`, state, light, biome, a sign's text |
| `findBlocks(center, radius, dimension) { id -> }` | blocks whose id matches, nearest first (radius up to 64, loaded chunks only) |
| `raycast(from, to, dimension)`, `raycast(player, distance)` | the first block or entity on a line, or what a player looks at |
| `world(dimension)` | time of day and weather (`runCommand("time set 13000")`, `runCommand("weather rain")` change them) |
| `entities(dimension, near, radius, type)`, `entity(id)`, `entity(uuid)` | loaded entities (players too), nearest to `near` first |
| `spawn("minecraft:zombie", pos, dimension)` | spawn an entity like `/summon`; returns it |
| `killEntity(id)`, `removeEntity(id)` | kill it (loot, a death), or remove it without dying |
| `inventory(player)`, `enderChest(player)`, `container(pos, dimension)` | items, with `items`, `inv[slot]`, `count`, `setSlot(slot, item, count, name, lore, enchantments, damage)`, `clearSlot`, `give`, `take` |
| `onChat { (player, message) -> }` | players' chat messages |
| `onPlayerJoin { name -> }`, `onPlayerLeave { name -> }` | players coming and going |
| `onBlockBreak { (player, pos, block, dimension) -> }` | a player broke a block |
| `onEntitySpawn { entity -> }`, `onEntityRemoved { (entity, died, reason) -> }` | entities coming into the world (spawned, or their chunk loaded) and leaving it |
| `onDamage { (entity, amount, health, cause, attacker, projectile) -> }` | any living entity lost health |
| `onDeath { (entity, cause, killer) -> }` | any living entity (players too) died |
| `onEvent { event -> }` | every server event |
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
| Chat, players joining and leaving | ✔ (`onChat`, tab list) | ✔ (`onChat`, `onPlayerJoin`, `onPlayerLeave`) |
| Blocks | what's loaded around you; `onBlockChange` | any dimension; `onBlockBreak` with who broke it |
| Entities | what's loaded around you; appear / gone | every loaded one; spawn, kill, remove; spawn / removed |
| Damage and death | your own | any living entity |
| Inventories | yours, and the open container | any player's, ender chests, any container |
| Raycasts, time, weather | ✔ | ✔ |
| Fabric events, exactly like in a mod | ✔ | ✔ |
| Hooks, class rewrites, packets (experimental) | ✔ | ✔ |
| Moving, looking, keys, clicking, HUD, drawing, screens, glow, fake blocks, Baritone | ✔ | — (they're about your own game) |

> Server scripts can do anything the server can: only run scripts you trust.
