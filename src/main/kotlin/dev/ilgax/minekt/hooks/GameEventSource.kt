package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.toInfo
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.registries.BuiltInRegistries
import dev.ilgax.minekt.api.Sidebar
import dev.ilgax.minekt.api.Vec3
import dev.ilgax.minekt.api.readSidebar
import dev.ilgax.minekt.api.readTabList
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Turns changes in client state into [GameEvent]s. Most are found by comparing against the
 * previous tick, which needs no mixins and is robust across game versions.
 */
internal class GameEventSource(
    private val jobs: JobManager,
    private val onJoin: (Minecraft) -> Unit,
    private val onDimensionChange: (Minecraft) -> Unit,
) {
    private var player: LocalPlayer? = null
    private var dimension: String? = null
    private var health = 0f
    private var dead = false
    private var screen: Screen? = null
    private var inventory: Map<String, Int>? = null

    private var tabList: Set<String>? = null
    private var sidebar: Sidebar? = null
    private var sidebarKnown = false
    private var ticks = 0
    private val appeared = mutableListOf<Entity>()

    fun register() {
        WorldHooks.emit = ::emit
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk ->
            if (jobs.wantsGame(GameEvent.ChunkLoad::class.java)) emit(GameEvent.ChunkLoad(chunk.pos.x(), chunk.pos.z()))
        }
        // Reported at the end of the tick: the entity's data (custom name, health, …) arrives just after it's added.
        ClientEntityEvents.ENTITY_LOAD.register { entity, _ ->
            if (entity !is LocalPlayer && jobs.wantsGame(GameEvent.EntityAppear::class.java)) appeared += entity
        }
        ClientEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            if (entity !is LocalPlayer && jobs.wantsGame(GameEvent.EntityGone::class.java)) {
                val died = (entity as? LivingEntity)?.isDeadOrDying == true || entity.removalReason == Entity.RemovalReason.KILLED
                emit(GameEvent.EntityGone(entity.toInfo(Minecraft.getInstance().player), died))
            }
        }
    }

    /** The last damage event the server sent for any entity, with the tick it came in: matched to a health drop in [tick]. */
    @Volatile private var lastDamagePacket: Pair<ClientboundDamageEventPacket, Int>? = null

    /** Network thread, every packet received (after scripts' filters). */
    fun packetReceived(packet: Packet<*>) {
        if (packet is ClientboundDamageEventPacket && packet.entityId() == Minecraft.getInstance().player?.id) {
            lastDamagePacket = packet to ticks
        }
        if (packet is ClientboundLevelParticlesPacket && jobs.wantsGame(GameEvent.Particle::class.java)) {
            val id = BuiltInRegistries.PARTICLE_TYPE.getKey(packet.particle().type).toString()
            emit(GameEvent.Particle(id, Vec3(packet.x(), packet.y(), packet.z()), packet.count()))
        }
    }

    /** Tab list and sidebar, compared every half second while someone listens. */
    private fun pollLists(mc: Minecraft) {
        if (jobs.wantsGame(GameEvent.PlayerJoin::class.java) || jobs.wantsGame(GameEvent.PlayerLeave::class.java)) {
            val now = readTabList(mc).toSet()
            tabList?.let { before ->
                (now - before).sorted().forEach { emit(GameEvent.PlayerJoin(it)) }
                (before - now).sorted().forEach { emit(GameEvent.PlayerLeave(it)) }
            }
            tabList = now
        } else tabList = null
        if (jobs.wantsGame(GameEvent.ScoreboardChange::class.java)) {
            val now = readSidebar(mc)
            if (sidebarKnown && now != sidebar) emit(GameEvent.ScoreboardChange(now?.title.orEmpty(), now?.lines.orEmpty()))
            sidebar = now
            sidebarKnown = true
        } else sidebarKnown = false
    }

    /**
     * A health drop, with what caused it if the server said so (its damage event arrives just before the
     * health update; one older than a second is someone else's hit, already over).
     */
    private fun damage(amount: Float, player: LocalPlayer, level: ClientLevel): GameEvent.Damage {
        val (packet, at) = lastDamagePacket ?: return GameEvent.Damage(amount, player.health)
        lastDamagePacket = null
        if (packet.entityId() != player.id || ticks - at > 20) return GameEvent.Damage(amount, player.health)
        val source = runCatching { packet.getSource(level) }.getOrNull()
        val attacker = source?.entity
        val direct = source?.directEntity?.takeIf { it !== attacker }
        return GameEvent.Damage(
            amount, player.health,
            cause = packet.sourceType().registeredName,
            attacker = attacker?.toInfo(player),
            projectile = direct?.toInfo(player),
        )
    }

    /** Client thread, end of every tick. */
    fun tick(mc: Minecraft) {
        WorldHooks.wantBlocks = jobs.wantsGame(GameEvent.BlockChange::class.java)
        WorldHooks.wantSounds = jobs.wantsGame(GameEvent.Sound::class.java)
        if (++ticks % 10 == 0 && mc.level != null) pollLists(mc)
        if (appeared.isNotEmpty()) {
            appeared.filterNot { it.isRemoved }.forEach { emit(GameEvent.EntityAppear(it.toInfo(mc.player))) }
            appeared.clear()
        }
        val current = mc.player
        val level = mc.level
        if (current == null || level == null) {
            if (player != null) emit(GameEvent.LeaveWorld)
            player = null
            dimension = null
            inventory = null
            tabList = null
            sidebarKnown = false
            return
        }
        val dim = level.dimension().identifier().toString()
        when {
            player == null -> {
                emit(GameEvent.JoinWorld(dim))
                onJoin(mc)
            }
            dimension != null && dim != dimension -> {
                emit(GameEvent.DimensionChange(dimension!!, dim))
                onDimensionChange(mc)
            }
        }
        if (current !== player) {
            // New player entity: joined, respawned, or changed dimension. Reset baselines.
            health = current.health
            inventory = null
        }
        player = current
        dimension = dim

        val nowDead = current.isDeadOrDying
        if (current.health < health && health > 0f) emit(damage(health - current.health, current, level))
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
