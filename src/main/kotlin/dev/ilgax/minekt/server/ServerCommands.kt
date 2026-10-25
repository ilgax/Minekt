package dev.ilgax.minekt.server

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import dev.ilgax.minekt.api.server.CommandPermission
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.splitArgs
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.server.MinecraftServer
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Server `/` commands owned by server scripts.
 *
 * Like [dev.ilgax.minekt.hooks.ScriptCommands] on the client: Brigadier can't remove nodes, so each name
 * gets one node per dispatcher, guarded by `requires { name is owned, and the sender may use it }`. When
 * the owning script ends the requirement fails, and players' command trees are sent again so the
 * command disappears from their Tab completion.
 */
internal class ServerCommands(private val scripts: ServerScripts) {
    private class Registration(val owner: Job, val completions: List<String>, val permission: CommandPermission)

    private val active = ConcurrentHashMap<String, Registration>()
    /** Names we've added a node for, per dispatcher (`/reload` makes a new one). */
    private val installed = Collections.synchronizedMap(WeakHashMap<CommandDispatcher<*>, MutableSet<String>>())

    /** A new dispatcher: add nodes again for scripts that are still running. */
    fun reinstall(dispatcher: CommandDispatcher<CommandSourceStack>) = active.keys.forEach { install(dispatcher, it) }

    /** Called from a script thread; does its work on the server thread. Returns an error or null. */
    fun add(name: String, completions: List<String>, permission: CommandPermission, owner: Job): String? {
        if (!name.matches(Regex("[a-z0-9_.-]+"))) return "Invalid command name '$name' (use a-z, 0-9, _ . -)"
        return scripts.onServerThread { server ->
            val existing = active[name]
            val dispatcher = server.commands.dispatcher
            when {
                existing != null && existing.owner !== owner -> "/$name is already registered by script '${existing.owner.name}'"
                dispatcher.root.getChild(name) != null && name !in installedOn(dispatcher) -> "/$name is already a command of the game or another mod"
                else -> {
                    active[name] = Registration(owner, completions, permission)
                    install(dispatcher, name)
                    resend(server)
                    null
                }
            }
        }
    }

    /** Fire-and-forget: called during job cleanup, which must not block on the server thread. */
    fun remove(name: String, owner: Job) {
        val registration = active[name] ?: return
        if (registration.owner === owner && active.remove(name, registration)) {
            scripts.server.execute { resend(scripts.server) }
        }
    }

    private fun allowed(name: String, source: CommandSourceStack): Boolean {
        val registration = active[name] ?: return false
        return MksPermissions.mayUseCommand(source, name) {
            registration.permission == CommandPermission.EVERYONE || ServerScripts.isOperator(source)
        }
    }

    private fun install(dispatcher: CommandDispatcher<CommandSourceStack>, name: String) {
        if (!installedOn(dispatcher).add(name)) return
        dispatcher.register(
            Commands.literal(name)
                .requires { allowed(name, it) }
                .executes { run(name, it.source, emptyList()) }
                .then(
                    Commands.argument("args", StringArgumentType.greedyString())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(active[name]?.completions.orEmpty(), builder) }
                        .executes { run(name, it.source, splitArgs(StringArgumentType.getString(it, "args"))) },
                ),
        )
    }

    private fun run(name: String, source: CommandSourceStack, args: List<String>): Int {
        val registration = active[name] ?: return 0
        registration.owner.post(Job.Event.Command(name, args, source.player?.name?.string))
        return 1
    }

    private fun installedOn(dispatcher: CommandDispatcher<*>) = installed.getOrPut(dispatcher) { mutableSetOf() }

    /** Send every player their command tree again, so added and removed commands show up right away. */
    private fun resend(server: MinecraftServer) = server.playerList.players.forEach(server.commands::sendCommands)
}
