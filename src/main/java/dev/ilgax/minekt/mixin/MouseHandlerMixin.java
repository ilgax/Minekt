package dev.ilgax.minekt.mixin;

import dev.ilgax.minekt.hooks.InputHooks;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse input for `onRawMouse` and `onMouseMove`: seen (and maybe cancelled) before the game handles it. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void minekt$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (InputHooks.listening && InputHooks.button(info.button(), action, info.modifiers())) ci.cancel();
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void minekt$scroll(long window, double x, double y, CallbackInfo ci) {
        if (InputHooks.listening && InputHooks.scroll(x, y)) ci.cancel();
    }

    @Inject(method = "onMove", at = @At("HEAD"), cancellable = true)
    private void minekt$move(long window, double x, double y, double dx, double dy, CallbackInfo ci) {
        if (InputHooks.listeningToMoves && InputHooks.move(x, y, dx, dy)) ci.cancel();
    }
}
