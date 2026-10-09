package dev.ilgax.minekt

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.api.ScriptHud
import dev.ilgax.minekt.hooks.GameEventSource
import dev.ilgax.minekt.hooks.InstallUi
import dev.ilgax.minekt.hooks.MacroRecorder
import dev.ilgax.minekt.hooks.PacketHooks
import dev.ilgax.minekt.share.ScriptInstaller
import dev.ilgax.minekt.hooks.ScriptCommands
import dev.ilgax.minekt.hooks.ScriptKeys
import dev.ilgax.minekt.hooks.ScriptManager
import dev.ilgax.minekt.hooks.ScriptRenderer
import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.TickClock
import dev.ilgax.minekt.script.ScriptClasspath
import dev.ilgax.minekt.script.CompilerSetup
import dev.ilgax.minekt.script.ScriptCompiler
import dev.ilgax.minekt.script.ScriptDependencies
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import net.minecraft.client.gui.components.toasts.SystemToast
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException
import kotlin.io.path.exists

object MinektClient : ClientModInitializer, Host {
    private val logger = LoggerFactory.getLogger("minekt")
    private const val PREFIX = "\\"
    private const val RECENT_ERRORS = 5
    private val EXAMPLES = listOf("hello", "whereami", "ores", "autowalk", "chatbot", "hud", "commands", "deathpoint", "stash", "waypoints", "baritone_mine")

    override val scriptDir: Path = FabricLoader.getInstance().gameDir.resolve("minekt")
    val jobs = JobManager(this)
    private val installs by lazy { InstallUi(ScriptInstaller(scriptDir), scriptDir, ::echo) }
    private val recorder by lazy { MacroRecorder(scriptDir) }

    /** The last few errors shown in chat, for the script manager. */
    private val recentErrors = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private val manager by lazy { ScriptManager(jobs, scriptDir, { installs.install(listOf(it)) }, { recentErrors.toList() }) }
    /** Set by `\menu`; the manager opens on the next tick, once the chat screen has closed. */
    @Volatile private var openManager = false

    override fun onInitializeClient() {
        ScriptClasspath.scriptDir = scriptDir
        ScriptDependencies.libDir = scriptDir.resolve("lib")
        ScriptDependencies.onDownload = { notifyCompiler(it, false) }
        installExamples()
        ScriptHud.register()
        ScriptCommands.register()
        ScriptKeys.register()
        installs.register()

        registerChatHooks()
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) jobs.dispatch(Job.Event.Chat(message.string))
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            jobs.dispatch(Job.Event.Chat(message.string))
        }
        val gameEvents = GameEventSource(jobs, onJoin = ::runAutorun).apply { register() }
        PacketHooks.filter = PacketHooks.Filter(jobs::filterPacket)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            TickClock.advance()
            jobs.dispatch(Job.Event.Tick)
            ScriptKeys.dispatch(jobs)
            if (mc.gui.screen() == null) jobs.pollKeys(InputConstants::isKeyDown)
            if (mc.level != null) ScriptRenderer.submitTick()
            gameEvents.tick(mc)
            recorder.tick(mc)
            if (ScriptKeys.consumeManagerKey()) openManager = true
            if (openManager) {
                openManager = false
                manager.open(mc)
            }
        }
        // Find (or, in the small build, download) the compiler while the game loads, then warm it up.
        Thread({
            CompilerSetup.run(
                libDir = scriptDir.resolve("lib"),
                addToClassPath = { jar -> FabricLauncherBase.getLauncher().addToClassPath(jar) },
                notify = ::notifyCompiler,
            )
            if (CompilerSetup.status == null) {
                val started = System.nanoTime()
                runCatching { ScriptCompiler.warmUp() }
                    .onSuccess { logger.info("Kotlin script compiler ready in {} ms", (System.nanoTime() - started) / 1_000_000) }
                    .onFailure { logger.error("Kotlin script compiler warm-up failed", it) }
            }
        }, "minekt-compiler").apply {
            isDaemon = true
            contextClassLoader = MinektClient::class.java.classLoader
        }.start()
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            jobs.storage.saveAll() // jobs save as they end, but the JVM may exit before they get to
            jobs.killAll()
        }
    }

    /**
     * `\commands`, and scripts' chat interceptors. Fabric runs ALLOW_* before MODIFY_* for the
     * same message, so ALLOW decides (hide / keep) and stashes a rewrite for MODIFY to apply.
     */
    private fun registerChatHooks() {
        var pendingSend: Pair<String, String>? = null
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (message.startsWith(PREFIX)) {
                runCatching { handleCommand(message.removePrefix(PREFIX).trim()) }
                    .onFailure { echo("Error: ${it.message}", MessageKind.ERROR) }
                return@register false
            }
            val result = jobs.interceptOutgoing(message) ?: return@register false
            pendingSend = if (result != message) message to result else null
            true
        }
        ClientSendMessageEvents.MODIFY_CHAT.register { message ->
            val rewrite = pendingSend?.takeIf { it.first == message }?.second
            pendingSend = null
            rewrite ?: message
        }

        var pendingGame: Pair<String, String>? = null
        ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
            if (overlay) return@register true
            val text = message.string
            val result = jobs.interceptIncoming(text) ?: return@register false
            pendingGame = if (result != text) text to result else null
            true
        }
        ClientReceiveMessageEvents.MODIFY_GAME.register { message, overlay ->
            val rewrite = pendingGame?.takeIf { !overlay && it.first == message.string }?.second
            pendingGame = null
            if (rewrite != null) Component.literal(rewrite) else message
        }
        // Player chat has no MODIFY event: hide the original and show the rewrite locally.
        ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
            val text = message.string
            val result = jobs.interceptIncoming(text) ?: return@register false
            if (result == text) return@register true
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(result))
            jobs.dispatch(Job.Event.Chat(result))
            false
        }
    }

    private fun handleCommand(line: String) {
        val parts = splitArgs(line)
        val command = parts.firstOrNull() ?: return help()
        when (command) {
            "help" -> help()
            "jobs" -> {
                val running = jobs.jobs
                if (running.isEmpty()) echo("No jobs running", MessageKind.INFO)
                running.forEach { job ->
                    val cpu = job.cpuPercent?.let { ", $it% CPU" }.orEmpty()
                    echo("[${job.id}] ${job.name} ${job.args.joinToString(" ")} — ${job.state.name.lowercase()}$cpu", MessageKind.INFO)
                }
            }
            "kill" -> when (val target = parts.getOrNull(1)) {
                null -> echo("Usage: \\kill <id|all>", MessageKind.ERROR)
                "all" -> jobs.killAll()
                else -> if (target.toIntOrNull()?.let(jobs::kill) != true) echo("No job $target", MessageKind.ERROR)
            }
            "eval" -> {
                val code = line.removePrefix("eval").trim()
                if (code.isEmpty()) echo("Usage: \\eval <kotlin code>", MessageKind.ERROR) else jobs.eval(code)
            }
            "install" -> installs.install(parts.drop(1))
            "update" -> installs.update(parts.getOrNull(1))
            "share" -> installs.share(parts.getOrNull(1))
            "menu" -> openManager = true
            "record" -> echo(
                when (val what = parts.getOrNull(1)) {
                    null -> "Usage: \\record <name> to start, \\record stop to save, \\record cancel"
                    "stop" -> recorder.stop()
                    "cancel" -> recorder.cancel()
                    else -> ScriptInstaller.sanitizeName(what).takeIf { it.isNotEmpty() }?.let(recorder::start) ?: "Pick a name with letters and digits"
                },
                MessageKind.INFO,
            )
            "scripts" -> echo("Scripts: " + jobs.availableScripts().joinToString(", ").ifEmpty { "(none)" }, MessageKind.INFO)
            else -> jobs.runScript(command, parts.drop(1))
        }
    }

    private fun help() {
        listOf(
            "Minekt — Kotlin scripts from ${scriptDir.fileName}/",
            "\\<script> [args]  run <script>.kts",
            "\\menu             open the script manager (or bind a key in Controls → Minekt)",
            "\\eval <code>      run a line of Kotlin",
            "\\jobs             list running scripts",
            "\\kill <id|all>    stop scripts",
            "\\scripts          list available scripts",
            "\\install <url>    install a script from a link (\\install for more)",
            "\\share <script>   copy a script to share it",
            "\\record <name>    record your movement as a macro script (\\record stop saves it)",
        ).forEach { echo(it, MessageKind.INFO) }
    }

    /** Run each `\command` line of `autorun.txt` when joining a world. `#` starts a comment. */
    private fun runAutorun() {
        val file = scriptDir.resolve("autorun.txt")
        if (!file.exists()) return
        Files.readAllLines(file).map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.forEach { line ->
            runCatching { handleCommand(line.removePrefix(PREFIX).trim()) }
                .onFailure { echo("autorun '$line' failed: ${it.message}", MessageKind.ERROR) }
        }
    }

    private fun installExamples() {
        if (scriptDir.exists()) return
        Files.createDirectories(scriptDir)
        for (name in EXAMPLES) {
            javaClass.getResourceAsStream("/minekt-examples/$name.kts")?.use { input ->
                Files.copy(input, scriptDir.resolve("$name.kts"))
            }
        }
        logger.info("Created {} with example scripts", scriptDir)
    }

    /** Compiler download news: a toast (it may happen before any world is open) and the log. */
    private fun notifyCompiler(message: String, failed: Boolean) {
        val mc = Minecraft.getInstance()
        mc.execute {
            SystemToast.add(
                mc.gui.toastManager(),
                SystemToast.SystemToastId(if (failed) 15_000L else 6_000L),
                Component.literal(if (failed) "Minekt: no Kotlin compiler" else "Minekt"),
                Component.literal(if (failed) "Scripts can't run. See the log for how to fix it." else message),
            )
        }
    }

    // ---- Host ---------------------------------------------------------------------------------

    override fun echo(message: String, kind: MessageKind) {
        val component = when (kind) {
            MessageKind.SCRIPT -> Component.literal(message)
            MessageKind.INFO -> Component.literal(message).withStyle(ChatFormatting.GRAY)
            MessageKind.ERROR -> Component.literal(message).withStyle(ChatFormatting.RED)
        }
        if (kind == MessageKind.ERROR) {
            recentErrors.addFirst(message)
            while (recentErrors.size > RECENT_ERRORS) recentErrors.pollLast()
        }
        val mc = Minecraft.getInstance()
        mc.execute {
            val player = mc.player
            if (player != null) player.sendSystemMessage(component) else logger.info("[script] {}", message)
        }
    }

    override fun registerCommand(name: String, completions: List<String>, owner: Job) =
        ScriptCommands.add(name, completions, owner)

    override fun unregisterCommand(name: String, owner: Job) = ScriptCommands.remove(name, owner)

    override fun bindingKeyName(slot: Int) = onGameThread { ScriptKeys.keyName(slot) }

    override fun <T> onGameThread(block: (Minecraft) -> T): T {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) return block(mc)
        try {
            return java.util.concurrent.CompletableFuture.supplyAsync({ block(mc) }, mc).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}

/** Whitespace-separated, with "double quotes" grouping. */
internal fun splitArgs(line: String): List<String> =
    Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
