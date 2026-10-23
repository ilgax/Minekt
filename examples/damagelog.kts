// \damagelog — says what hurt you, and who: fall damage, lava, the mob or player that hit you, the
// arrow and who shot it. Stays running until you \kill it.
// Shows: a vanilla packet read directly (onPacket), game { } for the details, styled chat.
@file:OptIn(ExperimentalPackets::class)

import net.minecraft.network.protocol.game.ClientboundDamageEventPacket

onPacket<ClientboundDamageEventPacket> { packet ->
    val report = game { mc ->
        val me = mc.player ?: return@game null
        if (packet.entityId() != me.id) return@game null // the server sends these for every entity you can see
        val source = packet.getSource(mc.level!!)
        Triple(
            packet.sourceType().registeredName.substringAfter(':').replace('_', ' '),
            source.entity?.name?.string,       // who caused it (the archer)
            source.directEntity?.name?.string, // what hit you (the arrow)
        )
    } ?: return@onPacket
    val (cause, who, what) = report
    val by = when {
        who != null && what != null && what != who -> " by $who's $what"
        who != null -> " by $who"
        else -> ""
    }
    echo(chatText("Hurt: ", Color.RED) + chatText("$cause$by", Color.WHITE), chatText("(${player.health.toInt()} ♥ left)", 0xAAAAAA))
}
echo("Logging damage. \\kill to stop.")
