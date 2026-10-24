# Coming from Fabric

A Minekt script is Kotlin running inside the game, with the same classes a Fabric mod uses: Minecraft's own
names (`LocalPlayer`, `KeyMapping`, `ClientboundSetHealthPacket`…), Fabric API, and any other mod's code. Mod
tutorials, Fabric's docs and the decompiled game all apply. What changes is how you get in: no project, no
build, no restart.

## The same things, spelled differently

| In a Fabric mod | In a script |
|---|---|
| A Gradle project, `fabric.mod.json`, an entrypoint class | One `.kts` file in `.minecraft/minekt/`; the file is the entrypoint |
| Build, copy the jar, restart the game | Save, type `\name` in chat |
| `onInitializeClient()` | The script's body |
| Code runs on the game thread | The body runs on its own thread; `game { mc -> … }` runs on the game thread ([why](#threads)) |
| `ClientTickEvents.END_CLIENT_TICK.register { }` | The same ([Fabric API events](advanced.md#fabric-api-events)), taken out again when the script ends; or `onTick { }` |
| `KeyMappingHelper.registerKeyMapping(KeyMapping(…))` | `keybind("Name", default = "b")`: in the same Controls menu |
| `ClientCommandRegistrationCallback` + Brigadier | `command("name") { args -> }` |
| `HudElementRegistry.addLast(…) { graphics, delta -> }` | `onHudRender { g -> }` (`g.raw` is the game's drawing context) |
| A mixin, `@Inject(at = @At("HEAD"), cancellable = true)` | `hook<Class>("method") { call -> call.cancel() }` ([hooks](advanced.md#hooking-game-methods)) |
| A mixin at `RETURN`, changing the result | `hookAfter<Class>("method") { call -> call.result = … }` |
| `@Redirect`, `@ModifyConstant`, a full transformer | `rewriteClass<Class> { node -> … }`: the class as an ASM tree |
| Access wideners, `@Accessor` mixins for private fields | Reflection: `getDeclaredField(…).apply { isAccessible = true }` |
| A mixin on `Connection` to read packets | `onPacket<ClientboundSetHealthPacket> { }`, `interceptPacket<…> { }` |
| `dependencies { implementation(…) }` | `@file:DependsOn("group:artifact:version")` |
| Config libraries and a config screen | `var x by setting(default)`: shown under the script's Settings in `\menu` |
| Saving your own files | `val x by persistent { default }` |
| Undoing nothing: a mod lives until the game closes | Everything a script adds comes off when it ends; `onStop { }` for the rest |

## One mod, written both ways

A small mod: B toggles full bright, the action bar warns once when health drops below 3 hearts, and you can't
jump while sneaking.

### As a Fabric mod

```kotlin
// src/client/kotlin/example/QuickTools.kt
object QuickTools : ClientModInitializer {
    private var bright = false
    private var warned = false

    override fun onInitializeClient() {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("quicktools", "main"))
        val toggle = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.quicktools.bright", InputConstants.Type.KEYBOARD, InputConstants.getKey("key.keyboard.b").value, category),
        )
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            while (toggle.consumeClick()) {
                bright = !bright
                mc.options.gamma().set(if (bright) 1.0 else 0.5)
            }
            val player = mc.player ?: return@register
            if (player.health < 6 && !warned) player.sendOverlayMessage(Component.literal("Low health!"))
            warned = player.health < 6
        }
    }
}
```

```java
// src/client/java/example/mixin/NoSneakJumpMixin.java
@Mixin(LivingEntity.class)
public abstract class NoSneakJumpMixin {
    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void quicktools$noSneakJump(CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player && player.isShiftKeyDown()) ci.cancel();
    }
}
```

Plus `fabric.mod.json` (the entrypoint, the mixin config, dependencies), `quicktools.client.mixins.json`, a
lang file for the key's name, `build.gradle` and `gradle.properties`. Then build, copy the jar to `mods/`, and
restart Minecraft for every change.

### As a script

```kotlin
// quicktools.kts
@file:OptIn(ExperimentalHooks::class)

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.player.LocalPlayer
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.LivingEntity

var bright = false
var warned = false

keybind("Full bright", default = "b").onPress {
    bright = !bright
    game { mc -> mc.options.gamma().set(if (bright) 1.0 else 0.5) }
}

ClientTickEvents.END_CLIENT_TICK.register { mc ->
    val player = mc.player ?: return@register
    if (player.health < 6 && !warned) player.sendOverlayMessage(Component.literal("Low health!"))
    warned = player.health < 6
}

hook<LivingEntity>("jumpFromGround") { call ->
    val self = call.self
    if (self is LocalPlayer && self.isShiftKeyDown) call.cancel()
}

onStop { game { mc -> mc.options.gamma().set(0.5) } }
```

Save it in `.minecraft/minekt/` and type `\quicktools`. Change it and run it again: the old version's
keybind, listener and hook are gone first. `\kill quicktools` takes it all off, and `onStop` puts the
brightness back; a mod has no way to be switched off without a restart.

The tick listener's body and the mixin's body moved over unchanged. The keybind is the one place a script
does it differently: `onPress` runs on the script's own thread, so it touches the game through `game { }`.

## What's actually different

### Threads

A mod's code runs on the game thread, so it must never block: no `Thread.sleep`, no waiting for the next
tick, nothing slow. A script's body runs on its own thread instead, so it can do exactly that:

```kotlin
press("forward"); sleep(2000); release("forward")
val msg = awaitChat { "ready" in it.text }
```

and touch the game through `game { mc -> … }`, which runs the block on the game thread and returns its
result. Code that the game calls (Fabric event listeners, `hook` handlers, `onHudRender`, `onRawKey`) runs on
the game thread like a mod's, where `game { }` runs straight away, and must stay quick.

That's also why `\kill` always works: Minekt can stop a script's thread even in `while (true) { }`, without
touching the game.

### Mixins are hooks

Real mixins are applied as classes load, before the game starts, so a script can't add one later. `hook`,
`hookAfter` and `rewriteClass` do the same jobs on classes that are already loaded (Minekt attaches a Java
agent and rewrites the method), and come off again when the script ends:

| Mixin | Script |
|---|---|
| `@Inject(at = @At("HEAD"))`, `ci.cancel()` | `hook<T>("method") { call -> call.cancel() }` |
| `@Inject(at = @At("RETURN"))`, `cir.setReturnValue(x)` | `hookAfter<T>("method") { call -> call.result = x }` |
| `@ModifyArg`, `@ModifyVariable` on a parameter | `hook<T>("method") { call -> call.args[0] = … }` |
| `this` inside the mixin | `call.self` |
| `@Shadow` a field | `call.self` and reflection |
| `@Redirect`, `@ModifyConstant`, `@At("INVOKE")` | `rewriteClass<T> { node -> … }` |

Hook the class that declares the method, as a mixin targets it: `LivingEntity` for `jumpFromGround`, not
`LocalPlayer`.

### Events

Fabric API events work as in a mod. Minekt also has its own events,
which keep the script alive and run on the script's thread: `onTick`, `onChat`, `onDamage { (amount, health,
cause, attacker) -> }`, `onBlockChange`, `onEntityAppear`, `onPacket`… Use whichever reads better; on the client,
some of Minekt's (like `onDamage`) have no Fabric equivalent.

### No `fabric.mod.json`

A script has no id, version or dependency list. `isModLoaded("sodium")` and `modVersion(…)` check for other
mods at runtime, and `@file:DependsOn` pulls Maven libraries.

## From a script to a mod

A script that grew big can become a mod with mostly copy and paste: the body goes into
`onInitializeClient()`, `game { mc -> … }` becomes plain code on the game thread (and anything that waited, like
`sleep` or `awaitChat`, becomes state checked each tick), `keybind` becomes a `KeyMapping`, and each `hook`
becomes a mixin.
