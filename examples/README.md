# Example scripts

More examples, beyond the ones Minekt puts in `.minecraft/minekt/` the first time it runs. To try one, copy it
into `.minecraft/minekt/` and type its name after a backslash in chat: `tracers.kts` runs with `\tracers`. Each
keeps running until you `\kill` it (or, for `coords`, for a minute).

| Script | What it does | Shows |
|---|---|---|
| [tracers.kts](tracers.kts) | Lines from your view to hostile mobs, toggled with a key | `onWorldRender`, `entityPosition`, `keybind`, `setting`, `Vec3` math |
| [crosshair.kts](crosshair.kts) | A crosshair that turns red on mobs, with their name, health and your speed | `onHudRender`, the game's hit result, `player.velocity` |
| [mobradar.kts](mobradar.kts) | A small radar of mobs and players around you, turning with you | `onHudRender`, `entities()`, trigonometry |
| [effects.kts](effects.kts) | Your potion effects with time left and a bar that runs down | `onHudRender`, `player.effects`, item icons |
| [fpsgraph.kts](fpsgraph.kts) | A graph of your frame rate | `onHudRender`, drawing a chart from rectangles |
| [trail.kts](trail.kts) | A fading breadcrumb trail behind you | `onWorldRender` lines and points, colors with alpha |
| [coords.kts](coords.kts) | Your position in chat with copy, share and save buttons | `chatText`, every kind of `ChatClick`, `persistent` |
| [damagelog.kts](damagelog.kts) | Says what hurt you and who: fall, lava, a mob, an arrow and its archer | a vanilla packet with `onPacket`, `game { }` |
| [autoeat.kts](autoeat.kts) | Eats from your hotbar when you're hungry | `player.food`, `press("use")`, item details (`isFood`, `nutrition`) |
| [quicktools.kts](quicktools.kts) | Full bright on a key, a low-health warning, no jumping while sneaking | the same mod as in [Coming from Fabric](../docs/from-fabric.md): Fabric events, `hook`, `onStop` |
| [minimap.kts](minimap.kts) | A map of the ground around you in the colors of real maps | `pixels`, reading the world through `game { }` |
| [glowmobs.kts](glowmobs.kts) | Outlines mobs (by kind) and chests around you through walls | `glow`, `highlight`, changing a glow's color |
| [preview.kts](preview.kts) | A floor of any block, only on your screen, to try out a build | `fakeBlocks`, `targetedBlock`, `keybind` |
| [chatfilter.kts](chatfilter.kts) | Hides chat messages with words you choose | Fabric API events (experimental), `setting` |

They're compiled by the unit tests (`the example scripts compile`) and run by `dev/selftest/examples.kts`.
