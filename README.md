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
2. Drop a Minekt jar into `.minecraft/mods`: `minekt-<version>.jar` (small, downloads the Kotlin compiler once)
   or `minekt-<version>-full.jar` (compiler built in, for offline use).
3. Start the game. Minekt creates `.minecraft/minekt/` with example scripts: type `\hello` in chat.

New to it? [Your first scripts](docs/tutorial.md) walks through six short ones, and [examples/](examples/README.md)
has more to copy from: tracers, a radar, a minimap, a custom crosshair, clickable coordinates and others. `\help` (or `/mk help`) lists the
chat commands, and `\menu` opens the script manager. With [Mod Menu](https://modrinth.com/mod/modmenu), Minekt's
settings (command prefix, editor) are under Configure. More in [Getting started](docs/getting-started.md).
Write Fabric mods? [Coming from Fabric](docs/from-fabric.md) shows the same mod both ways.

## What scripts can do

- **Read and control the game:** the player, inventory and containers, blocks and entities, keys, chat,
  recording your input as a script. ([Player and world](docs/world.md))
- **React to things:** chat, ticks, keys, damage, blocks changing, mobs appearing, sounds, the tab list and
  scoreboard, timers, messages from other scripts, their own `/commands`. ([Events](docs/events.md))
- **Show things:** HUD panels, shapes drawn in the world, glow outlines through walls, your own drawing every
  frame down to single pixels, blocks only you can see, real Minecraft screens. ([Drawing and screens](docs/display.md))
- **Keep and fetch data:** settings the player can change in a menu, values that survive restarts, web
  requests, any Maven library. ([Data and the web](docs/data.md))
- **Go deep:** raw packets, hooking any game method at runtime, rewriting any class's bytecode, other mods' APIs, Baritone. ([Advanced](docs/advanced.md))
- **Be shared:** `\install <link>` with a preview of what a script can do. ([Sharing](docs/sharing.md))
- **Run on servers:** the same jar on a dedicated server or in singleplayer runs server scripts, started with
  `/mks`: greet players, add commands, change blocks, react to chat and broken blocks. ([Server scripts](docs/server.md))

Failing scripts explain themselves in chat, and every script has a log. ([Errors and logs](docs/errors-and-logs.md))

All the docs: [docs/](docs/README.md) · [API overview](docs/api.md) · [How it works](docs/internals.md)

> No sandbox: scripts can do anything the game can. Only run scripts you trust, and check a server's rules
> before using automation there.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
