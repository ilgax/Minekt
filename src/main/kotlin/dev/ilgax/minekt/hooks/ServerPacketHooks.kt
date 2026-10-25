package dev.ilgax.minekt.hooks

import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBundlePacket

/**
 * Entry point for the server connection mixin. Runs on the network thread for every packet the server receives from a
 * player in the game ([incoming]) or sends them; [filter] (the server's job manager) decides what happens to each.
 * Loaded on both sides: nothing here may touch client classes.
 */
object ServerPacketHooks {
    /** Returns the packet to use instead (the same one to leave it alone), or null to drop it. */
    fun interface Filter {
        fun filter(packet: Packet<*>, incoming: Boolean, player: String): Packet<*>?
    }

    @Volatile
    @JvmStatic
    var filter: Filter? = null

    @JvmStatic
    fun incoming(packet: Packet<*>, player: String): Packet<*>? {
        val filter = filter ?: return packet
        return filter.filter(packet, true, player)
    }

    @JvmStatic
    fun outgoing(packet: Packet<*>, player: String): Packet<*>? {
        val filter = filter ?: return packet
        // A bundle is several packets applied together: filter each one and rebundle if any changed.
        if (packet is ClientboundBundlePacket) {
            val parts = packet.subPackets().toList()
            @Suppress("UNCHECKED_CAST")
            val filtered = parts.mapNotNull { filter.filter(it, false, player) } as List<Packet<in ClientGamePacketListener>>
            return if (filtered.size == parts.size && filtered.indices.all { filtered[it] === parts[it] }) packet else ClientboundBundlePacket(filtered)
        }
        return filter.filter(packet, false, player)
    }
}
