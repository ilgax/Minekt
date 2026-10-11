package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.Glows;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Glow outlines that scripts put on entities (`glow`), drawn like the Glowing effect, only on this client. */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void minekt$glow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
        if (Glows.active) {
            int color = Glows.entityColor(entity.getId());
            if (color != 0) state.outlineColor = color;
        }
    }
}
