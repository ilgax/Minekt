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

## Rewriting classes

Replace the bytecode of any class, while the game runs. You get the bytes and return new ones; Minekt
doesn't check or fix anything in between.

```kotlin
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

// ASM's tree API: change the ClassNode in place.
val rewrite = rewriteClass<Minecraft> { node ->
    val method = node.methods.first { it.name == "getLaunchedVersion" }
    method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables?.clear()
    method.instructions.add(LdcInsnNode("my version"))
    method.instructions.add(InsnNode(Opcodes.ARETURN))
}
rewrite.remove()                                                    // the original code is back

transformClass("net.minecraft.client.player.LocalPlayer") { bytes -> myBytes(bytes) }  // raw bytes; null = unchanged
val bytes = classBytes<ClientLevel>()                               // what the JVM runs now
```

- You get the class as it was defined, after Fabric's and mixins' changes, plus earlier rewrites by any
  script, in order. `rewriteClass` writes it back with stack map frames computed; `transformClass` takes your
  bytes as they are.
- **Loaded classes** change right away. The JVM only lets a loaded class change its method bodies: adding,
  removing or renaming methods or fields, or changing the superclass or interfaces, is refused, and
  `rewriteClass` / `transformClass` throw with the JVM's reason. Errors in your code throw there too, and the class
  is left as it was.
- **Classes that aren't loaded yet** are changed when they load, and then anything goes: new methods, fields,
  interfaces. Your code then runs on whichever thread loads the class. If it throws, the class loads unchanged
  and the error goes to the script's log.
- A rewrite lasts until `remove()` or the script ends, and keeps the script alive meanwhile. Removing one
  re-applies the others.
- Method hooks still work on rewritten methods: they wrap the new code.
- `classBytes` is handy with `ClassReader`, or saved to a file and read with `javap -c`.
- It uses the same runtime Java agent as method hooks, so it needs a full JDK too.

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
