package dev.ilgax.minekt.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets script keybinds be added to (and removed from) the Controls list while the game runs. */
@Mixin(Options.class)
public interface OptionsAccessor {
    @Mutable
    @Accessor("keyMappings")
    void minekt$setKeyMappings(KeyMapping[] keyMappings);
}
