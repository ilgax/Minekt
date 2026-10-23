@file:OptIn(dev.ilgax.minekt.api.ExperimentalVisuals::class)

package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.Glow
import dev.ilgax.minekt.job.Job
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Glow outlines from scripts (`glow`): the game's own glowing outline, through
 * [dev.ilgax.minekt.mixin.EntityRendererMixin]. The newest glow on an entity wins.
 */
object Glows {
    /** Whether any glow exists, so the mixin does nothing otherwise. */
    @JvmField @Volatile var active = false

    private val glows = CopyOnWriteArrayList<Glow>()

    internal fun add(glow: Glow) {
        glows += glow
        active = true
    }

    internal fun remove(glow: Glow) {
        glows -= glow
        active = glows.isNotEmpty()
    }

    internal fun removeAll(job: Job) {
        glows.removeIf { it.job === job }
        active = glows.isNotEmpty()
    }

    internal fun has(job: Job) = glows.any { it.job === job }

    /** The outline color for entity [id] (opaque ARGB), or 0 for none. */
    @JvmStatic
    fun entityColor(id: Int): Int {
        for (i in glows.indices.reversed()) {
            val glow = glows.getOrNull(i) ?: continue
            if (glow.entity == id) return glow.color or (0xFF shl 24)
        }
        return 0
    }
}
