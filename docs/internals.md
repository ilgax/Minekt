# How it works

- `MinektScript` is a Kotlin [script definition](https://kotlinlang.org/docs/custom-script-deps-tutorial.html).
  The `Minekt` API object is the script's implicit receiver. Server scripts use a second definition,
  `MinektServerScript`, whose receiver is `ServerMinekt`. Both extend `ScriptContext`, which holds what works on
  either side (output, waiting, timers, messages, settings, persistent values, HTTP, eval); helpers declared on
  `ScriptContext` are shared, so each side only sees its own calls plus the shared ones.
- A `Host` is the side a job runs on: the client (`MinektClient`, a `ClientHost` that can reach the game) or one
  server run (`ServerScripts`). It gives the job its script folder, tick clock, compiler and receiver.
  `MinektServer` is the main entrypoint, loaded on both sides, so it and everything server scripts use must
  never load client classes (dedicated servers don't have them). Snapshot conversions (`Convert.kt`), method
  hooks, class rewrites and Fabric events are side-neutral; server packet hooks come from a common `Connection`
  mixin (`ServerConnectionMixin`), the client's from a client-only one. `/mks` checks permission nodes through
  Fabric's permission API (`MksPermissions`).
- The Kotlin scripting host and embeddable compiler are bundled (jar-in-jar) and run in the game's JVM. A warm-up
  compile at startup means the first script doesn't pay for compiler start-up. Compiled scripts are cached by
  content, so editing a file and re-running it picks up the change without restarting the game. They're also
  kept in `minekt/lib/compiled/`, so a script that hasn't changed (nor its imports, nor the mods) starts
  without compiling after a restart too.
- Each run is a **job** on its own thread. API calls marshal onto the client (or server) thread and check
  whether the job was killed.
- **Every script can be stopped**, even `while (true) {}`. Java can't force-stop a thread, so the compiled script
  is rewritten (with ASM) to check for `\kill` at every loop iteration. That also covers loops that catch every
  exception, coroutines, and helper functions.
- **CPU hogs are reported.** A script that keeps a CPU core busy gets one warning in chat with the `\kill`
  command to stop it, and `\jobs` shows each script's CPU use.

## Known limitations (PoC)

- Stopping works inside script code only: a script stuck inside a single call into the game, another mod or a
  Java library (a huge regex, say) stops once that call returns.
- Script code that runs on the game thread (`game { }` blocks, packet filters, method hooks, live screen and HUD values) is never cut
  short. An endless loop there freezes the game, and `\kill` can't be typed while it's frozen.
- Screens are a single column.
- The small jar adds Minekt's compiler jar to Fabric's class loader through a Fabric Loader internal
  (`FabricLauncherBase.addToClassPath`), which a future loader version could change. The full jar doesn't
  use it.
- No sandbox: scripts can do anything the game can. Only run scripts you trust.
- Fabric-only for now. Server scripts have a smaller API than client scripts.
- Some servers ban automation mods. Check a server's rules before using it there.

## Building

Requires JDK 25.

```sh
./gradlew build          # both jars in build/libs/, runs the engine tests
./gradlew runClient -PsmallRun                         # dev client without the compiler, like the small jar
./gradlew runClient -PsmallRun -PsmallRunHome=/tmp/x   # …and with Gradle/Maven caches hidden: forces a download
./gradlew runClient      # dev client
./gradlew runServer      # dev dedicated server, in run/server (server scripts in run/server/minekt)
./gradlew prodServer     # real production server with the built jar, in build/prodserver (accept the EULA first)
./gradlew serverSmokeTest -PacceptMinecraftEula=true   # dev/servertest/servertest.kts on real servers: full jar, small jar downloading the compiler, small jar again from minekt/lib
./gradlew updateKotlinAbi   # after a deliberate change to the stable script API (then commit api/minekt.api)
```

`api/minekt.api` lists the stable script API: `dev.ilgax.minekt.api` minus what's marked experimental. `build`
fails if it differs from the code, so the API can only change on purpose.

`dev/selftest/` holds in-game self-tests. Copy them into `run/minekt/` and list them in `run/minekt/autorun.txt`,
then open a singleplayer world. Each one reports `OK`/`FAIL` lines in chat and closes the game. Tests that need real
key presses and clicks print `XDO …` lines: run them through `dev/selftest/xdo-runner.sh` (Linux, xdotool) or
`python dev/selftest/xdo-runner.py` (Windows), which start the client and carry those lines out. Copy the bundled
examples (`src/main/resources/minekt-examples`) into `run/minekt/` too: the game only installs them into a new folder.
- `\hooktest` then `\selftest`: core API, commands, keys and rendering. When it prints `PRESS KEYS NOW`, press
  J so the keybind hook gets exercised.
- `\sharing`: feedback, and installing, updating and sharing. Needs a local web server on 127.0.0.1:8765 (see
  the top of the file).
- `\persist`: run it in two separate launches. The first stores values; the second checks they survived the
  restart.
- `\features`: inventory and containers, interaction, events, world info, scans and coroutines. It
  uses the singleplayer server to give items, deal damage and teleport.
- `\startuptest` (with `double.kts`): put it under `[startup]` in `autorun.txt` and launch without joining a world;
  it checks it runs on the title screen, with `eval` and `startScript`, then closes the game.
- `\input`: raw keyboard and mouse input, listened to, cancelled and sent. Needs `xdo-runner`.
- `\rewrite`: rewriting real game classes, reading bytes with mixins applied, hooks on rewritten code.
- `\more`: timers, packet hooks, method hooks, recording input and playing it back, and the script manager. Needs
  `xdo-runner` too.
- `\draw`: drawing every frame, pressing bindings by name, the player's and other entities' fields, styled and
  clickable chat, and the clipboard.
- `\fabric`: Fabric API events from scripts: ticks, hiding a chat message, removal when a
  script is killed, and a script with only a listener running until it's killed.
- `\mksevents`: server scripts in the singleplayer world through `/mks`: `onEvent<ServerEvent.Chat>` seeing the
  player's chat, and a server script with only a Fabric listener running until `/mks kill`.
- `\realplayer`: a real player on a real server. Run `./gradlew prodServer` with `online-mode=false` and
  `white-list=false` in its `server.properties` and `dev/servertest/realplayer.kts` in its `minekt/` and
  `autorun.txt`, then join with `--quickPlayMultiplayer localhost:25565` and `[multiplayer]` / `\realplayer` in the
  client's `autorun.txt`. Checks the server's packet hooks, the `/mks install` and `/mks share` chat buttons (the
  game asks before a button runs a command that needs permissions; the test answers like a player), and `/mks`
  leaving the command tree without op.
- `\examples`: starts every script in `examples/` and checks it runs without errors, then takes a screenshot.
- `\stopdamage`: `onDamage`'s cause and attacker from real server damage (fall, a zombie, a skeleton's arrow),
  and `onStop` putting a setting back after `\kill`.
- `\raycast`: raycasts against blocks, see-through blocks, entities and yourself, and line of sight.
- `\details`: item details (durability, enchantments, names, lore, food) and block details (state, light,
  sign text, hardness, biome).
- `\fixworld`: not a test. Puts the test world back (respawns, creative, peaceful, day, overworld) when a test
  left it dead, in the Nether or at night, which makes the next test fail for no reason of its own.
- `\visuals`: glow outlines on entities and blocks (counted in screenshots, through a wall), fake blocks
  (states, server updates underneath, several on one block, many at once, removal when the script ends), pixels
  on the HUD, and Minekt's settings screen through Mod Menu's entry point.
- `\hudlog`: HUD panels, script errors in chat, `\log` and the log viewer. Needs `xdo-runner`.
- `\events`: settings (real clicks), autorun sections, HTTP and the newer events. Needs `xdo-runner` and the
  `autorun.txt` shown at the top of the file.
- `\ui`: the watchdog (`\kill` on `while (true) {}`, the CPU warning), messages
  between scripts, and script screens. The screen part clicks and types for real: run the client with
  `dev/selftest/xdo-runner.sh --quickPlaySingleplayer <world>` (X11 and `xdotool`) or `xdo-runner.py` (Windows), which performs the
  `XDO …` steps the test prints.
