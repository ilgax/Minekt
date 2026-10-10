# API overview

Everything below is in scope in every script, no imports needed.

| Area | API |
|---|---|
| Output | `echo(vararg parts)`, `chat(msg)` (a leading `/` makes it a command), `execute(cmd)`, `hudPanel { … }`, `writeLog(vararg parts)`, `clipboard()`, `setClipboard(text)` |
| Feedback | `playSound(id)`, `toast(title, message)`, `actionBar(text)`, `title(title, subtitle)`, `screenshot(name)` |
| Control | `args`, `jobId`, `sleep(ms)`, `waitTicks(n)`, `stop()` |
| Player | `player` (name, position, yaw/pitch, health, food, xp, air, armor, effects, velocity, onGround, inWater, sneaking, sprinting, flying, gameMode, vehicle, …), `position`, `setOrientation(yaw, pitch)`, `lookAt(x, y, z)` |
| Keybinds | `keybind(name, default)` → `onPress { }`, `onRelease { }`, `isDown`, `key`, `awaitPress()`, `awaitRelease()`, `remove()` |
| Raw input | `onRawKey { }`, `onRawMouse { }`, `onMouseMove { }` (`cancel()`), `tapKey`, `keyDown`, `keyUp`, `typeText`, `clickMouse`, `mouseDown`, `mouseUp`, `scrollMouse`, `moveMouse`; `\record` writes these for you |
| Bindings | `press("inventory")`, `press(Key.FORWARD)`, `release(…)`, `bindings()`: any of the game's bindings, whatever key it's on; released when the script ends |
| Inventory & screens | `inventory()`, `heldItem()`, `offhandItem()`, `selectSlot(n)`, `screen`, `container()`, `clickSlot(i)`, `shiftClick(i)`, `swapWithHotbar(i, n)`, `dropSlot(i)`, `openInventory()`, `closeScreen()` |
| Targeting & interaction | `targetedBlock()`, `targetedEntity()`, `useItem()`, `useOn(pos, face)`, `attack(entityId)`, `interact(entityId)` |
| World | `world` (dimension, timeOfDay, gameTime, isDay, rain, thunder), `getBlock(x, y, z)`, `findBlocks(radius) { id -> … }`, `scanRegion(from, to) { id -> … }`, `isLoaded(x, z)`, `entities(radius)`, `players()` |
| Events | `onChat`, `onTick`, `onDamage`, `onDeath`, `onRespawn`, `onJoinWorld`, `onLeaveWorld`, `onDimensionChange`, `onScreenOpen`, `onScreenClose`, `onChunkLoad`, `onItemGained`, `onBlockChange`, `onEntityAppear`, `onEntityGone`, `onSound`, `onParticle`, `onPlayerJoin`, `onPlayerLeave`, `onScoreboardChange`, `onEvent` |
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
| Method hooks | `hook<Class>("method") { call -> }`, `hookAfter<Class>("method") { call -> }` (change `args`, `cancel(value)`, replace `result`) |
| Code | `eval(code, "name" to value)`, `runScript(name, args)`, `startScript(name, args)` → `await()`, `kill()`, `isRunning` |
| Class rewriting | `rewriteClass<Class> { node -> }` (ASM tree), `transformClass(name) { bytes -> newBytes }`, `classBytes<Class>()` |
| Libraries | `@file:DependsOn("group:artifact:version")`, `@file:Repository("https://…")` |
| World shapes | `highlight(pos)`, `box(min, max)`, `line(a, b)`, `arrow(a, b)`, `text(pos, "…")`, `clearShapes()` |
| Drawing every frame | `onHudRender { g -> g.rect(…); g.text(…); g.item(…); g.image(…) }`, `onWorldRender { w -> w.line(…); w.box(…); w.text(…); w.point(…); w.entityPosition(id) }` |
| Mods | `isModLoaded(id)`, `modVersion(id)`, `baritone.*` |
| Raw access | `game { mc -> … }` runs on the client thread with the live `Minecraft` instance |

Game state comes back as plain data (`Vec3`, `BlockPos`, `PlayerInfo`, `EntityInfo`, …). Those are copies, so
they're safe to keep and read from the script's thread.

Details by topic:

| Page | |
|---|---|
| [Events](events.md) | events and a script's lifetime, coroutines, keys, commands, timers, messages between scripts |
| [Player and world](world.md) | the player, bindings, inventory, containers, interaction, world info and scans, recording input |
| [Drawing and screens](display.md) | shapes in the world, drawing every frame, HUD panels, script screens |
| [Data and the web](data.md) | settings, persistent values, HTTP, Maven libraries, sharing code between scripts |
| [Advanced](advanced.md) | packets, hooking game methods, rewriting classes, code that runs code, other mods and Baritone |
| [Errors and logs](errors-and-logs.md) | what failing scripts show, and where logs go |
| [Installing and sharing scripts](sharing.md) | `\install`, `\share`, `\update` |
| [How it works](internals.md) | the engine, limitations, building, self-tests |
