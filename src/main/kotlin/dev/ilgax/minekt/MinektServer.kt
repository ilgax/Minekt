package dev.ilgax.minekt

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import dev.ilgax.minekt.api.server.ServerEvent
import dev.ilgax.minekt.api.toInfo
import dev.ilgax.minekt.job.ChatClicks
import dev.ilgax.minekt.job.ScriptLogs
import dev.ilgax.minekt.script.CompiledScriptCache
import dev.ilgax.minekt.script.CompilerSetup
import dev.ilgax.minekt.script.ScriptClasspath
import dev.ilgax.minekt.script.ScriptDependencies
import dev.ilgax.minekt.script.ServerScriptCompiler
import dev.ilgax.minekt.server.MksPermissions
import dev.ilgax.minekt.server.MksPermissions.Node
import dev.ilgax.minekt.server.ServerScripts
import net.fabricmc.api.EnvType
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Entity
import org.slf4j.LoggerFactory

/**
 * Server scripts, on dedicated servers and in singleplayer's integrated server: one [ServerScripts]
 * per server run, and the operator command `/mks` (not `/minekt`: the client has that one).
 *
 * Loaded on both sides, so nothing here may touch client classes.
 */
object MinektServer : ModInitializer {
    private val logger = LoggerFactory.getLogger("minekt")

    /** The running server's scripts, between SERVER_STARTED and SERVER_STOPPING. */
    @Volatile private var scripts: ServerScripts? = null

    override fun onInitialize() {
        if (FabricLoader.getInstance().environmentType == EnvType.SERVER) {
            // On the client, MinektClient does this (with toasts); the integrated server shares it.
            val dir = FabricLoader.getInstance().gameDir.resolve("minekt")
            ScriptClasspath.scriptDir = dir
            ScriptDependencies.libDir = dir.resolve("lib")
            CompiledScriptCache.dir = dir.resolve("lib").resolve("compiled")
            ScriptDependencies.onDownload = { logger.info("[minekt] {}", it) }
            CompilerSetup.startInBackground(dir.resolve("lib"), { message, failed ->
                if (failed) logger.error("[minekt] Scripts can't run: {}", message) else logger.info("[minekt] {}", message)
            }, ServerScriptCompiler)
        }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            scripts = ServerScripts(server).also { runCatching(it::start).onFailure { e -> logger.error("Minekt server scripts failed to start", e) } }
        }
        ServerLifecycleEvents.SERVER_STOPPING.register {
            scripts?.stop()
            scripts = null
        }
        ServerTickEvents.END_SERVER_TICK.register { scripts?.tick() }

        ServerMessageEvents.CHAT_MESSAGE.register { message, sender, _ ->
            scripts?.dispatch(ServerEvent.Chat(sender.name.string, message.signedContent()))
        }
        ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> scripts?.dispatch(ServerEvent.PlayerJoin(handler.player.name.string)) }
        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> scripts?.dispatch(ServerEvent.PlayerLeave(handler.player.name.string)) }
        ServerEntityEvents.ENTITY_LOAD.register { entity, _ ->
            scripts?.dispatch(ServerEvent.EntitySpawn::class.java) { ServerEvent.EntitySpawn(entity.toInfo(null)) }
        }
        ServerEntityEvents.ENTITY_UNLOAD.register { entity, _ ->
            scripts?.dispatch(ServerEvent.EntityRemoved::class.java) {
                val reason = when (entity.removalReason) {
                    Entity.RemovalReason.KILLED -> "killed"
                    Entity.RemovalReason.DISCARDED -> "discarded"
                    Entity.RemovalReason.CHANGED_DIMENSION -> "changed_dimension"
                    else -> "unloaded"
                }
                ServerEvent.EntityRemoved(entity.toInfo(null), reason == "killed", reason)
            }
        }
        ServerLivingEntityEvents.AFTER_DAMAGE.register { entity, source, _, taken, _ ->
            if (taken > 0) scripts?.dispatch(ServerEvent.Damage::class.java) {
                val attacker = source.entity
                ServerEvent.Damage(
                    entity.toInfo(null), taken, entity.health, source.typeHolder().registeredName,
                    attacker?.toInfo(null), source.directEntity?.takeIf { it !== attacker }?.toInfo(null),
                )
            }
        }
        ServerLivingEntityEvents.AFTER_DEATH.register { entity, source ->
            scripts?.dispatch(ServerEvent.Death::class.java) {
                ServerEvent.Death(entity.toInfo(null), source.typeHolder().registeredName, source.entity?.toInfo(null))
            }
        }
        PlayerBlockBreakEvents.AFTER.register { level, player, pos, state, _ ->
            val scripts = scripts ?: return@register
            if (level.isClientSide) return@register
            val block = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            scripts.dispatch(ServerEvent.BlockBreak(player.name.string, pos.immutable(), block, level.dimension().identifier().toString()))
        }

        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            registerMks(dispatcher)
            scripts?.commands?.reinstall(dispatcher)
        }
    }

    private const val NOT_RUNNING = "Minekt's server scripts aren't running yet"

    /**
     * `/mks run|kill|jobs|log|eval|scripts|…`. Each subcommand has its own permission node (see [MksPermissions]),
     * and is left out of a player's command tree (and Tab completion) when they may not use it.
     */
    private fun registerMks(dispatcher: CommandDispatcher<CommandSourceStack>) {
        fun command(block: (CommandContext<CommandSourceStack>, ServerScripts) -> Unit): (CommandContext<CommandSourceStack>) -> Int = { ctx ->
            val scripts = scripts
            if (scripts == null) {
                reply(ctx.source, NOT_RUNNING, ChatFormatting.RED)
            } else {
                runCatching { block(ctx, scripts) }.onFailure { reply(ctx.source, "Error: ${it.message}", ChatFormatting.RED) }
            }
            1
        }

        fun allowed(node: Node) = { source: CommandSourceStack -> MksPermissions.allows(source, node) }
        fun available() = scripts?.jobs?.availableScripts().orEmpty()
        val mayRunAny = { source: CommandSourceStack -> MksPermissions.mayRunAny(source, available()) }

        val scriptNames = { _: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder ->
            SharedSuggestionProvider.suggest(available(), builder)
        }
        val runnableScripts = { ctx: CommandContext<CommandSourceStack>, builder: com.mojang.brigadier.suggestion.SuggestionsBuilder ->
            SharedSuggestionProvider.suggest(available().filter { MksPermissions.mayRun(ctx.source, it) }, builder)
        }

        fun run(ctx: CommandContext<CommandSourceStack>, scripts: ServerScripts, args: List<String>) {
            val script = StringArgumentType.getString(ctx, "script")
            if (!MksPermissions.mayRun(ctx.source, script)) return reply(ctx.source, "You may not run $script", ChatFormatting.RED)
            scripts.jobs.runScript(script, args)
        }

        dispatcher.register(
            Commands.literal("mks")
                .requires { source -> Node.entries.any { it != Node.NOTIFY && MksPermissions.allows(source, it) } || mayRunAny(source) }
                .executes(command { ctx, scripts -> help(ctx.source, scripts) })
                .then(
                    Commands.literal("run").requires(mayRunAny).then(
                        Commands.argument("script", StringArgumentType.word()).suggests(runnableScripts)
                            .executes(command { ctx, scripts -> run(ctx, scripts, emptyList()) })
                            .then(
                                Commands.argument("args", StringArgumentType.greedyString())
                                    .executes(command { ctx, scripts -> run(ctx, scripts, splitArgs(StringArgumentType.getString(ctx, "args"))) }),
                            ),
                    ),
                )
                .then(
                    Commands.literal("kill").requires(allowed(Node.KILL)).then(
                        Commands.argument("job", StringArgumentType.word())
                            .suggests { _, builder -> SharedSuggestionProvider.suggest(listOf("all") + scripts?.jobs?.jobs.orEmpty().map { it.id.toString() }, builder) }
                            .executes(command { ctx, scripts ->
                                when (val target = StringArgumentType.getString(ctx, "job")) {
                                    "all" -> scripts.jobs.killAll()
                                    else -> if (target.toIntOrNull()?.let(scripts.jobs::kill) != true) reply(ctx.source, "No job $target", ChatFormatting.RED)
                                }
                            }),
                    ),
                )
                .then(
                    Commands.literal("jobs").requires(allowed(Node.JOBS)).executes(command { ctx, scripts ->
                        val running = scripts.jobs.jobs
                        if (running.isEmpty()) reply(ctx.source, "No jobs running")
                        running.forEach { job ->
                            val cpu = job.cpuPercent?.let { ", $it% CPU" }.orEmpty()
                            reply(ctx.source, "[${job.id}] ${job.name} ${job.args.joinToString(" ")} — ${job.state.name.lowercase()}$cpu")
                        }
                    }),
                )
                .then(
                    Commands.literal("log").requires(allowed(Node.LOG)).then(
                        Commands.argument("script", StringArgumentType.word()).suggests(scriptNames).executes(command { ctx, scripts ->
                            val script = StringArgumentType.getString(ctx, "script")
                            val entries = scripts.jobs.logs.recent(script).takeLast(LOG_LINES)
                            if (entries.isEmpty()) reply(ctx.source, "Nothing logged for $script since the server started; the whole log is ${scripts.jobs.logs.file(script)}")
                            entries.forEach { reply(ctx.source, ScriptLogs.format(it).trimEnd(), if (it.level == ScriptLogs.Level.ERROR) ChatFormatting.RED else ChatFormatting.GRAY) }
                        }),
                    ),
                )
                .then(
                    Commands.literal("eval").requires(allowed(Node.EVAL)).then(
                        Commands.argument("code", StringArgumentType.greedyString()).executes(command { ctx, scripts ->
                            scripts.jobs.eval(StringArgumentType.getString(ctx, "code"))
                        }),
                    ),
                )
                .then(
                    Commands.literal("scripts").requires(mayRunAny).executes(command { ctx, scripts ->
                        val names = scripts.jobs.availableScripts().filter { MksPermissions.mayRun(ctx.source, it) }
                        reply(ctx.source, "Scripts in ${scripts.scriptDir}: " + names.joinToString(", ").ifEmpty { "(none)" })
                    }),
                )
                .then(
                    Commands.literal("install").requires(allowed(Node.INSTALL))
                        .then(Commands.literal("confirm").executes(command { ctx, scripts -> scripts.management.answer(ctx.source, true, null) })
                            .then(Commands.argument("token", StringArgumentType.word()).executes(command { ctx, scripts ->
                                scripts.management.answer(ctx.source, true, StringArgumentType.getString(ctx, "token"))
                            })))
                        .then(Commands.literal("cancel").executes(command { ctx, scripts -> scripts.management.answer(ctx.source, false, null) })
                            .then(Commands.argument("token", StringArgumentType.word()).executes(command { ctx, scripts ->
                                scripts.management.answer(ctx.source, false, StringArgumentType.getString(ctx, "token"))
                            })))
                        .then(Commands.argument("link", StringArgumentType.greedyString()).executes(command { ctx, scripts ->
                            val parts = StringArgumentType.getString(ctx, "link").trim().split(' ').filter { it.isNotEmpty() }
                            scripts.management.install(ctx.source, parts[0], parts.getOrNull(1))
                        })),
                )
                .then(
                    Commands.literal("share").requires(allowed(Node.SHARE)).then(
                        Commands.argument("script", StringArgumentType.word()).suggests(scriptNames).executes(command { ctx, scripts ->
                            scripts.management.share(ctx.source, StringArgumentType.getString(ctx, "script"))
                        }),
                    ),
                )
                .then(
                    Commands.literal("settings").requires(allowed(Node.SETTINGS)).then(
                        Commands.argument("script", StringArgumentType.word()).suggests(scriptNames)
                            .executes(command { ctx, scripts -> scripts.management.showSettings(ctx.source, StringArgumentType.getString(ctx, "script")) })
                            .then(
                                Commands.argument("key", StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        SharedSuggestionProvider.suggest(scripts?.management?.settingKeys(StringArgumentType.getString(ctx, "script")).orEmpty(), builder)
                                    }
                                    .then(Commands.argument("value", StringArgumentType.greedyString()).executes(command { ctx, scripts ->
                                        scripts.management.changeSetting(
                                            ctx.source,
                                            StringArgumentType.getString(ctx, "script"),
                                            StringArgumentType.getString(ctx, "key"),
                                            StringArgumentType.getString(ctx, "value"),
                                        )
                                    })),
                            ),
                    ),
                )
                .then(
                    // Clickable chat text made with ChatClick.run, which goes to the players who get scripts' messages.
                    Commands.literal("click").requires(allowed(Node.NOTIFY)).then(
                        Commands.argument("id", LongArgumentType.longArg()).executes(command { ctx, _ ->
                            if (!ChatClicks.click(LongArgumentType.getLong(ctx, "id"))) reply(ctx.source, "That script isn't running anymore")
                        }),
                    ),
                ),
        )
    }

    /** The subcommands [source] may use. */
    private fun help(source: CommandSourceStack, scripts: ServerScripts) {
        reply(source, "Minekt server scripts, from ${scripts.scriptDir}")
        val runAny = MksPermissions.mayRunAny(source, scripts.jobs.availableScripts())
        listOf(
            runAny to "/mks run <script> [args]  run <script>.kts",
            runAny to "/mks scripts              list the scripts you may run",
            MksPermissions.allows(source, Node.EVAL) to "/mks eval <code>          run a line of Kotlin",
            MksPermissions.allows(source, Node.JOBS) to "/mks jobs                 list running scripts",
            MksPermissions.allows(source, Node.LOG) to "/mks log <script>         show a script's recent log",
            MksPermissions.allows(source, Node.KILL) to "/mks kill <id|all>        stop scripts",
            MksPermissions.allows(source, Node.SETTINGS) to "/mks settings <script> [key value]  show or change a script's settings",
            MksPermissions.allows(source, Node.INSTALL) to "/mks install <url> [name]  install a script from a link (then /mks install confirm)",
            MksPermissions.allows(source, Node.SHARE) to "/mks share <script>       copy a script so others can install it",
        ).filter { it.first }.forEach { reply(source, it.second) }
    }

    private fun reply(source: CommandSourceStack, text: String, color: ChatFormatting = ChatFormatting.GRAY) =
        source.sendSystemMessage(Component.literal(text).withStyle(color))

    private const val LOG_LINES = 15
}
