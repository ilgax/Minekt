package dev.ilgax.minekt.api

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import java.util.concurrent.ConcurrentSkipListMap

/** One HUD element that draws whatever lines running scripts have set, top-left of the screen. */
internal object ScriptHud {
    private val lines = ConcurrentSkipListMap<Int, List<String>>()

    fun register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("minekt", "script_hud")) { graphics, _ ->
            val font = Minecraft.getInstance().font
            var y = 4
            for (jobLines in lines.values) for (line in jobLines) {
                graphics.text(font, line, 4, y, 0xFFFFFFFF.toInt(), true)
                y += font.lineHeight + 1
            }
        }
    }

    fun set(jobId: Int, text: List<String>) {
        if (text.isEmpty()) lines.remove(jobId) else lines[jobId] = text
    }
}
