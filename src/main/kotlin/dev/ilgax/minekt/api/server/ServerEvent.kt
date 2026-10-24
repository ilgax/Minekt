package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.EntityInfo
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/** Something that happened on the server; server scripts get them with `onEvent<ServerEvent.Chat> { }` and the like. */
sealed interface ServerEvent {
    /** [player] sent [message] to the chat: `onEvent<ServerEvent.Chat> { (player, message) -> }`. */
    data class Chat(val player: String, val message: String) : ServerEvent

    data class PlayerJoin(val player: String) : ServerEvent

    data class PlayerLeave(val player: String) : ServerEvent

    /** [player] broke [block] (its id, like `minecraft:stone`) at [pos] in [dimension]: `onEvent<ServerEvent.BlockBreak> { (player, pos, block) -> }`. */
    data class BlockBreak(val player: String, val pos: BlockPos, val block: String, val dimension: String) : ServerEvent

    /** An entity came into the world: it spawned, or its chunk loaded. `onEvent<ServerEvent.EntitySpawn> { (entity) -> }`. */
    data class EntitySpawn(val entity: EntityInfo) : ServerEvent

    /**
     * An entity left the world: it [died] (or was killed with a command), or else was removed. [reason] is `killed`,
     * `discarded` (removed: despawned, picked up, `removeEntity`…), `unloaded` (its chunk unloaded, or its player left) or
     * `changed_dimension`. `onEvent<ServerEvent.EntityRemoved> { (entity, died, reason) -> }`.
     */
    data class EntityRemoved(val entity: EntityInfo, val died: Boolean, val reason: String) : ServerEvent

    /**
     * A living [entity] lost [amount] health, with [health] left (as after the hit). [cause] is the damage type
     * (`minecraft:fall`, `minecraft:mob_attack`, `minecraft:arrow`…), [attacker] who's responsible and [projectile]
     * what hit (an arrow, a fireball), when there is one. `onEvent<ServerEvent.Damage> { (entity, amount, health, cause, attacker) -> }`.
     */
    data class Damage(
        val entity: EntityInfo,
        val amount: Float,
        val health: Float,
        val cause: String,
        val attacker: EntityInfo? = null,
        val projectile: EntityInfo? = null,
    ) : ServerEvent

    /** A living [entity] died of [cause] (a damage type, as in [Damage]); [killer] is who's responsible, if anyone. */
    data class Death(val entity: EntityInfo, val cause: String, val killer: EntityInfo? = null) : ServerEvent
}

/** Who may use a command made with [ServerMinekt.command]. */
enum class CommandPermission {
    EVERYONE,
    /** Operators (permission level 2, like `/gamemode`), and the console. */
    OPS,
}

/** Someone typed a script's command: [args] are the words after its name (`"quoted words"` grouped). */
class CommandCall internal constructor(
    private val api: ServerMinekt,
    /** The player who typed it, or null for the server console (or a command block). */
    val sender: String?,
    val args: List<String>,
) {
    /** Answer whoever typed the command: the player, or the server log for the console. */
    fun reply(vararg parts: Any?) {
        if (sender == null) api.writeServerLog(parts) else api.tell(sender, *parts)
    }

    operator fun component1() = sender
    operator fun component2() = args

    override fun toString() = "CommandCall($sender, $args)"
}
