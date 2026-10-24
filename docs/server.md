# Server scripts

The same Minekt jar runs scripts on a server too: on a dedicated server, and in singleplayer on the world's
own (integrated) server. Server scripts run next to the game's logic instead of in your client, so they can
change the world for everyone, answer players' commands, and react to what anyone does.

```kotlin
// <server>/minekt/greeter.kts   →   /mks run greeter
onPlayerJoin { name ->
    tell(name, chatText("Welcome, $name!", Color.GREEN))
    give(name, "minecraft:bread", 3)
}
onChat { (player, message) ->
    if (message == "!spawn") teleport(player, Vec3(0.5, 80.0, 0.5), "overworld")
}
command("day", CommandPermission.OPS) { call ->
    runCommand("time set day")
    call.reply("Good morning")
}
```

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

Operators with permission level 4 (like `/stop`; in singleplayer, the host with cheats on) and the server console run
scripts with `/mks`:

| Command | |
|---|---|
| `/mks run <script> [args]` | run `<script>.kts` from the script folder |
| `/mks eval <code>` | run a line of Kotlin |
| `/mks jobs` | list running scripts |
| `/mks kill <id\|all>` | stop scripts |
| `/mks log <script>` | the script's recent log; the whole log is `minekt/logs/<script>.log` |
| `/mks scripts` | list the scripts in the folder |

It isn't `/minekt` because client scripts already use that name.

What scripts print with `echo`, and their errors, go to the level-4 operators online and to the server log.
`ChatClick.run` actions work for them (they run `/mks click`).

## What server scripts can call

Server scripts get a [`ServerMinekt`](../src/main/kotlin/dev/ilgax/minekt/api/server/ServerMinekt.kt). Everything
that isn't about your own client works the same as in client scripts:

- `args`, `echo`, `writeLog`, `sleep`, `waitTicks` (server ticks), `stop()`, `onTick` (every server tick), `onStop`
- timers: `every`, `after`, `everyTicks`, `afterTicks`; coroutines: `launch`, `async`, `awaitTicks`
- messages between scripts (`publish`, `subscribe`), only between scripts of the same server
- `setting(…)` and `persistent { … }` (`perWorld` keys by the world folder), `save()`
- `httpGet`, `httpPost`, `http`; `@file:DependsOn` and `@file:Import`
- `eval`, `startScript`, `runScript` (scripts from the server's folder)
- `chatText` and `Color` for styled messages

And on top, the server API:

| Call | |
|---|---|
| `server { s -> … }` | run code on the server thread with the live `MinecraftServer`: anything the game or a mod offers |
| `players`, `player(name)` | who's online: `name`, `uuid`, `position`, `dimension`, `health`, `gameMode`, `isOp` |
| `broadcast(…)`, `tell(player, …)` | messages to everyone, or one player |
| `runCommand("time set day")` | run a command as the console; returns what it answered |
| `blockId(x, y, z, dimension)`, `setBlock(x, y, z, "minecraft:oak_log[axis=x]", dimension)` | read and place blocks, also by `BlockPos` |
| `teleport(player, pos, dimension)` | move a player, to another dimension if given |
| `give(player, "minecraft:diamond", 5)` | items; returns false if they didn't all fit |
| `onChat { (player, message) -> }` | players' chat messages |
| `onPlayerJoin { name -> }`, `onPlayerLeave { name -> }` | players coming and going |
| `onBlockBreak { (player, pos, block, dimension) -> }` | a player broke a block |
| `command(name, permission) { call -> }` | a server command for `EVERYONE` or only `OPS` (permission level 2, like `/gamemode`): `call.sender`, `call.args`, `call.reply(…)` |

Dimensions are ids like `minecraft:the_nether`, or just `the_nether`; the default is the overworld.

Client calls (`player.position`, `game { }`, HUD panels, keys, screens…) don't compile in a server script, and
server calls don't compile in a client script, so a script can't end up on the wrong side by accident.

A script's commands are like `/gamemode`: they show up in players' Tab completion when they may use them, and
disappear when the script ends. A name that's already a command of the game or a mod can't be taken.

> Server scripts can do anything the server can: only run scripts you trust.
