// Server half of the real-player test (dev/selftest/realplayer.kts joins with a client): ops whoever joins, and
// gives the client commands that exercise the server's packet hooks. Start it from the server's autorun.txt.
@file:OptIn(ExperimentalPackets::class)

import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.network.protocol.game.ServerboundChatPacket

onEvent<ServerEvent.PlayerJoin> { (name) -> runCommand("op $name") }

// A packet from the player, seen with who sent it.
onPacket<ServerboundChatPacket> { packet, player ->
    if (packet.message() == "pkt ping") tell(player, "REALSRV packet from $player: ${packet.message()}")
}

// A packet to the player, dropped on the way out.
interceptPacketSent<ClientboundSystemChatPacket> { packet, _ -> if ("secret" in packet.content().string) null else packet }

command("sendpkt") { call -> sendPacket(call.sender!!, ClientboundSetActionBarTextPacket(Component.literal("REALSRV action bar"))) }
command("secrettest") { call ->
    tell(call.sender!!, "REALSRV secret line")
    tell(call.sender!!, "REALSRV visible line")
}
command("deopme") { call -> runCommand("deop ${call.sender}") }
