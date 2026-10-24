package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.Handler
import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.server.ServerScripts
import net.minecraft.commands.CommandSource
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3

/**
 * The implicit receiver of server scripts: everything here (and in [ScriptContext]) is callable
 * unqualified from a `.kts` run by `/mks run` or the server's `minekt/autorun.txt`.
 *
 * Calls run on the script's own thread; anything touching the world hops to the server thread via
 * [server]. Dimensions are ids like `minecraft:the_nether`, or just `the_nether`.
 */
class ServerMinekt internal constructor(job: Job) : ScriptContext(job) {
    private val host: ServerScripts get() = job.host as ServerScripts

    // ---- raw access ---------------------------------------------------------------------------

    /**
     * Run [block] on the server thread with the live [MinecraftServer] and return its result.
     * This is the escape hatch: anything the game or any installed mod exposes is reachable here.
     */
    fun <T> server(block: (MinecraftServer) -> T): T {
        job.checkAlive()
        return host.onServerThread(block)
    }

    // ---- players ------------------------------------------------------------------------------

    /** Everyone online, by name. */
    val players: List<OnlinePlayer> get() = server { s -> s.playerList.players.map(::info).sortedBy { it.name } }

    /** The player called [name] (any case), or null if they aren't online. */
    fun player(name: String): OnlinePlayer? = server { s -> s.playerList.getPlayerByName(name)?.let(::info) }

    /** Show a message to everyone online (and in the server log). [ChatText] parts keep their colors. */
    fun broadcast(vararg parts: Any?) {
        val text = styled(parts)
        server { s -> s.playerList.broadcastSystemMessage(text, false) }
    }

    /** Show a message to one player. Throws if they aren't online. */
    fun tell(player: String, vararg parts: Any?) {
        val text = styled(parts)
        server { s -> online(s, player).sendSystemMessage(text) }
    }

    /**
     * Run a command as the server console (all permissions; leading `/` optional), like `runCommand("time set day")`,
     * and return what it answered, one line per message.
     */
    fun runCommand(command: String): String = server { s ->
        val output = mutableListOf<String>()
        val collector = object : CommandSource {
            override fun sendSystemMessage(message: Component) {
                output += message.string
            }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }
        s.commands.performPrefixedCommand(s.createCommandSourceStack().withSource(collector), command.removePrefix("/"))
        output.joinToString("\n")
    }

    /** Move [player] to [pos], in [dimension] if given (else where they are). */
    fun teleport(player: String, pos: Vec3, dimension: String? = null) = server { s ->
        val target = online(s, player)
        val level = dimension?.let { level(s, it) } ?: target.level()
        target.teleportTo(level, pos.x, pos.y, pos.z, emptySet(), target.yRot, target.xRot, true)
        Unit
    }

    /** Give [player] [count] of [item] (an id like `minecraft:diamond`). Returns false if it didn't all fit in their inventory. */
    fun give(player: String, item: String, count: Int = 1): Boolean = server { s ->
        require(count > 0) { "count must be at least 1" }
        val type = BuiltInRegistries.ITEM.getOptional(identifier(item)).orElseThrow { IllegalArgumentException("No item '$item'") }
        online(s, player).inventory.add(ItemStack(type, count))
    }

    // ---- world --------------------------------------------------------------------------------

    /** The id of the block at a position, like `minecraft:stone` (unloaded chunks are loaded to read it). */
    fun blockId(x: Int, y: Int, z: Int, dimension: String = "minecraft:overworld"): String = server { s ->
        BuiltInRegistries.BLOCK.getKey(level(s, dimension).getBlockState(BlockPos(x, y, z)).block).toString()
    }

    fun blockId(pos: BlockPos, dimension: String = "minecraft:overworld") = blockId(pos.x, pos.y, pos.z, dimension)

    /** Place [block] at a position: an id like `minecraft:stone`, with properties too: `minecraft:oak_log[axis=x]`. */
    fun setBlock(x: Int, y: Int, z: Int, block: String, dimension: String = "minecraft:overworld") = server { s ->
        val state = try {
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, block, false).blockState()
        } catch (e: com.mojang.brigadier.exceptions.CommandSyntaxException) {
            throw IllegalArgumentException("'$block' isn't a block: ${e.message}")
        }
        level(s, dimension).setBlock(BlockPos(x, y, z), state, Block.UPDATE_ALL)
        Unit
    }

    fun setBlock(pos: BlockPos, block: String, dimension: String = "minecraft:overworld") = setBlock(pos.x, pos.y, pos.z, block, dimension)

    // ---- events -------------------------------------------------------------------------------

    /** A player sent a chat message: `onChat { (player, message) -> }`. Handlers run on the script thread. */
    fun onChat(handler: (ServerEvent.Chat) -> Unit): Handler = on(handler)

    fun onPlayerJoin(handler: (name: String) -> Unit): Handler = on<ServerEvent.PlayerJoin> { handler(it.player) }

    fun onPlayerLeave(handler: (name: String) -> Unit): Handler = on<ServerEvent.PlayerLeave> { handler(it.player) }

    /** A player broke a block: `onBlockBreak { (player, pos, block, dimension) -> }`. */
    fun onBlockBreak(handler: (ServerEvent.BlockBreak) -> Unit): Handler = on(handler)

    private inline fun <reified T : ServerEvent> on(crossinline handler: (T) -> Unit): Handler {
        job.checkAlive()
        val filtered: (ServerEvent) -> Unit = { if (it is T) handler(it) }
        job.serverHandlers += filtered
        return Handler { job.serverHandlers.remove(filtered) }
    }

    /**
     * Make `/name` a server command while this script runs, for [permission]. [handler] gets who typed it
     * and the words after the name: `command("spawn") { call -> teleport(call.sender!!, …) }`. [completions]
     * are offered on Tab. A name that's already a command (of the game or a mod) can't be taken.
     */
    fun command(
        name: String,
        permission: CommandPermission = CommandPermission.EVERYONE,
        completions: List<String> = emptyList(),
        handler: (CommandCall) -> Unit,
    ): Handler {
        job.checkAlive()
        val call: (Job.Event.Command) -> Unit = { handler(CommandCall(this, it.sender, it.args)) }
        job.commandHandlers[name] = call
        host.commands.add(name, completions, permission, job)?.let { error ->
            job.commandHandlers.remove(name)
            throw IllegalStateException(error)
        }
        return Handler {
            if (job.commandHandlers.remove(name, call)) host.unregisterCommand(name, job)
        }
    }

    // ---- internals ----------------------------------------------------------------------------

    internal fun writeServerLog(parts: Array<out Any?>) = host.log(plain(parts))

    /** The world folder: one per dedicated server, or the singleplayer world. */
    override fun worldKey(): String = server { s ->
        val folder = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().fileName
        (if (s.isDedicatedServer) "server:" else "singleplayer:") + folder
    }

    private fun online(server: MinecraftServer, name: String): ServerPlayer =
        server.playerList.getPlayerByName(name) ?: throw IllegalArgumentException("$name isn't online")

    private fun level(server: MinecraftServer, dimension: String): ServerLevel =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier(dimension)))
            ?: throw IllegalArgumentException("No dimension '$dimension' (there are ${server.levelKeys().joinToString { it.identifier().toString() }})")

    private fun identifier(id: String): Identifier =
        Identifier.tryParse(if (':' in id) id else "minecraft:$id") ?: throw IllegalArgumentException("'$id' isn't an id")

    private fun info(player: ServerPlayer) = OnlinePlayer(
        name = player.name.string,
        uuid = player.uuid.toString(),
        position = player.position(),
        dimension = player.level().dimension().identifier().toString(),
        health = player.health,
        gameMode = player.gameMode().serializedName,
        isOp = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER),
    )
}
