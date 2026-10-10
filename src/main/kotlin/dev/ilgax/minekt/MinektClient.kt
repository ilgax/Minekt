package dev.ilgax.minekt

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.api.ScriptHud
import dev.ilgax.minekt.hooks.GameEventSource
import dev.ilgax.minekt.hooks.ErrorChat
import dev.ilgax.minekt.hooks.InputHooks
import dev.ilgax.minekt.hooks.InstallUi
import dev.ilgax.minekt.api.RawKey
import dev.ilgax.minekt.api.RawMouseButton
import dev.ilgax.minekt.api.RawMouseMove
import dev.ilgax.minekt.api.RawScroll
import dev.ilgax.minekt.hooks.LogViewer
import dev.ilgax.minekt.hooks.SettingsView
import dev.ilgax.minekt.hooks.MacroRecorder
import dev.ilgax.minekt.hooks.PacketHooks
import dev.ilgax.minekt.share.ScriptInstaller
import dev.ilgax.minekt.hooks.ScriptCommands
import dev.ilgax.minekt.hooks.ScriptKeys
import dev.ilgax.minekt.hooks.ScriptManager
import dev.ilgax.minekt.hooks.ScriptRenderer
import com.mojang.brigadier.arguments.StringArgumentType
import dev.ilgax.minekt.job.Autorun
import dev.ilgax.minekt.job.ErrorReport
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
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
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
    private val BUILT_IN_COMMANDS = setOf("help", "jobs", "kill", "eval", "install", "update", "share", "menu", "log", "settings", "record", "scripts")
    /** `[miner] …` or `[miner:3:5] …`: which script a chat message is about. */
    private val SCRIPT_PREFIX = Regex("^\\[([A-Za-z0-9_.-]+)(?::\\d+)*] ")
    private val EXAMPLES = listOf("hello", "whereami", "ores", "autowalk", "chatbot", "hud", "commands", "deathpoint", "stash", "waypoints", "baritone_mine")

    override val scriptDir: Path = FabricLoader.getInstance().gameDir.resolve("minekt")
    val jobs = JobManager(this)
    private val installs by lazy { InstallUi(ScriptInstaller(scriptDir), scriptDir, ::echo) }
    private val recorder by lazy { MacroRecorder(scriptDir) }

    /** The last few errors shown in chat, for the script manager. */
    private val recentErrors = java.util.concurrent.ConcurrentLinkedDeque<ScriptManager.RecentError>()
    private val logViewer by lazy { LogViewer(jobs.logs) }
    private val settingsView by lazy { SettingsView(jobs.settings) }
    private val manager: ScriptManager by lazy {
        ScriptManager(jobs, scriptDir, { installs.install(listOf(it)) }, { recentErrors.toList() }, openLog = { mc, script -> logViewer.open(mc, script, back = { manager.open(mc) }) },
            openSettings = { mc, script -> settingsView.open(mc, script, back = { manager.open(mc) }) },
        )
    }
    /** A screen to open on the next tick, once the chat screen has closed (`\menu`, `\log`). */
    @Volatile private var pendingScreen: ((Minecraft) -> Unit)? = null

    override fun onInitializeClient() {
        ScriptClasspath.scriptDir = scriptDir
        ScriptDependencies.libDir = scriptDir.resolve("lib")
        ScriptDependencies.onDownload = { notifyCompiler(it, false) }
        installExamples()
        ScriptHud.register()
        ScriptCommands.register()
        ScriptKeys.file = scriptDir.resolve("settings/keybinds.json")
        ScriptKeys.register()
        installs.register()
        registerLogCommand()

        registerChatHooks()
        ClientReceiveMessageEvents.GAME.register { message, overlay ->
            if (!overlay) jobs.dispatch(Job.Event.Chat(message.string))
        }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ ->
            jobs.dispatch(Job.Event.Chat(message.string))
        }
        val gameEvents = GameEventSource(jobs, onJoin = { runAutorun(it, joined = true) }, onDimensionChange = { runAutorun(it, joined = false) }).apply { register() }
        InputHooks.filter = jobs::filterInput
        PacketHooks.filter = PacketHooks.Filter { packet, incoming ->
            jobs.filterPacket(packet, incoming)?.also { if (incoming) gameEvents.packetReceived(it) }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            TickClock.advance()
            jobs.dispatch(Job.Event.Tick)
            ScriptKeys.tick(mc)
            InputHooks.listening = jobs.wantsInput(RawKey::class.java) || jobs.wantsInput(RawMouseButton::class.java) || jobs.wantsInput(RawScroll::class.java)
            InputHooks.listeningToMoves = jobs.wantsInput(RawMouseMove::class.java)
            if (mc.gui.screen() == null) jobs.pollKeys(InputConstants::isKeyDown)
            if (mc.level != null) ScriptRenderer.submitTick()
            ScriptHud.tick()
            gameEvents.tick(mc)
            recorder.tick(mc)
            if (ScriptKeys.consumeManagerKey()) pendingScreen = manager::open
            pendingScreen?.let { open ->
                pendingScreen = null
                open(mc)
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
        ClientLifecycleEvents.CLIENT_STARTED.register { runStartup() }
        ClientLifecycleEvents.CLIENT_STOPPING.register {
            jobs.storage.saveAll() // jobs save as they end, but the JVM may exit before they get to
            jobs.killAll()
        }
    }

    /** `\\commands`: lines starting with the prefix are handled here and never reach the server. */
    private fun registerChatHooks() {
        ClientSendMessageEvents.ALLOW_CHAT.register { message ->
            if (!message.startsWith(PREFIX)) return@register true
            runCatching { handleCommand(message.removePrefix(PREFIX).trim()) }
                .onFailure { echo("Error: ${it.message}", MessageKind.ERROR) }
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
            "menu" -> pendingScreen = manager::open
            "log" -> showLog(parts.getOrNull(1))
            "settings" -> when (val script = parts.getOrNull(1)) {
                null -> echo("Usage: \\settings <script>", MessageKind.ERROR)
                else -> pendingScreen = { mc -> settingsView.open(mc, script) }
            }
            "record" -> echo(
                when (val what = parts.getOrNull(1)) {
                    null -> "Usage: \\record <name> to start, \\record stop to save, \\record cancel"
                    "stop" -> recorder.stop()
                    "cancel" -> recorder.cancel()
                    else -> ScriptInstaller.sanitizeName(what).takeIf { it.isNotEmpty() }?.let(recorder::start) ?: "Pick a name with letters and digits"
                },
                MessageKind.INFO,
            )
            "scripts" -> {
                val scripts = jobs.availableScripts()
                echo("Scripts: " + scripts.joinToString(", ").ifEmpty { "(none)" }, MessageKind.INFO)
                // A script named like a built-in command can't be started by typing its name.
                scripts.filter { it in BUILT_IN_COMMANDS }.forEach {
                    echo("$it.kts has the name of the built-in \\$it command: rename it, or run it from \\menu", MessageKind.ERROR)
                }
            }
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
            "\\log <script>     show a script's log: output, errors with details",
            "\\settings <script> change a script's settings",
            "\\kill <id|all>    stop scripts",
            "\\scripts          list available scripts",
            "\\install <url>    install a script from a link (\\install for more)",
            "\\share <script>   copy a script to share it",
            "\\record <name>    record your movement as a macro script (\\record stop saves it)",
        ).forEach { echo(it, MessageKind.INFO) }
    }

    /** `\log <script>`, or the log of the script that failed last. Also `/minekt log`, which error messages link to. */
    private fun showLog(script: String?) {
        val name = script ?: recentErrors.firstNotNullOfOrNull { it.script }
            ?: return echo("Usage: \\log <script>. Logs are in ${scriptDir.fileName}/logs/", MessageKind.ERROR)
        pendingScreen = { mc -> logViewer.open(mc, name) }
    }

    private fun registerLogCommand() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("minekt").then(
                    ClientCommands.literal("log").then(
                        ClientCommands.argument("script", StringArgumentType.word()).executes { ctx ->
                            showLog(StringArgumentType.getString(ctx, "script"))
                            1
                        },
                    ),
                ),
            )
        }
    }

    /** Run the `\\command` lines of `autorun.txt` that apply here (see [Autorun]). */
    private fun runAutorun(mc: Minecraft, joined: Boolean) {
        val autorun = readAutorun() ?: return
        if (joined) autorun.problems.forEach { echo(it, MessageKind.ERROR) }
        val place = Autorun.Place(
            server = mc.currentServer?.takeIf { mc.singleplayerServer == null }?.ip,
            world = mc.singleplayerServer?.worldData?.levelName,
            dimension = mc.level?.dimension()?.identifier()?.toString() ?: return,
        )
        runAutorunLines(if (joined) autorun.onJoin(place) else autorun.onDimensionChange(place))
    }

    /** The `[startup]` section, once the game has started (before any world). Output goes to the log and script logs. */
    private fun runStartup() {
        val autorun = readAutorun() ?: return
        autorun.problems.forEach { logger.warn(it) }
        runAutorunLines(autorun.onStartup())
    }

    private fun readAutorun(): Autorun? {
        val file = scriptDir.resolve("autorun.txt")
        return if (file.exists()) Autorun(Files.readString(file)) else null
    }

    private fun runAutorunLines(lines: List<String>) {
        for (line in lines) {
            val command = line.removePrefix(PREFIX).trim()
            // Rejoining or coming back to a dimension shouldn't start a second copy of a script.
            val script = splitArgs(command).firstOrNull()
            if (script != null && jobs.jobs.any { it.name == script }) continue
            runCatching { handleCommand(command) }
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
        if (kind == MessageKind.ERROR) rememberError(SCRIPT_PREFIX.find(message)?.groupValues?.get(1), message)
        show(component, message)
    }

    override fun showError(report: ErrorReport) {
        rememberError(report.script, report.headline)
        ErrorChat.lines(report, scriptDir).forEach { show(it, it.string) }
    }

    private fun rememberError(script: String?, text: String) {
        recentErrors.addFirst(ScriptManager.RecentError(script, text))
        while (recentErrors.size > RECENT_ERRORS) recentErrors.pollLast()
    }

    private fun show(component: Component, plain: String) {
        val mc = Minecraft.getInstance()
        mc.execute {
            val player = mc.player
            if (player != null) player.sendSystemMessage(component) else logger.info("[script] {}", plain)
        }
    }

    override fun registerCommand(name: String, completions: List<String>, owner: Job) =
        ScriptCommands.add(name, completions, owner)

    override fun unregisterCommand(name: String, owner: Job) = ScriptCommands.remove(name, owner)

    override fun addKeybind(owner: Job, id: String, label: String, defaultKey: String?) = ScriptKeys.add(owner, id, label, defaultKey)

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
