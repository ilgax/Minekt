# Player and world

## The player

`player` is a snapshot of you, read when you use it:

```kotlin
val p = player
echo(p.health, p.food, p.xpLevel, p.air, p.armor, p.gameMode)
if (p.effects.any { it.id == "minecraft:poison" }) echo("Poisoned!")
if (!p.onGround && p.velocity.y < -0.8) echo("Falling fast")
if (p.inWater || p.inLava || p.onFire) echo("Get out!")
```

It also has `position`, `eyePosition`, `lookDirection` (see [Raycasts](#raycasts)), `yaw`, `pitch`, `maxHealth`, `absorption`, `saturation`, `xpProgress`, `maxAir`,
`sneaking`, `sprinting`, `flying`, `gliding` (elytra), `vehicle` (what you ride, or null), `dimension` and
`selectedSlot`. Anything else is a `game { mc -> mc.player!!… }` away.

## Other entities

`entities(radius)` lists the entities around you, nearest first, and `entity(id)` reads one again (null once
it's gone):

```kotlin
for (e in entities(32)) {
    if (e.type == "minecraft:zombie" && e.heldItem == "minecraft:iron_sword") echo("Armed zombie ${e.distance.toInt()} blocks away")
}
val pet = entities(16).firstOrNull { it.customName == "Rex" }
echo(pet?.health, pet?.maxHealth, pet?.equipment, pet?.isBaby)
```

Each has `id`, `uuid`, `name`, `type`, `position`, `distance`, `health` and `maxHealth` (null for things that
aren't alive), `customName`, `isBaby`, `equipment` (slot → item id: `mainhand`, `offhand`, `head`, `chest`,
`legs`, `feet`, `body`, `saddle`; `heldItem` is the main hand), `velocity`, `yaw`, `pitch`, `sneaking`,
`invisible`, `glowing`, `onFire`, `vehicleId` and `effects`. The server usually only tells the client about
your own effects, so other entities' `effects` are mostly empty.

## Bindings

`press` holds one of the game's bindings, whatever key the player put it on, and `release` lets go. Going down
counts as a click, so `press("inventory")` opens the inventory and `press("attack")` swings:

```kotlin
press("forward"); sleep(2000); release("forward")
press("hotbar.3"); release("hotbar.3")              // select the third hotbar slot
press(Key.SNEAK)                                    // the common ones are also in Key
echo(bindings())                                    // every binding's name, other mods' included
```

Names are as in `options.txt`, with or without `key.`: `forward`, `jump`, `inventory`, `hotbar.1` … `hotbar.9`,
`togglePerspective`, `pickItem`, `playerlist`, `screenshot`, … Bindings still held when the script ends are
released.

## Inventory, containers and interaction

```kotlin
val target = targetedBlock() ?: stop()                 // what the crosshair is on
useOn(target.pos, target.face)                         // right-click it (opens a chest)
val chest = container()                                // slots of the open menu
chest.playerSlots.filter { it.id == "minecraft:cobblestone" }.forEach { shiftClick(it.index) }
closeScreen()

useOn(BlockPos(10, 63, 10), Face.UP)                   // place the held block on top of (10, 63, 10)
attack(targetedEntity()!!.id)
```

Slot numbers are the open menu's indices, as listed by `container().slots`. Each slot says whether it
belongs to the player's inventory or to the container. The server applies its usual reach and permission
checks to every action.

## Raycasts

`raycast(from, to)` finds the first block or entity along any straight line, or null if the line is clear:

```kotlin
val p = player
when (val hit = raycast(p.eyePosition, p.eyePosition + p.lookDirection * 50.0)) {
    is RayHit.Block -> echo("${hit.id} ${hit.distance.toInt()} blocks away, ${hit.face} face")
    is RayHit.Entity -> echo("${hit.entity.name} in the way")
    null -> echo("nothing within 50 blocks")
}

// Can that mob see me? Only solid blocks block the view; other mobs don't.
val sees = raycast(mobEyes, p.eyePosition, entities = false, solidOnly = true) == null
```

- Both kinds of hit have `pos` (the exact point) and `distance` (from `from`). Blocks also have `block`, `id` and
  `face`; entities have `entity`.
- `blocks = false` / `entities = false` leave one kind out. `fluids = true` stops at water and lava surfaces.
- By default, anything you could point at stops the line, like the crosshair: tall grass, cobwebs, signs.
  `solidOnly = true` only counts blocks you'd bump into.
- You never stop your own raycasts unless `includeSelf = true`. Unloaded chunks count as empty.
- `player.eyePosition` and `player.lookDirection` (one block long) give the crosshair's line; `Vec3` has `+`,
  `-`, `*` and `length` for the math.

## World info and large scans

```kotlin
if (!world.isDay) echo("Night falls (${world.timeOfDay})")
val diamonds = scanRegion(BlockPos(-128, -64, -128), BlockPos(128, 16, 128)) { it == "minecraft:deepslate_diamond_ore" }
```

`scanRegion` works on big areas without freezing the game. It skips unloaded chunks and all-air sections, and
spreads the work over several frames. In testing, a 96 × 384 × 96 region took about 2 s while the game kept
running.

## Recording input

`\record farm` starts recording after a 3-second countdown (a big 3, 2, 1 on screen; add `now` to skip it, like
`\record farm now`). Play normally, then `\record stop` saves `farm.kts`, and `\farm` plays it back.
`\record cancel` throws a recording away. To record for a set time, add it: `\record farm 30s` (or `30`, `2m`,
`100t` for ticks) saves by itself when the time is up, with what's left shown above the hotbar. The saved script
is an ordinary script, made of the same calls you'd write by hand:

```kotlin
// \farm, recorded with \record on 2026-10-10: 1.5 s.
selectSlot(0)
setOrientation(90.0f, 10.0f)
keyDown("w")
keyDown("left.control")
waitTicks(20)
keyDown("space")
waitTicks(1)
keyUp("space")
setOrientation(92.5f, 40.0f)
mouseDown(0)
waitTicks(8)
mouseUp(0)
keyUp("w")
keyUp("left.control")
```

- Recorded: every key you press (except typing in chat), mouse buttons and the scroll wheel outside screens, and
  the camera, once per tick. Keys and clicks are sent again as [raw input](events.md#raw-keyboard-and-mouse), so
  they go through your key bindings like the real ones.
- It replays input, not positions, so start it where you started recording. Replays are close, not exact:
  timing and momentum differ slightly between runs. Fine for routines, not for pixel-perfect parkour.
- To change it, edit it: loop part of it, change the waits, add `if`s. Keys still down when a script ends are
  released.
- Long recordings are split into functions (`part1()`, `part2()`, …), since one function can only hold so much.
