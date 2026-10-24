@file:OptIn(dev.ilgax.minekt.api.ExperimentalVisuals::class)

package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.clientHost
import com.mojang.blaze3d.platform.NativeImage
import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// A grid of pixels you set one by one, drawn on the HUD, for images, maps, graphs or emulators:
//
//   val screen = pixels(160, 144)
//   screen[10, 20] = Color.RED
//   screen.fill(0x202020)
//   onHudRender { g -> g.pixels(screen, 10, 10, 320, 288) }      // drawn twice as big, sharp
//
// For speed, write `screen.data` directly (0xAARRGGBB, row by row: index y * width + x) and call
// `screen.changed()` after. Only what changed is sent to the graphics card, once per frame.

/** Pixels made with [pixels]; draw them with `g.pixels` in `onHudRender`. */
@ExperimentalVisuals
class Pixels internal constructor(
    val width: Int,
    val height: Int,
    private val job: Job,
) {
    /** Every pixel, 0xAARRGGBB, row by row (`y * width + x`). Call [changed] after writing it directly. */
    val data = IntArray(width * height)

    private val dirty = AtomicBoolean(true)
    private var texture: DynamicTexture? = null
    private var id: Identifier? = null
    private var released = false

    /** The pixel at [x], [y] (0xAARRGGBB). */
    operator fun get(x: Int, y: Int): Int = data[index(x, y)]

    /** Set the pixel at [x], [y]: 0xRRGGBB is opaque, 0xAARRGGBB keeps its alpha. */
    operator fun set(x: Int, y: Int, color: Int) {
        data[index(x, y)] = opaque(color)
        dirty.set(true)
    }

    /** Make the pixel at [x], [y] fully transparent. */
    fun clear(x: Int, y: Int) {
        data[index(x, y)] = 0
        dirty.set(true)
    }

    /** Set every pixel to [color]. */
    fun fill(color: Int) {
        data.fill(opaque(color))
        dirty.set(true)
    }

    /** Make every pixel transparent. */
    fun clear() {
        data.fill(0)
        dirty.set(true)
    }

    /** Fill a rectangle. Parts outside are left out. */
    fun fill(x: Int, y: Int, width: Int, height: Int, color: Int) {
        val c = opaque(color)
        for (py in maxOf(0, y) until minOf(this.height, y + height)) {
            data.fill(c, py * this.width + maxOf(0, x), py * this.width + minOf(this.width, x + width))
        }
        dirty.set(true)
    }

    /** Say [data] was written directly, so it's drawn again. */
    fun changed() = dirty.set(true)

    private fun index(x: Int, y: Int): Int {
        if (x !in 0 until width || y !in 0 until height) throw IndexOutOfBoundsException("($x, $y) is outside $width × $height pixels")
        return y * width + x
    }

    /** The texture's id, created and updated on the render thread as needed. */
    internal fun texture(): Identifier {
        check(!released) { "These pixels belonged to ${job.name}, which has ended" }
        val existing = texture
        val tex = existing ?: DynamicTexture({ "Minekt pixels of ${job.name}" }, NativeImage(width, height, false)).also { created ->
            val newId = Identifier.fromNamespaceAndPath("minekt", "pixels/${nextId.incrementAndGet()}")
            Minecraft.getInstance().textureManager.register(newId, created)
            texture = created
            id = newId
            job.cleanups += { job.clientHost.onGameThread { release() } }
        }
        if (dirty.getAndSet(false)) upload(tex)
        return id!!
    }

    private fun upload(tex: DynamicTexture) {
        val image = tex.pixels ?: return
        val pointer = image.pointer
        // ARGB in, ABGR (bytes R, G, B, A) in the image's memory.
        for (i in data.indices) {
            val c = data[i]
            MemoryUtil.memPutInt(pointer + i * 4L, (c and 0xFF00FF00.toInt()) or (c shr 16 and 0xFF) or (c and 0xFF shl 16))
        }
        tex.upload()
    }

    private fun release() {
        id?.let { Minecraft.getInstance().textureManager.release(it) }
        id = null
        texture = null
        released = true
    }

    private companion object {
        val nextId = AtomicLong()
    }
}

/** A [width] × [height] grid of [Pixels], all transparent at first. Freed when the script ends. */
@ExperimentalVisuals
fun Minekt.pixels(width: Int, height: Int): Pixels {
    require(width in 1..4096 && height in 1..4096) { "Pixels can be 1 to 4096 wide and high, not $width × $height" }
    job.checkAlive()
    return Pixels(width, height, job)
}
