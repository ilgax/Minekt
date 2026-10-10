# Drawing and screens

## Drawing in the world

Boxes, lines, arrows and floating text, drawn in the 3D world until removed or the script ends:

```kotlin
val ores = findBlocks(16) { it.endsWith("_ore") }
ores.forEach { highlight(it.pos, Color.CYAN) }                 // filled box around each block
val label = text(Vec3(0.5, 80.0, 0.5), "Spawn", Color.YELLOW, scale = 2f)
line(player.position, Vec3(0.5, 64.0, 0.5), Color.RED, width = 3f)
label.remove()                                                  // every call returns a handle
clearShapes()                                                   // or drop them all
```

- Every shape takes `color` (ARGB: `Color.RED`, `Color.rgb(r, g, b, a)`) and `throughWalls` (default `true`:
  drawn on top of terrain, so you can see it behind blocks). Boxes take `fill`; lines and arrows take `width`;
  text takes `scale`.
- Fills come out solid rather than see-through when drawn through walls (that's how the game's gizmo renderer
  works). Use `fill = false` for outlines only.
- A script that only draws ends straight away and takes its shapes with it. Keep it alive with a handler
  (`keybind("Clear").onPress { stop() }`) to leave them up until you're done (see `ores.kts`).
- Shapes use Minecraft's built-in gizmo renderer. They're redrawn every tick from data the script sets, so
  scripts never run code on the render thread.

## HUD panels

`hud("line", …)` puts plain text in the top-left corner. `hudPanel` does more: boxes with titles, live text,
progress bars, item icons and images, in any corner or edge of the screen:

```kotlin
var ores = 0
val panel = hudPanel(Anchor.TOP_RIGHT) {
    title("Miner")
    text { "Ores: $ores" }                                    // live: re-read every tick
    bar("Durability", Color.GREEN) { durabilityLeft() }       // 0.0–1.0 (or pass max = …)
    separator()
    row {
        item("diamond", count = 12)                           // an icon with its count
        item("minecraft:iron_ingot") { "$iron" }              // an icon with live text
    }
    image("icons/pick.png", 32, 32)                           // a PNG from the minekt folder
}
panel.update { title("Done") }      // replace what it shows
panel.move(Anchor.BOTTOM_RIGHT)     // or panel.visible = false, panel.remove()
```

- **Anchors:** `TOP_LEFT`, `TOP`, `TOP_RIGHT`, `LEFT`, `CENTER`, `RIGHT`, `BOTTOM_LEFT`, `BOTTOM` (above the
  hotbar), `BOTTOM_RIGHT`. Panels at the same anchor stack instead of overlapping. `x`/`y` nudge a panel,
  `scale` resizes it, `background = false` drops the dark box.
- **Text** takes a `color` and `§` color codes (`"§aok §cfail"`). `title` is bold.
- **Live parts** (`{ … }`) are re-read every tick on the game thread, so `game { }` inside them is direct. Keep
  them quick: read variables and the player, don't sleep or wait. If one throws, it shows `⚠ message` and
  the error goes to the script's log once.
- **Images** are reloaded when the file changes. A missing image draws a magenta box, with a note in the log.
- Panels hide with the rest of the HUD (F1) and go away when the script ends.

## Screens

Scripts can open real Minecraft screens: settings menus, pickers, little dashboards.

```kotlin
var autoEat by persistent { true }
var range by persistent { 32 }
var mode by persistent { "walk" }

keybind("Miner settings", default = "m").onPress {
    showScreen("Miner settings") {
        text("Changes apply right away")
        text { "Health: ${player.health}" }                 // live: recomputed every tick
        toggle("Auto eat", ::autoEat)
        slider("Range", 8..64, ::range)
        choice("Mode", listOf("walk", "sprint"), ::mode)
        row {
            button("Start") { baritone.mine("diamond_ore") }
            button("Stop", tooltip = "Stops Baritone") { baritone.cancel() }
        }
    }
}
```

- Widgets: `text`, `button`, `toggle`, `slider` (whole numbers, or decimals with a `step`), `textField`, `choice`,
  and `row { }` to put several side by side. A **Done** button is added unless `doneButton = false`.
- Give a widget a property (`::autoEat`) and it changes that property as the player clicks. Or pass a value and
  a callback: `slider("Zoom", 1..10, zoom) { zoom = it }`. Combined with `persistent`, that's a settings menu
  that remembers itself.
- Callbacks run on the script's side, one at a time and alongside the script body, so they can use the whole
  API (`game { }`, `sleep`, `baritone`, …).
- `showScreen` returns a handle: `close()`, `refresh()` (runs the block again, e.g. after a list changed),
  `isOpen`, `onClose { }`, `awaitClose()` (suspend) and `waitForClose()` (blocking).
- The script stays alive while its screen is open. The screen doesn't pause the game.
- Screens taller than the window scroll with the mouse wheel.
- The bundled `waypoints.kts` is a complete example: a per-world waypoint list with add/delete buttons that
  redraws itself with `refresh()` and publishes the waypoints for other scripts.
