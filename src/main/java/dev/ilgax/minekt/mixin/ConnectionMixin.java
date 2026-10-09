package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.PacketHooks;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets scripts see, change and cancel the client's packets (see {@link PacketHooks}). Only the
 * client's own connection is touched, not the integrated server's.
 */
@Mixin(Connection.class)
public abstract class ConnectionMixin {
    /** Set while re-entering with a replaced packet, so it isn't filtered twice. */
    private static final ThreadLocal<Boolean> MINEKT_REENTRY = ThreadLocal.withInitial(() -> false);

    @Shadow
    public abstract PacketFlow getReceiving();

    @Shadow
    protected abstract void channelRead0(ChannelHandlerContext context, Packet<?> packet);

    @Shadow
    public abstract void send(Packet<?> packet, ChannelFutureListener listener, boolean flush);

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void minekt$onReceive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        if (getReceiving() != PacketFlow.CLIENTBOUND || MINEKT_REENTRY.get()) return;
        Packet<?> result = PacketHooks.incoming(packet);
        if (result == packet) return;
        ci.cancel();
        if (result == null) return;
        MINEKT_REENTRY.set(true);
        try {
            channelRead0(context, result);
        } finally {
            MINEKT_REENTRY.set(false);
        }
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void minekt$onSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (getReceiving() != PacketFlow.CLIENTBOUND || MINEKT_REENTRY.get()) return;
        Packet<?> result = PacketHooks.outgoing(packet);
        if (result == packet) return;
        ci.cancel();
        if (result == null) return;
        MINEKT_REENTRY.set(true);
        try {
            send(result, listener, flush);
        } finally {
            MINEKT_REENTRY.set(false);
        }
    }
}
