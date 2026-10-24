package dev.ilgax.minekt.api.server

import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3

/** Something that happened on the server; server scripts get them through `onChat`, `onPlayerJoin`… */
sealed interface ServerEvent {
    /** [player] sent [message] to the chat: `onChat { (player, message) -> }`. */
    data class Chat(val player: String, val message: String) : ServerEvent

    data class PlayerJoin(val player: String) : ServerEvent

    data class PlayerLeave(val player: String) : ServerEvent

    /** [player] broke [block] (its id, like `minecraft:stone`) at [pos] in [dimension]: `onBlockBreak { (player, pos, block) -> }`. */
    data class BlockBreak(val player: String, val pos: BlockPos, val block: String, val dimension: String) : ServerEvent
}

/** A player on the server, as [ServerMinekt.players] and [ServerMinekt.player] see them. */
data class OnlinePlayer(
    val name: String,
    val uuid: String,
    val position: Vec3,
    /** Like `minecraft:overworld`. */
    val dimension: String,
    val health: Float,
    /** `survival`, `creative`, `adventure` or `spectator`. */
    val gameMode: String,
    /** Whether they may use operator commands (permission level 2, like `/gamemode`). */
    val isOp: Boolean,
)

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
