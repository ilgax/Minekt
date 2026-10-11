# Getting started

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
| `\log [script]` | show a script's log: output, errors with stack traces (no name: the last script that failed) |
| `\kill <id\|all>` | stop scripts |
| `\scripts` | list available scripts |
| `\install <url> [name]` | install a script from a link, after a preview ([more](sharing.md)) |
| `\install clipboard [name]` | install a script someone shared with you |
| `\update <script>` | get a newer version from where it was installed from |
| `\share <script>` | copy a script to the clipboard to share it |
| `\record <name> [30s] [now]` / `\record stop` | record your keys, clicks and camera as a script ([more](world.md#recording-input)) |
| `\settings <script>` | change a script's settings (see [Settings](data.md#settings)) |
| `\help` | show this list |

Every command also works with `/mk` in front instead of `\`: `/mk menu`, `/mk hello`, `/mk kill all`, with
tab completion for commands and script names.

Lines starting with `\` never reach the server. A script named like one of these commands (`menu.kts`,
`log.kts`, …) can't be started by typing its name, since the command wins; `\scripts` points such scripts out, and
the script manager still runs them.

**Script manager:** `\menu`, or a key you bind under Controls → Minekt → Script manager, opens a screen with
the running scripts (CPU use and a Stop button each), every script with Run and Edit (opens it in your editor),
arguments, New script, Open folder, installing from a link, and the last few errors. Every script has a Log
button, and clicking a recent error opens that script's log.

## Autorun

`minekt/autorun.txt` lists `\commands` to run when you join a world. `#` starts a comment. Sections pick
where the lines below them run:

```
\hello                          # before any section: every world
[server *.example.net]           # servers whose address matches (* and ? wildcards)
\miner
[world Creative*]                # singleplayer worlds by name
\builder
[dimension the_nether]           # on joining in the Nether, and every time you enter it
\nightvision
[multiplayer]                    # any server; [singleplayer]: any local world
\stats
[server play.x.net, dimension the_end]   # all conditions must match
\endtools
[always]                         # back to every world
\clock
[startup]                        # once per launch, when the game has started: on the title screen
\patches
```

- Sections without `dimension` run once, on joining. Sections with one also run whenever you enter that
  dimension, so `[dimension the_nether]` alone means "every time I'm in the Nether".
- `[startup]` runs before any world exists, so `player` and the like aren't available yet, but `game { }`, class
  rewriting, keybinds, HTTP and the rest are. It's the place to rewrite classes before the game loads them:
  then a rewrite can change anything, not just method bodies. Startup scripts that keep running (with handlers)
  carry on into every world. Their output goes to the game log and their script log, since there's no chat yet.
- A script that is already running isn't started a second time.
- Matching ignores case; `minecraft:` is optional in dimension names. An unknown section name is reported in
  chat when you join, and its lines don't run.

## Your first script

Create `minekt/hello.kts`:

```kotlin
echo("Hello, ${player.name}! You're at ${player.position}")
```

and type `\hello` in chat. Edit the file and run it again: changes are picked up without restarting. Next:
[Your first scripts](tutorial.md), then the [API overview](api.md).
