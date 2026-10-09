package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import net.minecraft.network.protocol.Packet

// Raw network packets, both directions. Packet classes are Minecraft's own, e.g.
// net.minecraft.network.protocol.game.ClientboundSetHealthPacket (received) or
// ServerboundChatPacket (sent); import them like any other class.
//
//   onPacket<ClientboundSetHealthPacket> { echo("health now ${it.health()}") }
//   onPacket<Packet<*>> { seen[it.javaClass.simpleName] = (seen[it.javaClass.simpleName] ?: 0) + 1 }
//   interceptPacket<ClientboundSoundPacket> { null }                       // drop every sound
//   interceptPacketSent<ServerboundMovePlayerPacket> { it }                // pass through (or replace)
//
// `onPacket…` listeners run on the script thread after the body, like other events. `intercept…`
// filters run immediately on the network thread, before the game sees the packet: return it (or a
// replacement) to let it through, or null to drop it. Keep filters quick; an exception removes one.

/** Call [handler] with every received packet of type [T]. */
inline fun <reified T : Packet<*>> Minekt.onPacket(noinline handler: (T) -> Unit) = addPacketHook(T::class.java, incoming = true, filter = false) { handler(it as T) }

/** Call [handler] with every packet of type [T] the client sends. */
inline fun <reified T : Packet<*>> Minekt.onPacketSent(noinline handler: (T) -> Unit) = addPacketHook(T::class.java, incoming = false, filter = false) { handler(it as T) }

/** Filter received packets of type [T]: return the packet, a replacement, or null to drop it. */
inline fun <reified T : Packet<*>> Minekt.interceptPacket(noinline filter: (T) -> Packet<*>?) = addPacketHook(T::class.java, incoming = true, filter = true) { filter(it as T) }

/** Filter sent packets of type [T]: return the packet, a replacement, or null to drop it. */
inline fun <reified T : Packet<*>> Minekt.interceptPacketSent(noinline filter: (T) -> Packet<*>?) = addPacketHook(T::class.java, incoming = false, filter = true) { filter(it as T) }

/** Suspend until a packet of type [T] is received (`sent = true`: sent) and return it. */
suspend inline fun <reified T : Packet<*>> Minekt.awaitPacket(sent: Boolean = false): T = awaitPacket(T::class.java, !sent) as T

/** Send a packet to the server (it goes through `interceptPacketSent` filters like any other). */
fun Minekt.sendPacket(packet: Packet<*>) = game { mc ->
    val connection = mc.connection ?: error("Not connected to a world")
    connection.send(packet)
}

/** Returned by the packet functions; [remove] stops listening / filtering. */
class PacketHookHandle internal constructor(private val remove: () -> Unit) {
    fun remove() = remove.invoke()
}

@PublishedApi
internal fun Minekt.addPacketHook(type: Class<*>, incoming: Boolean, filter: Boolean, handler: (Packet<*>) -> Any?): PacketHookHandle {
    job.checkAlive()
    val hook = Job.PacketHook(type, incoming, handler)
    val list = if (filter) job.packetFilters else job.packetHandlers
    list += hook
    return PacketHookHandle { list -= hook }
}

@PublishedApi
internal suspend fun Minekt.awaitPacket(type: Class<*>, incoming: Boolean): Packet<*> =
    (job.awaitEvent { it is Job.Event.Packet && it.incoming == incoming && type.isInstance(it.packet) } as Job.Event.Packet).packet
