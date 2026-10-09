package dev.ilgax.minekt.hooks

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundBundlePacket

/**
 * Entry point for the connection mixin. Runs on the network thread for every packet the client
 * receives or sends; [filter] (the job manager) decides what happens to each.
 */
object PacketHooks {
    /** Returns the packet to use instead (the same one to leave it alone), or null to drop it. */
    fun interface Filter {
        fun filter(packet: Packet<*>, incoming: Boolean): Packet<*>?
    }

    @Volatile
    var filter: Filter? = null

    @JvmStatic
    fun incoming(packet: Packet<*>): Packet<*>? {
        val filter = filter ?: return packet
        // A bundle is several packets applied together: filter each one and rebundle if any changed.
        if (packet is ClientboundBundlePacket) {
            val parts = packet.subPackets().toList()
            @Suppress("UNCHECKED_CAST")
            val filtered = parts.mapNotNull { filter.filter(it, true) } as List<Packet<in net.minecraft.network.protocol.game.ClientGamePacketListener>>
            return if (filtered.size == parts.size && filtered.indices.all { filtered[it] === parts[it] }) packet else ClientboundBundlePacket(filtered)
        }
        return filter.filter(packet, true)
    }

    @JvmStatic
    fun outgoing(packet: Packet<*>): Packet<*>? {
        val filter = filter ?: return packet
        return filter.filter(packet, false)
    }
}
