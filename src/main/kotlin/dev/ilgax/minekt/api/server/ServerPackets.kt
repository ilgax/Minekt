@file:OptIn(dev.ilgax.minekt.api.ExperimentalPackets::class)

package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.ExperimentalPackets
import dev.ilgax.minekt.api.PacketHookHandle
import dev.ilgax.minekt.api.packetHook
import net.minecraft.network.protocol.Packet

// Raw network packets between the server and players in the game. Packet classes are Minecraft's own, e.g.
// net.minecraft.network.protocol.game.ServerboundChatPacket (received from a player) or ClientboundSetTimePacket
// (sent to one); import them like any other class.
//
//   onPacket<ServerboundChatPacket> { packet, player -> echo(player, "said", packet.message()) }
//   interceptPacketSent<ClientboundSoundPacket> { packet, player -> if (player == "Steve") null else packet }
//   sendPacket("Steve", ClientboundSetTitleTextPacket(Component.literal("Hi")))
//
// `onPacket…` listeners run on the script thread after the body, like other events. `intercept…` filters run
// immediately on the network thread, before the game sees the packet: return it (or a replacement) to let it through,
// or null to drop it. Keep filters quick; an exception removes one.

/** Call [handler] with every packet of type [T] a player sends the server, and who sent it. */
@ExperimentalPackets
inline fun <reified T : Packet<*>> ServerMinekt.onPacket(noinline handler: (packet: T, player: String) -> Unit): PacketHookHandle =
    packetHook(T::class.java, incoming = true, filter = false) { packet, player -> handler(packet as T, player!!) }

/** Call [handler] with every packet of type [T] the server sends a player, and to whom. */
@ExperimentalPackets
inline fun <reified T : Packet<*>> ServerMinekt.onPacketSent(noinline handler: (packet: T, player: String) -> Unit): PacketHookHandle =
    packetHook(T::class.java, incoming = false, filter = false) { packet, player -> handler(packet as T, player!!) }

/** Filter packets of type [T] from players: return the packet, a replacement, or null to drop it. */
@ExperimentalPackets
inline fun <reified T : Packet<*>> ServerMinekt.interceptPacket(noinline filter: (packet: T, player: String) -> Packet<*>?): PacketHookHandle =
    packetHook(T::class.java, incoming = true, filter = true) { packet, player -> filter(packet as T, player!!) }

/** Filter packets of type [T] to players: return the packet, a replacement, or null to drop it. */
@ExperimentalPackets
inline fun <reified T : Packet<*>> ServerMinekt.interceptPacketSent(noinline filter: (packet: T, player: String) -> Packet<*>?): PacketHookHandle =
    packetHook(T::class.java, incoming = false, filter = true) { packet, player -> filter(packet as T, player!!) }

/** Send [packet] to [player] (it goes through `interceptPacketSent` filters like any other). Throws if they aren't online. */
@ExperimentalPackets
fun ServerMinekt.sendPacket(player: String, packet: Packet<*>) = server { s ->
    online(s, player).connection.send(packet)
}
