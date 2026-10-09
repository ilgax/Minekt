package dev.ilgax.minekt.api

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** ARGB colors for world shapes. `Color.rgb(255, 128, 0)` or a literal like `0xFFFF8000.toInt()`. */
object Color {
    val WHITE = rgb(255, 255, 255)
    val RED = rgb(255, 64, 64)
    val GREEN = rgb(64, 255, 64)
    val BLUE = rgb(64, 128, 255)
    val YELLOW = rgb(255, 230, 64)
    val CYAN = rgb(64, 230, 255)
    val MAGENTA = rgb(255, 64, 255)
    val ORANGE = rgb(255, 160, 32)

    fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a and 0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha and 0xFF shl 24)
}

/** Something a script draws in the world. Immutable; drawn every tick until removed or the script ends. */
sealed interface Shape {
    val throughWalls: Boolean

    data class Box(val min: Vec3, val max: Vec3, val color: Int, val fill: Boolean, override val throughWalls: Boolean) : Shape
    data class Line(val from: Vec3, val to: Vec3, val color: Int, val width: Float, val arrow: Boolean, override val throughWalls: Boolean) : Shape
    data class Text(val pos: Vec3, val text: String, val color: Int, val scale: Float, override val throughWalls: Boolean) : Shape
}

/** A drawn shape; call [remove] to take it away early. */
class ShapeHandle internal constructor(private val id: Long, private val registry: ShapeRegistry) {
    fun remove() = registry.remove(id)
}

/** All shapes currently drawn by scripts, keyed by job. Written by script threads, read by the client thread. */
class ShapeRegistry {
    private val nextId = AtomicLong()
    private val byJob = ConcurrentHashMap<Int, ConcurrentHashMap<Long, Shape>>()

    fun add(jobId: Int, shape: Shape): ShapeHandle {
        val id = nextId.incrementAndGet()
        byJob.computeIfAbsent(jobId) { ConcurrentHashMap() }[id] = shape
        return ShapeHandle(id, this)
    }

    fun remove(id: Long) = byJob.values.forEach { it.remove(id) }

    fun clear(jobId: Int) {
        byJob.remove(jobId)
    }

    fun all(): Sequence<Shape> = byJob.values.asSequence().flatMap { it.values.asSequence() }

    fun count(jobId: Int): Int = byJob[jobId]?.size ?: 0

    companion object {
        /** The registry the game draws from. */
        val global = ShapeRegistry()
    }
}
