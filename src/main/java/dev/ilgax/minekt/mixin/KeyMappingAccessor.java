package dev.ilgax.minekt.mixin;

import java.util.Map;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Every key mapping by name (a removed script keybind must leave it too), and a mapping's pending clicks. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("ALL")
    static Map<String, KeyMapping> minekt$all() {
        throw new AssertionError();
    }

    @Accessor("clickCount")
    int minekt$getClickCount();

    @Accessor("clickCount")
    void minekt$setClickCount(int count);
}
