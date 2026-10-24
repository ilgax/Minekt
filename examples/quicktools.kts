// \quicktools — B toggles full bright, the action bar warns once below 3 hearts, and you can't jump while
// sneaking. The same small mod written as a Fabric mod and as this script: docs/from-fabric.md.
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
