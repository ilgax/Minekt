package dev.ilgax.minekt.server

import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.api.server.CommandPermission
import dev.ilgax.minekt.api.server.ServerEvent
import dev.ilgax.minekt.api.server.ServerMinekt
import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.TickClock
import dev.ilgax.minekt.script.ServerScriptCompiler
import dev.ilgax.minekt.script.TemplateCompiler
import dev.ilgax.minekt.splitArgs
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import kotlin.io.path.exists

/**
 * Server scripts for one run of [server], from when it has started until it stops: a dedicated server's
 * `minekt` folder, or the singleplayer world's. Scripts get a [ServerMinekt]; their messages go to the
 * players with `minekt:notify` (operators, by default) and the server log.
 *
 * Never touches client classes: dedicated servers don't have them.
 */
internal class ServerScripts(val server: MinecraftServer) : Host {
    override val scriptDir: Path =
        if (server.isDedicatedServer) FabricLoader.getInstance().gameDir.resolve("minekt")
        else server.getWorldPath(LevelResource.ROOT).resolve("minekt")

    override val tickClock = TickClock()
    override val compiler: TemplateCompiler get() = ServerScriptCompiler
    override val commandPrefix: String get() = "/mks "

    override fun receiver(job: Job): ScriptContext = ServerMinekt(job)

    val jobs = JobManager(this)
    val commands = ServerCommands(this)

    /** The server is up: run `autorun.txt`. */
    fun start() {
        Files.createDirectories(scriptDir)
        val autorun = scriptDir.resolve("autorun.txt")
        if (!autorun.exists()) return
        for (line in autorunLines(Files.readString(autorun))) {
            val parts = splitArgs(line)
            if (jobs.runScript(parts.first(), parts.drop(1)) == null) log("autorun.txt: couldn't start '$line'")
        }
    }

    /** The server is stopping: scripts get their `onStop` and save. */
    fun stop() {
        jobs.storage.saveAll() // jobs save as they end, but the JVM may exit before they get to
        jobs.killAll()
    }

    fun tick() {
        tickClock.advance()
        jobs.dispatch(Job.Event.Tick)
    }

    fun dispatch(event: ServerEvent) = jobs.dispatch(Job.Event.Server(event))

    /** Dispatch the event [make] builds, only if a script listens for [type]: entity events are frequent. */
    inline fun <T : ServerEvent> dispatch(type: Class<T>, make: () -> T) {
        if (jobs.wantsServer(type)) dispatch(make())
    }

    fun log(text: String) = LOGGER.info("[minekt] {}", text)

    // ---- Host ---------------------------------------------------------------------------------

    override fun echo(message: String, kind: MessageKind) {
        val component = when (kind) {
            MessageKind.SCRIPT -> Component.literal(message)
            MessageKind.INFO -> Component.literal(message).withStyle(ChatFormatting.GRAY)
            MessageKind.ERROR -> Component.literal(message).withStyle(ChatFormatting.RED)
        }
        if (kind == MessageKind.ERROR) LOGGER.warn("[minekt] {}", message) else log(message)
        toOperators(component)
    }

    override fun echo(message: Component, plain: String) {
        log(plain)
        toOperators(message)
    }

    private fun toOperators(message: Component) = server.execute {
        server.playerList.players.filter { MksPermissions.allows(it.createCommandSourceStack(), MksPermissions.Node.NOTIFY) }.forEach { it.sendSystemMessage(message) }
    }

    override fun clickCommand(id: Long) = "mks click $id"

    override fun isGameThread() = server.isSameThread

    /** Run [block] on the server thread and wait for its result. */
    fun <T> onServerThread(block: (MinecraftServer) -> T): T {
        if (server.isSameThread) return block(server)
        try {
            return CompletableFuture.supplyAsync({ block(server) }, server).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun registerCommand(name: String, completions: List<String>, owner: Job) =
        commands.add(name, completions, CommandPermission.EVERYONE, owner)

    override fun unregisterCommand(name: String, owner: Job) = commands.remove(name, owner)

    companion object {
        private val LOGGER = LoggerFactory.getLogger("minekt")

        /** Operators (permission level 2, like for `/gamemode`), and the console: [CommandPermission.OPS]. */
        fun isOperator(source: CommandSourceStack) = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)

        /**
         * `autorun.txt` on a server: one script per line with its arguments, `#` starts a comment. A leading
         * `\`, `/mks run ` or `mks run ` is allowed, so lines copied from the client's file or chat work too.
         */
        fun autorunLines(text: String): List<String> = text.lines()
            .map { it.substringBefore('#').trim().removePrefix("\\").removePrefix("/").removePrefix("mks run ").trim() }
            .filter { it.isNotEmpty() }
    }
}
