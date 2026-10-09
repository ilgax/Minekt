package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.toInfo
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries

/**
 * Turns changes in client state into [GameEvent]s. Most are found by comparing against the
 * previous tick, which needs no mixins and is robust across game versions.
 */
internal class GameEventSource(private val jobs: JobManager, private val onJoin: () -> Unit) {
    private var player: LocalPlayer? = null
    private var dimension: String? = null
    private var health = 0f
    private var dead = false
    private var screen: Screen? = null
    private var inventory: Map<String, Int>? = null

    fun register() {
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            emit(GameEvent.ChunkLoad(chunk.pos.x(), chunk.pos.z()))
        }
    }

    /** Client thread, end of every tick. */
    fun tick(mc: Minecraft) {
        val current = mc.player
        val level = mc.level
        if (current == null || level == null) {
            if (player != null) emit(GameEvent.LeaveWorld)
            player = null
            dimension = null
            inventory = null
            return
        }
        val dim = level.dimension().identifier().toString()
        when {
            player == null -> {
                emit(GameEvent.JoinWorld(dim))
                onJoin()
            }
            dimension != null && dim != dimension -> emit(GameEvent.DimensionChange(dimension!!, dim))
        }
        if (current !== player) {
            // New player entity: joined, respawned, or changed dimension. Reset baselines.
            health = current.health
            inventory = null
        }
        player = current
        dimension = dim

        val nowDead = current.isDeadOrDying
        if (current.health < health && health > 0f) emit(GameEvent.Damage(health - current.health, current.health))
        if (nowDead && !dead) emit(GameEvent.Death)
        if (!nowDead && dead) emit(GameEvent.Respawn)
        dead = nowDead
        health = current.health

        val nowScreen = mc.gui.screen()
        if (nowScreen !== screen) {
            emit(if (nowScreen == null) GameEvent.ScreenClose else GameEvent.ScreenOpen(nowScreen.toInfo()))
            screen = nowScreen
        }

        val counts = HashMap<String, Int>()
        val inv = current.inventory
        for (slot in 0 until inv.containerSize) {
            val stack = inv.getItem(slot)
            if (!stack.isEmpty) counts.merge(BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count, Int::plus)
        }
        inventory?.let { before ->
            for ((id, count) in counts) {
                val gained = count - (before[id] ?: 0)
                if (gained > 0) emit(GameEvent.ItemGained(id, gained))
            }
        }
        inventory = counts
    }

    private fun emit(event: GameEvent) = jobs.dispatch(Job.Event.Game(event))
}
