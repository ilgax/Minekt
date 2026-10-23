package dev.ilgax.minekt.api

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.phys.Vec3

// Positions are Minecraft's own Vec3 (exact) and BlockPos (a block), the same classes a mod uses,
// and are imported into every script. Both are fixed values: keep or share them freely. These add
// Kotlin's operators and unpacking to them:
//
//   val ahead = player.eyePosition + player.lookDirection * 5.0
//   val (x, y, z) = player.position.blockPos
//   blockAt(pos.below()); pos.above(2); pos.offset(1, 0, -1); pos.north(); pos.center
//   pos.distManhattan(other); a.distanceTo(b); v.length(); v.normalize()
//   echo("at", pos)  or  "at ${pos.toShortString()}"    // "$pos" alone prints Minecraft's long form

operator fun Vec3.plus(other: Vec3): Vec3 = add(other)
operator fun Vec3.minus(other: Vec3): Vec3 = subtract(other)
operator fun Vec3.times(factor: Double): Vec3 = scale(factor)
operator fun Vec3.div(divisor: Double): Vec3 = scale(1.0 / divisor)
operator fun Vec3.unaryMinus(): Vec3 = reverse()
operator fun Vec3.component1(): Double = x
operator fun Vec3.component2(): Double = y
operator fun Vec3.component3(): Double = z

/**
 * `12.5, 64.0, -3.2`: for text like `"at ${pos.toShortString()}"`, like BlockPos's own `toShortString()`
 * (`12, 64, -4`). `echo` shows positions this way by itself when they're passed as separate parts.
 */
fun Vec3.toShortString(decimals: Int = 1): String = "%.${decimals}f, %.${decimals}f, %.${decimals}f".format(x, y, z)

/** The block this position is in. */
val Vec3.blockPos: BlockPos get() = BlockPos.containing(this)

operator fun BlockPos.plus(other: Vec3i): BlockPos = offset(other)
operator fun BlockPos.minus(other: Vec3i): BlockPos = subtract(other)
operator fun BlockPos.component1(): Int = x
operator fun BlockPos.component2(): Int = y
operator fun BlockPos.component3(): Int = z

/** `(x, y, z)` with two decimals, as chat shows positions. */
internal fun Vec3.short() = "(%.2f, %.2f, %.2f)".format(x, y, z)

/** `(x, y, z)`, as chat shows blocks. */
internal fun BlockPos.short() = "($x, $y, $z)"
