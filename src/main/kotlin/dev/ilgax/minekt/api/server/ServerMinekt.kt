package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.EntityInfo
import dev.ilgax.minekt.api.Handler
import dev.ilgax.minekt.api.toInfo
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

    /** Everyone online, by name, with their [EntityInfo.gameMode] and [EntityInfo.isOp]. */
    val players: List<EntityInfo> get() = server { s -> s.playerList.players.map { it.toInfo(null) }.sortedBy { it.name } }

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

    // ---- world --------------------------------------------------------------------------------

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

    /**
     * Call [handler] for every server event of type [T], on the script's thread with a plain copy of what happened:
     * `onEvent<ServerEvent.Damage> { (entity, amount, health, cause, attacker) -> }`, or every event with
     * `onEvent<ServerEvent> { when (it) { is ServerEvent.Chat -> … } }`. It runs just after the event, so it can't
     * cancel it (a Fabric event listener can), but it can take its time. Only the types scripts listen for are built.
     */
    inline fun <reified T : ServerEvent> onEvent(noinline handler: (T) -> Unit): Handler =
        addServerHandler(T::class.java) { if (it is T) handler(it) }

    @PublishedApi
    internal fun addServerHandler(type: Class<out ServerEvent>, handler: (ServerEvent) -> Unit): Handler {
        job.checkAlive()
        job.serverEventTypes += type
        job.serverHandlers += handler
        return Handler { job.serverHandlers.remove(handler) }
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

    override fun currentDayTime(): Long? = server { s -> Math.floorMod(s.overworld().defaultClockTime, 24000L) }

    /** The world folder: one per dedicated server, or the singleplayer world. */
    override fun worldKey(): String = server { s ->
        val folder = s.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().fileName
        (if (s.isDedicatedServer) "server:" else "singleplayer:") + folder
    }

    internal fun online(server: MinecraftServer, name: String): ServerPlayer =
        server.playerList.getPlayerByName(name) ?: throw IllegalArgumentException("$name isn't online")

    internal fun level(server: MinecraftServer, dimension: String): ServerLevel =
        server.getLevel(ResourceKey.create(Registries.DIMENSION, identifier(dimension)))
            ?: throw IllegalArgumentException("No dimension '$dimension' (there are ${server.levelKeys().joinToString { it.identifier().toString() }})")

    internal fun identifier(id: String): Identifier =
        Identifier.tryParse(if (':' in id) id else "minecraft:$id") ?: throw IllegalArgumentException("'$id' isn't an id")
}
