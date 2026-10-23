# API overview

Everything below is in scope in every script, no imports needed.

| Area | API |
|---|---|
| Output | `echo(vararg parts)` (plain, or styled with `chatText(text, color, bold, hover, click = ChatClick.run { })`), `chat(msg)` (a leading `/` makes it a command), `execute(cmd)`, `hudPanel { … }`, `writeLog(vararg parts)`, `clipboard`, `clipboard = text` |
| Feedback | `playSound(id)`, `toast(title, message, duration)`, `actionBar(text)`, `title(title, subtitle, fadeInTicks, stayTicks, fadeOutTicks)`, `screenshot(name)` |
| Control | `args`, `jobId`, `sleep(ms)` or `sleep(2.seconds)`, `waitTicks(n)`, `stop()` |
| Player | `player` (name, position, eyePosition, lookDirection, yaw/pitch, health, food, xp, air, armor, effects, velocity, onGround, inWater, sneaking, sprinting, flying, gameMode, vehicle, …), `position`, `setOrientation(yaw, pitch)`, `lookAt(x, y, z)` |
| Keybinds | `keybind(name, default)` → `onPress { }`, `onRelease { }`, `isDown`, `key`, `awaitPress()`, `awaitRelease()`, `remove()` |
| Raw input | `onRawKey { }`, `onRawMouse { }`, `onMouseMove { }` (`cancel()`), `tapKey`, `keyDown`, `keyUp`, `typeText`, `clickMouse`, `mouseDown`, `mouseUp`, `scrollMouse`, `moveMouse`; `\record` writes these for you |
| Bindings | `press("forward")`, `release("forward")`, `bindings`: any of the game's bindings, whatever key it's on; released when the script ends |
| Inventory & screens | `inventory` (items with name, durability, enchantments, lore, nutrition…), `heldItem`, `offhandItem`, `selectSlot(n)`, `screen`, `container`, `clickSlot(i)`, `shiftClick(i)`, `swapWithHotbar(i, n)`, `dropSlot(i)`, `openInventory()`, `closeScreen()` |
| Targeting & interaction | `raycast(from, to)` → `RayHit.Block` / `RayHit.Entity` / null, `targetedBlock`, `targetedEntity`, `useItem()`, `useOn(pos, face)`, `attack(entityId)`, `interact(entityId)` |
| World | `world` (dimension, timeOfDay, gameTime, isDay, rain, thunder), `blockId(x, y, z)`, `blockAt(pos)` (properties, light, biome, hardness, sign text), `findBlocks(radius) { id -> … }`, `scanRegion(from, to) { id -> … }`, `isLoaded(x, z)`, `entities(radius)` (type, name, health, equipment, isBaby, customName, velocity, …), `entity(id)`, `players` |
| Events | `onChat { (text, sender) -> }`, `onTick`, `onStop { reason -> }`, `onDamage { (amount, health, cause, attacker) -> }`, `onDeath`, `onRespawn`, `onJoinWorld`, `onLeaveWorld`, `onDimensionChange`, `onScreenOpen`, `onScreenClose`, `onChunkLoad`, `onItemGained`, `onBlockChange`, `onEntityAppear`, `onEntityGone`, `onSound`, `onParticle`, `onPlayerJoin`, `onPlayerLeave`, `onScoreboardChange`, `onEvent` |
| Server info | `tabList`, `sidebar` |
| Settings | `var x by setting(default, …)`, `onSettingChange { (key, value) -> }` |
| Persistence | `val x by persistent { default }` (options `perWorld`, `store`, `key`), `save()` |
| Web | `httpGet(url)`, `httpPost(url, body)`, `http(method, url, body)` → `status`, `body`, `ok`, `json<T>()` |
| Coroutines | `launch { }`, `async { }`, `awaitTicks(n)`, `awaitChat { }`, `awaitEvent { }`, `awaitEventOf<T>()`, `awaitScreen { }` (+ all of `kotlinx.coroutines`) |
| Commands | `command("name", completions) { args -> }` registers a real `/name` |
| Screens | `showScreen(title) { text(…); toggle(…); slider(…); textField(…); choice(…); button(…) { }; row { } }` |
| Messages | `publish(topic, value)`, `subscribe<T>(topic) { }`, `awaitMessage<T>(topic)`, `lastMessage<T>(topic)` |
| Timers | `every(5.seconds) { }`, `after(30.seconds) { }`, `everyTicks(n) { }`, `afterTicks(n) { }`, `atDaytime(13000) { }` |
| Packets (experimental) | `onPacket<T> { }`, `onPacketSent<T> { }`, `interceptPacket<T> { it or null }`, `interceptPacketSent<T> { }`, `awaitPacket<T>()`, `sendPacket(p)` |
| Fabric events (experimental) | `@file:OptIn(ExperimentalFabricEvents::class)`, then `ClientTickEvents.END_CLIENT_TICK.register { }` like in a mod; removed when the script ends (experimental) |
| Method hooks (experimental) | `hook<Class>("method") { call -> }`, `hookAfter<Class>("method") { call -> }` (change `args`, `cancel(value)`, replace `result`) |
| Code | `eval(code, "name" to value)`, `runScript(name, args)`, `startScript(name, args)` → `await()`, `kill()`, `isRunning` |
| Class rewriting (experimental) | `rewriteClass<Class> { node -> }` (ASM tree), `transformClass(name) { bytes -> newBytes }`, `classBytes<Class>()` |
| Libraries | `@file:DependsOn("group:artifact:version")`, `@file:Repository("https://…")` |
| World shapes | `highlight(pos)` (the block's own shape), `box(min, max)`, `line(a, b)`, `arrow(a, b)`, `text(pos, "…")` → `color`, `remove()`; `clearShapes()` |
| Glow outlines (experimental) | `glow(entity, color)` → `color`, `remove()`; blocks: `highlight(pos)` |
| Fake blocks (experimental) | `fakeBlock(pos, "minecraft:glass")`, `fakeBlocks(map)`, `clearFakeBlocks()` → `remove()` |
| Pixels (experimental) | `pixels(w, h)` → `[x, y] = color`, `fill`, `clear`, `data` + `changed()`; drawn with `g.pixels(p, x, y, w, h)` |
| Drawing every frame | `onHudRender { g -> g.rect(…); g.text(…); g.item(…); g.image(…) }`, `onWorldRender { w -> w.line(…); w.box(…); w.text(…); w.point(…); w.entityPosition(id) }` |
| Mods | `isModLoaded(id)`, `modVersion(id)`, `baritone.*` (experimental) |
| Raw access | `game { mc -> … }` runs on the client thread with the live `Minecraft` instance |

Game state comes back as plain data (`PlayerInfo`, `EntityInfo`, `BlockInfo`, …). Those are copies, so
they're safe to keep and read from the script's thread.

### Positions

Positions are Minecraft's own `Vec3` (exact) and `BlockPos` (a block), already imported in every script, so
code from mods and the game works as it is. Both never change once made, so they're safe to keep too. Minekt
adds `+`, `-`, `*`, unpacking (`val (x, y, z) = pos`), `vec.blockPos` and `vec.toShortString()`; Minecraft's own
methods (`pos.below()`, `pos.above(2)`, `pos.offset(1, 0, -1)`, `pos.center`, `a.distanceTo(b)`, `v.length()`, …)
work as in a mod. `echo` shows both as `(x, y, z)`; inside a string, use `toShortString()` (`"$pos"` alone
gives Minecraft's long form).

### Stable and experimental

Everything on this page is stable: a script that only uses it keeps working in later Minekt versions (the build
checks this against `api/minekt.api`). A few parts aren't settled yet and need a line at the top of the script:

| Line | For |
|---|---|
| `@file:OptIn(ExperimentalPackets::class)` | `onPacket`, `interceptPacket`, `sendPacket`, `awaitPacket`: Minecraft's packet classes change every version |
| `@file:OptIn(ExperimentalHooks::class)` | `hook`, `hookAfter`, `rewriteClass`, `transformClass`, `classBytes`: they name the game's methods |
| `@file:OptIn(ExperimentalVisuals::class)` | `glow`, `fakeBlock`, `fakeBlocks`, `pixels`: new, may still change |
| `@file:OptIn(ExperimentalBaritone::class)` | `baritone.*`: follows Baritone's own API |
| `@file:OptIn(ExperimentalFabricEvents::class)` | Fabric API events |

Several at once: `@file:OptIn(ExperimentalPackets::class, ExperimentalHooks::class)`. Without the line, the
script doesn't compile and the error says which line to add. `game { }` is stable itself, but what you do with
the game inside it follows Minecraft's own changes.

### Time

Real time is a Kotlin `Duration`: `every(5.seconds)`, `toast("Hi", duration = 3.seconds)`, `httpGet(url, timeout = 10.seconds)`,
`sleep(2.seconds)` (and `sleep(500)` in milliseconds, as everyone expects). Game time is in ticks (20 a second) and
says so: `waitTicks(20)`, `everyTicks(5)`, `title(…, stayTicks = 60)`, `effect.durationTicks`.

Details by topic:

| Page | |
|---|---|
| [Events](events.md) | events and a script's lifetime, coroutines, keys, commands, timers, messages between scripts |
| [Player and world](world.md) | the player, other entities, bindings, raycasts, inventory, containers, interaction, world info and scans, fake blocks, recording input |
| [Drawing and screens](display.md) | shapes in the world, glow outlines, drawing every frame, pixels, styled chat, HUD panels, script screens |
| [Data and the web](data.md) | settings, persistent values, HTTP, Maven libraries, sharing code between scripts |
| [Coming from Fabric](from-fabric.md) | the same mod written as a Fabric mod and as a script; what maps to what |
| [Advanced](advanced.md) | packets, hooking game methods, rewriting classes, code that runs code, Fabric API events (experimental), other mods and Baritone |
| [Errors and logs](errors-and-logs.md) | what failing scripts show, and where logs go |
| [Installing and sharing scripts](sharing.md) | `\install`, `\share`, `\update` |
| [How it works](internals.md) | the engine, limitations, building, self-tests |
