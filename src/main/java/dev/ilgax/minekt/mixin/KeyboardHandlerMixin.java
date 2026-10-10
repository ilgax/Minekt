package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.InputHooks;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keyboard input for `onRawKey`: seen (and maybe cancelled) before the game handles it. */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void minekt$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (InputHooks.listening && InputHooks.key(event.key(), action, event.modifiers())) ci.cancel();
    }
}
