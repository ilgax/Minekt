package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.FakeBlocks;
import dev.ilgax.minekt.hooks.WorldHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block changes for `onBlockChange`. Server updates go through setServerVerifiedBlockState (which
 * calls Level.setBlock directly); the client's own predictions and their corrections through setBlock.
 * Server updates to blocks that scripts fake ({@link FakeBlocks}) are kept from the world.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Unique
    private int minekt$depth;

    @Unique
    private void minekt$before(BlockPos pos) {
        if (WorldHooks.wantBlocks && !FakeBlocks.applying) {
            minekt$depth++;
            WorldHooks.beforeSetBlock((ClientLevel) (Object) this, pos);
        }
    }

    @Unique
    private void minekt$after(BlockPos pos) {
        // Only pair with a HEAD that pushed (the flag may have flipped in between).
        if (minekt$depth > 0) {
            minekt$depth--;
            WorldHooks.afterSetBlock((ClientLevel) (Object) this, pos);
        }
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void minekt$beforeSetBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        minekt$before(pos);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void minekt$afterSetBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        minekt$after(pos);
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), cancellable = true)
    private void minekt$beforeServerBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        // A script shows a fake block here: remember the real one, keep showing the fake.
        if (FakeBlocks.serverUpdate((ClientLevel) (Object) this, pos, state)) {
            ci.cancel();
            return;
        }
        minekt$before(pos);
    }

    @Inject(method = "setServerVerifiedBlockState", at = @At("RETURN"))
    private void minekt$afterServerBlock(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
        minekt$after(pos);
    }
}
