# Advanced

## Packets

Raw network packets, both directions. The classes are Minecraft's own (`net.minecraft.network.protocol.game.*`):

```kotlin
import net.minecraft.network.protocol.game.*

onPacket<ClientboundSetHealthPacket> { echo("health now ${it.health}") }
onPacketSent<ServerboundMovePlayerPacket> { moves++ }
interceptPacket<ClientboundSoundPacket> { null }                              // drop every sound
interceptPacket<ClientboundSystemChatPacket> { if ("spam" in it.content().string) null else it }
sendPacket(ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS))
val stats = runBlocking { withTimeoutOrNull(5_000) { awaitPacket<ClientboundAwardStatsPacket>() } }
```

- `onPacket…` listeners run on the script thread after the body, like other events. `onPacket<Packet<*>>` sees
  everything.
- `intercept…` filters run right away on the network thread, before the game sees the packet: return it, a
  replacement, or `null` to drop it. Keep them quick. A filter that throws is removed and the packet goes through.
- Bundled packets are taken apart, so filters and listeners see each packet inside a bundle.

## Hooking game methods

Run code before or after any method of Minecraft or another mod, even though the game is already running:

```kotlin
hook<LivingEntity>("jumpFromGround") { call -> if (call.self is LocalPlayer) echo("jump!") }
hook<LivingEntity>("jumpFromGround") { it.cancel() }                         // skip the method
hookAfter<LivingEntity>("getSpeed") { it.result = (it.result as Float) * 2 }  // change what it returns
hook<Hud>("setOverlayMessage", Component::class.java, Boolean::class.java) {   // overloaded: name the types
    it.args[0] = Component.literal("rewritten")
}
```

- `call.self` is the object (null for static methods), `call.args` the arguments (change them in `hook`),
  `call.cancel(value)` skips the method and returns `value`, and in `hookAfter`, `call.result` can be replaced.
  Numbers are converted to the method's types for you.
- Handlers run right then, on whichever thread called the method (usually the game thread or the integrated
  server's), so keep them quick. Calls a handler makes to hooked methods don't trigger hooks again.
- Only the class that declares the method is hooked, not subclasses that override it: hook the class that has
  the code. A class name works too: `hook("net.minecraft.client.player.LocalPlayer", "aiStep") { … }`.
- Hooks come off when the script ends (or `handle.remove()`), and the method's original code is put back. A
  handler that throws is removed.
- How: Minekt attaches a Java agent at runtime and rewrites the method. That needs a Java runtime that allows
  attaching agents (a full JDK does; some minimal runtimes don't, and then `hook` says so).

## Other mods (Baritone and anything else)

Scripts compile against **every loaded mod's jar**, so any mod's public API can be used directly:

```kotlin
import baritone.api.BaritoneAPI

game { BaritoneAPI.getProvider().primaryBaritone.commandManager.execute("mine diamond_ore") }
```

For Baritone there is also a built-in helper. It uses reflection, so it works with whatever Baritone version
is installed, and Minekt runs fine without it:

```kotlin
if (!baritone.isAvailable) stop()
baritone.goto(100, 64, -20)        // also: mine(…), follow(player), explore(), cancel(), command("…")
baritone.await()                   // block until Baritone is done
echo("arrived at ${player.position}")
```

Notes:
- **Only what the client has.** In multiplayer, server-side mods aren't reachable. In singleplayer the built-in
  server runs in the same process, so `game { it.singleplayerServer }` gets you there.
- **Missing mods are compile errors.** A script that imports a mod which isn't installed won't compile. Guard
  it with `isModLoaded(…)` plus reflection if it should run either way.
- **Threads.** Scripts run on their own thread. Touch the game or another mod only inside `game { }`.

## Raw access

`game { mc -> … }` runs a block on the client thread with the live `Minecraft` instance and returns its result:
anything the game can do, a script can do from there.
