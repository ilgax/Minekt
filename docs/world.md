# Player and world

## Inventory, containers and interaction

```kotlin
val target = targetedBlock() ?: stop()                 // what the crosshair is on
useOn(target.pos, target.face)                         // right-click it (opens a chest)
val chest = container()                                // slots of the open menu
chest.playerSlots.filter { it.id == "minecraft:cobblestone" }.forEach { shiftClick(it.index) }
closeScreen()

placeBlock(BlockPos(10, 64, 10))                       // places the held block against a neighbour
attack(targetedEntity()!!.id)
```

Slot numbers are the open menu's indices, as listed by `container().slots`. Each slot says whether it
belongs to the player's inventory or to the container. The server applies its usual reach and permission
checks to every action.

## World info and large scans

```kotlin
if (!world.isDay) echo("Night falls (${world.timeOfDay})")
val diamonds = scanRegion(BlockPos(-128, -64, -128), BlockPos(128, 16, 128)) { it == "minecraft:deepslate_diamond_ore" }
```

`scanRegion` works on big areas without freezing the game. It skips unloaded chunks and all-air sections, and
spreads the work over several frames. In testing, a 96 × 384 × 96 region took about 2 s while the game kept
running.

## Macros

`\record farm` starts recording. Play normally, then `\record stop` saves `farm.kts`, and `\farm` replays it.
`\record cancel` throws a recording away. The saved script is plain Kotlin you can edit:

```kotlin
macro(
    step(20, "forward sprint", yaw = 90.0f, pitch = 10.0f, slot = 0),
    step(1, "forward sprint jump", yaw = 90.0f, pitch = 10.0f),
    step(8, "attack", yaw = 92.5f, pitch = 40.0f, slot = 2),
    speed = 1.0,
)
```

- Each step holds exactly the listed keys for some ticks (20 per second) while looking at yaw/pitch. A key that
  goes down at the start of a step also counts as a click, so `attack` hits mobs and `use` places blocks.
- Recorded: movement keys, jump, sneak, sprint, attack, use, drop, swap hands, the camera and the hotbar slot.
  A macro replays inputs, not positions, so start it where you started recording.
- Clicks shorter than a tick (50 ms) can be missed while recording.
- Replays are close, not exact: in testing, a recorded 6.2-block walk replayed as 6.0 to 7.6 blocks, because
  timing and momentum differ slightly between runs. Fine for routines, not for pixel-perfect parkour.
