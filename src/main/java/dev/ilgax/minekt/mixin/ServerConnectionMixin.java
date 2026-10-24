package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.ServerPacketHooks;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets server scripts see, change and cancel the packets of players in the game (see {@link ServerPacketHooks}): the
 * server's side of each connection, also singleplayer's integrated server. Loaded on both sides.
 */
@Mixin(Connection.class)
public abstract class ServerConnectionMixin {
    /** Set while re-entering with a replaced packet, so it isn't filtered twice. */
    private static final ThreadLocal<Boolean> MINEKT_SERVER_REENTRY = ThreadLocal.withInitial(() -> false);

    @Shadow
    public abstract PacketFlow getReceiving();

    @Shadow
    public abstract PacketListener getPacketListener();

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext context, Packet<?> packet);

    @Shadow
    public abstract void send(Packet<?> packet, ChannelFutureListener listener, boolean flush);

    /** The player in the game on the other end, or null (not the server's side, or still logging in). */
    private String minekt$player() {
        if (getReceiving() != PacketFlow.SERVERBOUND || ServerPacketHooks.getFilter() == null || MINEKT_SERVER_REENTRY.get()) return null;
        return getPacketListener() instanceof ServerGamePacketListenerImpl game ? game.player.getName().getString() : null;
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void minekt$onServerReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        String player = minekt$player();
        if (player == null) return;
        Packet<?> result = ServerPacketHooks.incoming(packet, player);
        if (result == packet) return;
        ci.cancel();
        if (result == null) return;
        MINEKT_SERVER_REENTRY.set(true);
        try {
            channelRead0(context, result);
        } finally {
            MINEKT_SERVER_REENTRY.set(false);
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void minekt$onServerSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        String player = minekt$player();
        if (player == null) return;
        Packet<?> result = ServerPacketHooks.outgoing(packet, player);
        if (result == packet) return;
        ci.cancel();
        if (result == null) return;
        MINEKT_SERVER_REENTRY.set(true);
        try {
            send(result, listener, flush);
        } finally {
            MINEKT_SERVER_REENTRY.set(false);
        }
    }
}
