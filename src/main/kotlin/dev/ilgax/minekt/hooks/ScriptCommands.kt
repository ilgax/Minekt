package dev.ilgax.minekt.hooks

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.splitArgs
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Client-side `/` commands owned by scripts, built on Fabric's client command API.
 *
 * Brigadier can't remove nodes, so each name gets one node per dispatcher, guarded by
 * `requires { name is owned }`. When the owning script ends, the requirement fails: the command
 * vanishes from tab-completion and `/name` falls through to the server again.
 */
internal object ScriptCommands {
    private class Registration(val owner: Job, val completions: List<String>)

    private val active = ConcurrentHashMap<String, Registration>()
    /** Names we've added a node for, per dispatcher (a fresh dispatcher is made on every world join). */
    private val installed = Collections.synchronizedMap(WeakHashMap<CommandDispatcher<*>, MutableSet<String>>())

    fun register() {
        // New world / server: re-add nodes for scripts that are still running.
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            active.keys.forEach { install(dispatcher, it) }
        }
    }

    /** Called from a script thread; does its work on the client thread. Returns an error or null. */
    fun add(name: String, completions: List<String>, owner: Job): String? {
        if (!name.matches(Regex("[a-z0-9_.-]+"))) return "Invalid command name '$name' (use a-z, 0-9, _ . -)"
        return onClientThread {
            val existing = active[name]
            val dispatcher = ClientCommands.getActiveDispatcher()
            when {
                existing != null && existing.owner !== owner ->
                    "/$name is already registered by script '${existing.owner.name}'"
                dispatcher != null && dispatcher.root.getChild(name) != null && name !in installedOn(dispatcher) ->
                    "/$name is already a client command of another mod"
                else -> {
                    active[name] = Registration(owner, completions)
                    dispatcher?.let { install(it, name) }
                    refresh()
                    null
                }
            }
        }
    }

    /** Fire-and-forget: called during job cleanup, which must not block on the client thread. */
    fun remove(name: String, owner: Job) {
        val registration = active[name] ?: return
        if (registration.owner === owner && active.remove(name, registration)) {
            Minecraft.getInstance().execute { refresh() }
        }
    }

    private fun install(dispatcher: CommandDispatcher<FabricClientCommandSource>, name: String) {
        if (!installedOn(dispatcher).add(name)) return
        dispatcher.register(
            ClientCommands.literal(name)
                .requires { active.containsKey(name) }
                .executes { run(name, emptyList()) }
                .then(
                    ClientCommands.argument("args", StringArgumentType.greedyString())
                        .suggests { _, builder -> SharedSuggestionProvider.suggest(active[name]?.completions.orEmpty(), builder) }
                        .executes { ctx: CommandContext<FabricClientCommandSource> ->
                            run(name, splitArgs(StringArgumentType.getString(ctx, "args")))
                        },
                ),
        )
    }

    private fun run(name: String, args: List<String>): Int {
        val registration = active[name] ?: return 0
        registration.owner.post(Job.Event.Command(name, args))
        return 1
    }

    private fun installedOn(dispatcher: CommandDispatcher<*>) = installed.getOrPut(dispatcher) { mutableSetOf() }

    /** Rebuild the tab-completion tree so added/removed commands show up immediately. */
    private fun refresh() {
        if (Minecraft.getInstance().connection != null) ClientCommands.refreshCommandCompletions()
    }

    private fun <T> onClientThread(block: () -> T): T {
        val mc = Minecraft.getInstance()
        return if (mc.isSameThread) block() else CompletableFuture.supplyAsync(block, mc).join()
    }
}
