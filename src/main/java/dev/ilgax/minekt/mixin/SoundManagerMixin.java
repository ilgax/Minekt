package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.WorldHooks;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sounds for `onSound`. */
@Mixin(SoundManager.class)
public abstract class SoundManagerMixin {
    @Inject(method = "play", at = @At("HEAD"))
    private void minekt$play(SoundInstance sound, CallbackInfoReturnable<?> cir) {
        if (WorldHooks.wantSounds) WorldHooks.sound(sound);
    }
}
