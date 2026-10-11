# API overview

Everything below is in scope in every script, no imports needed.

| Area | API |
|---|---|
| Output | `echo(vararg parts)` (plain, or styled with `chatText(text, color, bold, hover, click = ChatClick.run { })`), `chat(msg)` (a leading `/` makes it a command), `execute(cmd)`, `hudPanel { … }`, `writeLog(vararg parts)`, `clipboard()`, `setClipboard(text)` |
| Feedback | `playSound(id)`, `toast(title, message)`, `actionBar(text)`, `title(title, subtitle)`, `screenshot(name)` |
| Control | `args`, `jobId`, `sleep(ms)`, `waitTicks(n)`, `stop()` |
| Player | `player` (name, position, eyePosition, lookDirection, yaw/pitch, health, food, xp, air, armor, effects, velocity, onGround, inWater, sneaking, sprinting, flying, gameMode, vehicle, …), `position`, `setOrientation(yaw, pitch)`, `lookAt(x, y, z)` |
| Keybinds | `keybind(name, default)` → `onPress { }`, `onRelease { }`, `isDown`, `key`, `awaitPress()`, `awaitRelease()`, `remove()` |
| Raw input | `onRawKey { }`, `onRawMouse { }`, `onMouseMove { }` (`cancel()`), `tapKey`, `keyDown`, `keyUp`, `typeText`, `clickMouse`, `mouseDown`, `mouseUp`, `scrollMouse`, `moveMouse`; `\record` writes these for you |
| Bindings | `press("inventory")`, `press(Key.FORWARD)`, `release(…)`, `bindings()`: any of the game's bindings, whatever key it's on; released when the script ends |
| Inventory & screens | `inventory()` (items with name, durability, enchantments, lore, nutrition…), `heldItem()`, `offhandItem()`, `selectSlot(n)`, `screen`, `container()`, `clickSlot(i)`, `shiftClick(i)`, `swapWithHotbar(i, n)`, `dropSlot(i)`, `openInventory()`, `closeScreen()` |
| Targeting & interaction | `raycast(from, to)` → `RayHit.Block` / `RayHit.Entity` / null, `targetedBlock()`, `targetedEntity()`, `useItem()`, `useOn(pos, face)`, `attack(entityId)`, `interact(entityId)` |
| World | `world` (dimension, timeOfDay, gameTime, isDay, rain, thunder), `getBlock(x, y, z)`, `blockAt(pos)` (properties, light, biome, hardness, sign text), `findBlocks(radius) { id -> … }`, `scanRegion(from, to) { id -> … }`, `isLoaded(x, z)`, `entities(radius)` (type, name, health, equipment, isBaby, customName, velocity, …), `entity(id)`, `players()` |
| Events | `onChat`, `onTick`, `onStop { reason -> }`, `onDamage { amount, health[, cause, attacker] -> }`, `onDeath`, `onRespawn`, `onJoinWorld`, `onLeaveWorld`, `onDimensionChange`, `onScreenOpen`, `onScreenClose`, `onChunkLoad`, `onItemGained`, `onBlockChange`, `onEntityAppear`, `onEntityGone`, `onSound`, `onParticle`, `onPlayerJoin`, `onPlayerLeave`, `onScoreboardChange`, `onEvent` |
| Server info | `tabList()`, `sidebar()` |
| Settings | `var x by setting(default, …)`, `onSettingChange { key, value -> }` |
| Persistence | `val x by persistent { default }` (options `perWorld`, `store`, `key`), `save()` |
| Web | `httpGet(url)`, `httpPost(url, body)`, `http(method, url, body)` → `status`, `body`, `ok`, `json<T>()` |
| Coroutines | `launch { }`, `async { }`, `awaitTicks(n)`, `awaitChat { }`, `awaitEvent { }`, `awaitEventOf<T>()`, `awaitScreen { }` (+ all of `kotlinx.coroutines`) |
| Commands | `command("name", completions) { args -> }` registers a real `/name` |
| Screens | `showScreen(title) { text(…); toggle(…); slider(…); textField(…); choice(…); button(…) { }; row { } }` |
| Messages | `publish(topic, value)`, `subscribe<T>(topic) { }`, `awaitMessage<T>(topic)`, `lastMessage<T>(topic)` |
| Timers | `every(5.seconds) { }`, `after(30.seconds) { }`, `everyTicks(n) { }`, `afterTicks(n) { }`, `atDaytime(13000) { }` |
| Packets | `onPacket<T> { }`, `onPacketSent<T> { }`, `interceptPacket<T> { it or null }`, `interceptPacketSent<T> { }`, `awaitPacket<T>()`, `sendPacket(p)` |
| Fabric events | `@file:OptIn(ExperimentalFabricEvents::class)`, then `ClientTickEvents.END_CLIENT_TICK.register { }` like in a mod; removed when the script ends (experimental) |
| Method hooks | `hook<Class>("method") { call -> }`, `hookAfter<Class>("method") { call -> }` (change `args`, `cancel(value)`, replace `result`) |
| Code | `eval(code, "name" to value)`, `runScript(name, args)`, `startScript(name, args)` → `await()`, `kill()`, `isRunning` |
| Class rewriting | `rewriteClass<Class> { node -> }` (ASM tree), `transformClass(name) { bytes -> newBytes }`, `classBytes<Class>()` |
| Libraries | `@file:DependsOn("group:artifact:version")`, `@file:Repository("https://…")` |
| World shapes | `highlight(pos)`, `box(min, max)`, `line(a, b)`, `arrow(a, b)`, `text(pos, "…")`, `clearShapes()` |
| Glow outlines | `glow(entity, color)`, `glowBlock(pos, color, fill)` → `color`, `remove()` |
| Fake blocks | `fakeBlock(pos, "minecraft:glass")`, `fakeBlocks(map)`, `clearFakeBlocks()` → `remove()` |
| Pixels | `pixels(w, h)` → `[x, y] = color`, `fill`, `clear`, `data` + `changed()`; drawn with `g.pixels(p, x, y, w, h)` |
| Drawing every frame | `onHudRender { g -> g.rect(…); g.text(…); g.item(…); g.image(…) }`, `onWorldRender { w -> w.line(…); w.box(…); w.text(…); w.point(…); w.entityPosition(id) }` |
| Mods | `isModLoaded(id)`, `modVersion(id)`, `baritone.*` |
| Raw access | `game { mc -> … }` runs on the client thread with the live `Minecraft` instance |

Game state comes back as plain data (`Vec3`, `BlockPos`, `PlayerInfo`, `EntityInfo`, …). Those are copies, so
they're safe to keep and read from the script's thread.

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
