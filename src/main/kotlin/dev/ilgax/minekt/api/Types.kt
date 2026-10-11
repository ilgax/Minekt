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

    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = Vec3(x * factor, y * factor, z * factor)

    /** How long this is, as a direction or offset. */
    val length: Double get() = sqrt(x * x + y * y + z * z)

    override fun toString() = "(%.2f, %.2f, %.2f)".format(x, y, z)
}

data class BlockPos(val x: Int, val y: Int, val z: Int) {
    fun offset(dx: Int = 0, dy: Int = 0, dz: Int = 0) = BlockPos(x + dx, y + dy, z + dz)
    override fun toString() = "($x, $y, $z)"
}

data class Block(val pos: BlockPos, val id: String)

/**
 * The player, as of the moment `player` was read. [food] and [saturation] are 0–20; [air] counts
 * down from [maxAir] underwater; [velocity] is blocks per tick; [gameMode] is `survival`,
 * `creative`, `adventure` or `spectator`; [vehicle] is what you ride (an entity id like
 * `minecraft:boat`) or null.
 */
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
    val absorption: Float = 0f,
    val food: Int = 20,
    val saturation: Float = 0f,
    val xpLevel: Int = 0,
    val xpProgress: Float = 0f,
    val air: Int = 300,
    val maxAir: Int = 300,
    val armor: Int = 0,
    val effects: List<EffectInfo> = emptyList(),
    val velocity: Vec3 = Vec3(0.0, 0.0, 0.0),
    val onGround: Boolean = true,
    val inWater: Boolean = false,
    val inLava: Boolean = false,
    val onFire: Boolean = false,
    val sneaking: Boolean = false,
    val sprinting: Boolean = false,
    val flying: Boolean = false,
    val gliding: Boolean = false,
    val gameMode: String = "survival",
    val vehicle: String? = null,
    /** Where your eyes are: where the crosshair looks from. */
    val eyePosition: Vec3 = position,
    /** The way you look, one block long: `eyePosition + lookDirection * 20.0` is 20 blocks ahead. */
    val lookDirection: Vec3 = Vec3(0.0, 0.0, 1.0),
)

/** A potion effect: [id] like `minecraft:speed`, [amplifier] 0 for level I, [duration] in ticks (-1 for infinite). */
data class EffectInfo(val id: String, val amplifier: Int, val duration: Int)

/**
 * An entity, as of the moment it was read. [health], [maxHealth] and [isBaby] are for living
 * entities (null / false otherwise). [equipment] maps slots (`mainhand`, `offhand`, `head`, `chest`,
 * `legs`, `feet`, `body`, `saddle`) to item ids, leaving out empty ones. [effects] are only what the
 * server shares: usually none for other entities.
 */
data class EntityInfo(
    val id: Int,
    val uuid: String,
    val name: String,
    /** Registry id, e.g. `minecraft:zombie`. */
    val type: String,
    val position: Vec3,
    val health: Float?,
    val distance: Double,
    val maxHealth: Float? = null,
    /** The name given with a name tag or command, or null. */
    val customName: String? = null,
    val isBaby: Boolean = false,
    val equipment: Map<String, String> = emptyMap(),
    val effects: List<EffectInfo> = emptyList(),
    /** Blocks per tick, as last sent by the server. */
    val velocity: Vec3 = Vec3(0.0, 0.0, 0.0),
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val sneaking: Boolean = false,
    val invisible: Boolean = false,
    val glowing: Boolean = false,
    val onFire: Boolean = false,
    /** The entity it rides (an id for `entity(id)`), or null. */
    val vehicleId: Int? = null,
) {
    /** What it holds in its main hand, or null. */
    val heldItem: String? get() = equipment["mainhand"]
}

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
    /**
     * The player lost [amount] health, with [health] left. [cause] is the damage type (`minecraft:fall`,
     * `minecraft:mob_attack`, `minecraft:arrow`, …), [attacker] who's responsible and [projectile] what hit
     * (an arrow, a fireball), when the server says so; null otherwise.
     */
    data class Damage(
        val amount: Float,
        val health: Float,
        val cause: String? = null,
        val attacker: EntityInfo? = null,
        val projectile: EntityInfo? = null,
    ) : GameEvent
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
