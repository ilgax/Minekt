package dev.ilgax.minekt.api

import kotlin.math.sqrt

/**
 * Plain snapshots of game state. Scripts get copies, never live game objects, so they are safe to
 * keep around and read from the script thread. (Use `game { mc -> ... }` for live objects.)
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    fun distanceTo(other: Vec3): Double {
        val dx = x - other.x; val dy = y - other.y; val dz = z - other.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    val blockPos: BlockPos get() = BlockPos(Math.floor(x).toInt(), Math.floor(y).toInt(), Math.floor(z).toInt())

    override fun toString() = "(%.2f, %.2f, %.2f)".format(x, y, z)
}

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0) = BlockPos(x + dx, y + dy, z + dz)
    override fun toString() = "($x, $y, $z)"
}

data class Block(val pos: BlockPos, val id: String)

data class PlayerInfo(
    val name: String,
    val uuid: String,
    val position: Vec3,
    val yaw: Float,
    val pitch: Float,
    val health: Float,
    val maxHealth: Float,
    val dimension: String,
    val selectedSlot: Int,
)

data class EntityInfo(
    val id: Int,
    val uuid: String,
    val name: String,
    /** Registry id, e.g. `minecraft:zombie`. */
    val type: String,
    val position: Vec3,
    val health: Float?,
    val distance: Double,
)

data class ItemStackInfo(val slot: Int, val id: String, val count: Int)

/** Movement / action keys a script can hold down. */
enum class Key { FORWARD, BACK, LEFT, RIGHT, JUMP, SNEAK, SPRINT, ATTACK, USE, DROP, SWAP_OFFHAND }
