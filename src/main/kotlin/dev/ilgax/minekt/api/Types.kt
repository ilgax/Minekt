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

/** A side of a block. */
enum class Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }

/** The block under the crosshair, and which face of it. */
data class TargetedBlock(val pos: BlockPos, val id: String, val face: Face, val hit: Vec3)

/** The open screen. [type] is the screen class name, e.g. `ChestScreen`, `InventoryScreen`, `ChatScreen`. */
data class ScreenInfo(val title: String, val type: String, val isContainer: Boolean)

/**
 * One slot of the open container menu. [index] is what `clickSlot` etc. take. [isPlayerInventory]
 * separates the player's own slots from the container's (chest, furnace, ...).
 */
data class SlotInfo(val index: Int, val id: String?, val count: Int, val isPlayerInventory: Boolean)

/** The open container (or the player's inventory menu when nothing else is open). */
data class ContainerInfo(val title: String, val slots: List<SlotInfo>) {
    /** Slots belonging to the container itself (not the player's inventory). */
    val containerSlots: List<SlotInfo> get() = slots.filter { !it.isPlayerInventory }
    val playerSlots: List<SlotInfo> get() = slots.filter { it.isPlayerInventory }
    fun find(id: String): SlotInfo? = slots.firstOrNull { it.id == id || it.id == "minecraft:$id" }
}

data class WorldInfo(
    val dimension: String,
    /** Time of day in ticks, 0–23999 (0 = sunrise, 6000 = noon, 13000 = night, 18000 = midnight). */
    val timeOfDay: Long,
    /** Total ticks the world has run. */
    val gameTime: Long,
    val isDay: Boolean,
    val isRaining: Boolean,
    val isThundering: Boolean,
)

/** Things that happen to the player or around them. Use `onEvent { }` or the specific `onDamage { }` etc. */
sealed interface GameEvent {
    data class Damage(val amount: Float, val health: Float) : GameEvent
    data object Death : GameEvent
    data object Respawn : GameEvent
    data class JoinWorld(val dimension: String) : GameEvent
    data object LeaveWorld : GameEvent
    data class DimensionChange(val from: String, val to: String) : GameEvent
    data class ScreenOpen(val screen: ScreenInfo) : GameEvent
    data object ScreenClose : GameEvent
    data class ChunkLoad(val x: Int, val z: Int) : GameEvent
    /** The count of an item in the player's inventory went up (pickups, crafting, taking from chests). */
    data class ItemGained(val id: String, val count: Int) : GameEvent
    /** A block changed into another block: placed, broken, grown, … ([old] and [new] are block ids). Not reported for chunks loading. */
    data class BlockChange(val pos: BlockPos, val old: String, val new: String) : GameEvent
    /** An entity came into view (spawned, or walked into loaded range). */
    data class EntityAppear(val entity: EntityInfo) : GameEvent
    /** An entity went away: [died], or despawned / out of range. */
    data class EntityGone(val entity: EntityInfo, val died: Boolean) : GameEvent
    /** A sound started playing for you. [category] is e.g. `hostile`, `blocks`, `players`, `ui`. */
    data class Sound(val id: String, val pos: Vec3, val category: String) : GameEvent
    /** The server sent particles (not ones the client makes up itself, like footsteps). */
    data class Particle(val id: String, val pos: Vec3, val count: Int) : GameEvent
    /** A player appeared in the tab list. */
    data class PlayerJoin(val name: String) : GameEvent
    /** A player left the tab list. */
    data class PlayerLeave(val name: String) : GameEvent
    /** The sidebar scoreboard changed (or appeared / went away: then [title] is empty). Lines top to bottom. */
    data class ScoreboardChange(val title: String, val lines: List<String>) : GameEvent
}

/** The sidebar scoreboard: its title and lines, top to bottom. */
data class Sidebar(val title: String, val lines: List<String>)
