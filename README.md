<p align="center"><img src="src/main/resources/assets/minekt/icon.png" alt="Minekt" width="128"></p>

<h1 align="center">Minekt</h1>

<p align="center"><b>Kotlin scripts for Minecraft.</b> Write a small <code>.kts</code> file, type its name in chat, and it runs
inside the game. Edit it and run it again: no restart.</p>

<p align="center">Beta 0.1.0 · Fabric · Minecraft 26.3 · Java 25 · GPL-3.0-or-later</p>

> **Beta:** things may still change before 1.0. Each [release](https://github.com/ilgax/Minekt/releases) lists what
> changed.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 26.3 (it needs Java 25).
2. Put these in `.minecraft/mods`:
   - [Fabric API](https://modrinth.com/mod/fabric-api)
   - [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin)
   - Minekt, from [Releases](https://github.com/ilgax/Minekt/releases): `minekt-<version>.jar` (downloads the Kotlin
     compiler on first start) or `minekt-<version>-full.jar` (has it built in, works offline).
3. Start the game and open a world. Type `\hello` in chat.

## Your first script

Scripts live in `.minecraft/minekt/` (Minekt creates it, with a few examples). Save this as
`.minecraft/minekt/ores.kts`, then type `\ores` in chat:

```kotlin
val ores = findBlocks(16) { it.endsWith("_ore") }
echo("${ores.size} ores nearby")
ores.firstOrNull()?.let { echo("nearest:", it.id, "at", it.pos) }
```

Change it, save, and type `\ores` again: it runs the new version. [Your first scripts](docs/tutorial.md) goes on from
here, and [examples/](examples/README.md) has more to copy: a radar, a minimap, tracers, a custom crosshair…

## In-game commands

| Command | |
|---|---|
| `\<script> [args]` | run `<script>.kts` from `.minecraft/minekt/` |
| `\menu` | the script manager: run, stop, settings and logs |
| `\jobs`, `\kill <id\|all>` | see and stop running scripts |
| `\install <link>` | install someone's script, after showing what it can do |
| `\help` | every command |

With [Mod Menu](https://modrinth.com/mod/modmenu), Minekt's settings are under Configure. More in
[Getting started](docs/getting-started.md).

**On a server:** put the same three jars in the server's `mods` folder. Operators run scripts from the server's
`minekt/` folder with `/mks run <script>`. See [Server scripts](docs/server.md).

> Scripts can do anything the game can. Only run scripts you trust, and check a server's rules before using
> automation there.

## For power users

Scripts are full Kotlin with the game and every installed mod on hand, and mod code works in them as it is.

- [API overview](docs/api.md): everything scripts can call, on one page
- [Player and world](docs/world.md), [Events](docs/events.md), [Drawing and screens](docs/display.md),
  [Data and the web](docs/data.md), [Errors and logs](docs/errors-and-logs.md), [Sharing](docs/sharing.md)
- [Advanced](docs/advanced.md): Fabric API events, method hooks, class rewriting, packets, other mods, Baritone
- [Coming from Fabric](docs/from-fabric.md): the same mod written both ways
- [Server scripts](docs/server.md): the server API, `/mks`, permission nodes for permission managers
- [How it works](docs/internals.md): internals, building from source, the tests

All the docs: [docs/](docs/README.md).

## AI disclosure

A large part of Minekt's code, tests and documentation was written with the help of an AI coding assistant. I
review and test the changes, and every release is built only after the automated tests and the real-server tests
pass. Minekt itself uses no AI: it doesn't connect to any AI service.

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).