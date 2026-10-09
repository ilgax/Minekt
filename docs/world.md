# Player and world

## Inventory, containers and interaction

```kotlin
val target = targetedBlock() ?: stop()                 // what the crosshair is on
useOn(target.pos, target.face)                         // right-click it (opens a chest)
val chest = container()                                // slots of the open menu
chest.playerSlots.filter { it.id == "minecraft:cobblestone" }.forEach { shiftClick(it.index) }
closeScreen()

placeBlock(BlockPos(10, 64, 10))                       // places the held block against a neighbour
breakBlock(BlockPos(10, 64, 10))                       // mines it, taking as long as survival would
attack(targetedEntity()!!.id)
turnToward(0.5, 64.0, 0.5, ticks = 10)                 // smooth turn instead of a snap
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

## Walking somewhere

```kotlin
walkTo(100, 64, -20)                                     // blocks until there, or until it gives up
val r = walkTo(chest.pos, sprint = true)                  // a block you can't stand in: stops next to it
if (!r.arrived) echo("couldn't get there: ${r.reason}")
walkToNearest { pos -> getBlock(pos.offset(dy = -1)) == "minecraft:grass_block" }
val route = pathTo(0, 64, 0)                              // just plan (a list of feet positions), e.g. to draw it
```

- Minekt plans the route itself through loaded chunks: walking (also diagonally, never cutting corners), jumping
  up one block, dropping down up to 3 blocks (any height into water) and swimming. It never walks into or onto
  lava, fire, cactus, magma, berry bushes, powder snow or cobwebs; add your own with `avoid = listOf("…")`.
- It walks the route with the normal movement keys, steered every tick on the game thread, and re-plans when it
  gets stuck or pushed off course. `showPath = true` draws the route while walking.
- `walkTo` returns a `WalkResult`: `arrived`, plus `status` (`ARRIVED`, `NO_PATH`, `STUCK`, `TIMED_OUT`) and a
  readable `reason`. The default `timeout` is 2 minutes.
- **Baritone fallback:** if Baritone is installed, targets beyond loaded chunks, or ones Minekt finds no route to,
  are handed to Baritone (`usedBaritone` in the result). Turn that off with `useBaritone = false`. Without
  Baritone, far targets are approached in stages: walk to the closest visible spot, look again as chunks load.
- It doesn't break or place blocks, open doors, climb ladders, or do parkour. For those, and for mining or
  building, use Baritone directly (`baritone.*`).
- A target that can't be reached at all is only known once the search has looked everywhere it could go (up to
  40,000 spots, spread over a few ticks), so `NO_PATH` can take a moment in open terrain.

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
