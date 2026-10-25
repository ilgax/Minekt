package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.FakeBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Optional;

/** Keep server block-entity updates while a script temporarily replaces the visible block. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Redirect(
        method = "handleBlockEntityData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntityType;)Ljava/util/Optional;"
        )
    )
    private <T extends BlockEntity> Optional<T> minekt$realBlockEntity(ClientLevel level, BlockPos pos, BlockEntityType<T> type) {
        return FakeBlocks.blockEntityForUpdate(level, pos, type);
    }
}
