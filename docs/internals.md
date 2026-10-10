# How it works

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
- Script code that runs on the game thread (`game { }` blocks, packet filters, method hooks, live screen and HUD values) is never cut
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
  J so the keybind hook gets exercised.
- `\sharing`: feedback, and installing, updating and sharing. Needs a local web server on 127.0.0.1:8765 (see
  the top of the file).
- `\persist`: run it in two separate launches. The first stores values; the second checks they survived the
  restart.
- `\features`: inventory and containers, interaction, events, world info, scans and coroutines. It
  uses the singleplayer server to give items, deal damage and teleport.
- `\startuptest` (with `double.kts`): put it under `[startup]` in `autorun.txt` and launch without joining a world;
  it checks it runs on the title screen, with `eval` and `runScript`, then closes the game.
- `\input`: raw keyboard and mouse input, listened to, cancelled and sent. Needs `xdo-runner.sh`.
- `\rewrite`: rewriting real game classes, reading bytes with mixins applied, hooks on rewritten code.
- `\more`: timers, packet hooks, method hooks, recording input and playing it back, and the script manager. Needs
  `xdo-runner.sh` too.
- `\draw`: drawing every frame, pressing bindings by name, the player's and other entities' fields, styled and
  clickable chat, and the clipboard.
- `\hudlog`: HUD panels, script errors in chat, `\log` and the log viewer. Needs `xdo-runner.sh`.
- `\events`: settings (real clicks), autorun sections, HTTP and the newer events. Needs `xdo-runner.sh` and the
  `autorun.txt` shown at the top of the file.
- `\ui`: the watchdog (`\kill` on `while (true) {}`, the CPU warning), messages
  between scripts, and script screens. The screen part clicks and types for real: run the client with
  `dev/selftest/xdo-runner.sh --quickPlaySingleplayer <world>` (needs X11 and `xdotool`), which performs the
  `XDO …` steps the test prints.
