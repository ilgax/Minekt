package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.MinektEvent
import dev.ilgax.minekt.hooks.ClassRewrites
import dev.ilgax.minekt.hooks.MethodHooks
import dev.ilgax.minekt.script.CompilerSetup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm

/** Thrown inside a job's thread when it has been killed; unwinds the script. */
class JobKilledException : RuntimeException("job killed", null, false, false)

/** Thrown by `stop()` to end a job cleanly, e.g. from inside an event handler. */
class JobStopException : RuntimeException("job stopped", null, false, false)

/**
 * A running script: its main thread, event handlers, coroutines, and anything it must clean up.
 *
 * Lifetime: the script body runs first. Afterwards the job stays alive while it has handlers or
 * running coroutines, until `stop()`, `\kill`, or an error.
 */
class Job internal constructor(
    val id: Int,
    val name: String,
    val args: List<String>,
    private val source: SourceCode,
    internal val host: Host,
    internal val storage: PersistentStore,
    internal val bus: MessageBus,
    internal val logs: ScriptLogs,
    internal val settings: ScriptSettings,
    internal val jobs: JobManager,
    private val onExit: (Job) -> Unit,
) {
    sealed interface Event {
        data object Tick : Event
        data class Chat(val message: dev.ilgax.minekt.api.ChatMessage) : Event
        /** `/name args`, typed by [sender] (a player's name, or null for the client's own player or the server console). */
        data class Command(val name: String, val args: List<String>, val sender: String? = null) : Event
        /** A script keybind went down ([pressed]) or up. */
        data class Keybind(val id: String, val pressed: Boolean = true) : Event
        data class Game(val event: MinektEvent) : Event
        data class Message(val topic: String, val value: Any?, val from: String) : Event
        /** A packet received ([incoming]) or sent; on a server, from or to [player]. */
        data class Packet(val packet: net.minecraft.network.protocol.Packet<*>, val incoming: Boolean, val player: String? = null) : Event
        data class Setting(val key: String, val value: Any) : Event
        /** Clickable chat text made by this script was clicked. */
        data class Click(val id: Long) : Event
        /** Something happened on the server, for server scripts. */
        data class Server(val event: dev.ilgax.minekt.api.server.ServerEvent) : Event
    }

    enum class State { COMPILING, RUNNING, LISTENING, DONE }

    @Volatile var state = State.COMPILING
        private set

    /**
     * What the script body evaluated to (its last expression), once it has finished. Fails if the
     * script didn't compile, threw, or was killed or stopped before its body finished.
     */
    internal val result = java.util.concurrent.CompletableFuture<Any?>()

    @Volatile private var killed = false
    @Volatile private var stopRequested = false
    internal val isKilled: Boolean get() = killed

    /** Every thread that runs this job's script code (main + coroutine threads), for CPU accounting. */
    private val threads: MutableSet<Thread> = ConcurrentHashMap.newKeySet()
    private val thread = Watchdog.JobThread(this, ::run, "minekt-job-$id-$name").apply {
        isDaemon = true
        // Under Fabric the mod classes live in Knot's loader; the compiler resolves script
        // annotations (e.g. @file:Import) through the context loader, so it must be Knot's.
        contextClassLoader = Job::class.java.classLoader
        threads += this
    }
    private val events = LinkedBlockingQueue<Event>()

    internal val tickHandlers = CopyOnWriteArrayList<() -> Unit>()
    internal val chatHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.ChatMessage) -> Unit>()
    internal val commandHandlers = ConcurrentHashMap<String, (Event.Command) -> Unit>()
    /** Keybind id → handlers for it going down (`onPress`) and up (`onRelease`). */
    internal val keybindHandlers = ConcurrentHashMap<String, CopyOnWriteArrayList<KeybindHandler>>()

    class KeybindHandler(val onPress: Boolean, val handler: () -> Unit)
    internal val gameHandlers = CopyOnWriteArrayList<(MinektEvent) -> Unit>()
    /** Server scripts' `onChat`, `onPlayerJoin`, `onBlockBreak`…, each filtering the events it wants. */
    internal val serverHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.server.ServerEvent) -> Unit>()
    /** Server event types the handlers above are for (`ServerEvent` itself: all), so the server only builds wanted events. */
    internal val serverEventTypes: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()
    /** Event types the handlers above are for (`MinektEvent` itself: all), so unwanted events cost nothing. */
    internal val gameEventTypes: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()
    /** `awaitEvent` calls in progress: they may want any event. */
    internal val gameAwaiters = java.util.concurrent.atomic.AtomicInteger()

    internal fun wantsGame(type: Class<*>) = gameAwaiters.get() > 0 || gameEventTypes.any { it.isAssignableFrom(type) }
    internal val messageHandlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()
    internal val settingHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.SettingChange) -> Unit>()
    /** Settings this run declared with `by setting(…)`; others are dropped from the file when it ends cleanly. */
    internal val declaredSettings: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A packet listener or filter for packets of [type] in one direction; [handler] also gets the player, on a server. */
    class PacketHook(val type: Class<*>, val incoming: Boolean, val handler: (net.minecraft.network.protocol.Packet<*>, String?) -> Any?) {
        fun matches(packet: net.minecraft.network.protocol.Packet<*>, incoming: Boolean) = incoming == this.incoming && type.isInstance(packet)
    }

    /** `onPacket` / `onPacketSent`: run on this job's thread. */
    internal val packetHandlers = CopyOnWriteArrayList<PacketHook>()
    /** `interceptPacket` / `interceptPacketSent`: run synchronously on the network thread; see [JobManager]. */
    internal val packetFilters = CopyOnWriteArrayList<PacketHook>()


    /** A raw keyboard or mouse listener; runs on the game thread, see [JobManager.filterInput]. */
    class InputHandler(val type: Class<out dev.ilgax.minekt.api.InputEvent>, val handler: (dev.ilgax.minekt.api.InputEvent) -> Unit)

    internal val inputHandlers = CopyOnWriteArrayList<InputHandler>()

    /** Actions behind this script's clickable chat text, by id (see [ChatClicks]). They don't keep it alive. */
    internal val clickActions = ConcurrentHashMap<Long, () -> Unit>()

    /** `onHudRender` / `onWorldRender` handlers, run on the game thread every frame. */
    internal val hudRenderHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.HudCanvas) -> Unit>()
    internal val worldRenderHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.WorldCanvas) -> Unit>()

    /** Arbitrary cleanup registered by API helpers (unregistering HUD lines, keybind handlers, ...). */
    internal val cleanups = CopyOnWriteArrayList<() -> Unit>()

    internal fun start() = thread.start()

    fun kill() {
        killed = true
        thread.interrupt()
        shutdownCoroutines()
    }

    /** Called from API entry points so tight loops that use the API can still be killed. */
    internal fun checkAlive() {
        if (mustStop) throw JobKilledException()
    }

    /** Until when (System.nanoTime) `onStop` handlers may still run after a kill, or 0. */
    @Volatile private var stopGraceUntil = 0L

    /** Killed, and not in the short time `onStop` handlers get afterwards. Checked by the API and [Watchdog]. */
    internal val mustStop: Boolean get() = killed && (stopGraceUntil == 0L || System.nanoTime() > stopGraceUntil)

    /** `onStop` handlers: run once when the script ends, however it ends. */
    internal val stopHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.StopReason) -> Unit>()

    /** Write to this script's log, and with [chat] also to chat as `[name] text`. */
    internal fun log(level: ScriptLogs.Level, text: String, detail: String? = null, chat: MessageKind? = null) {
        logs.add(name, level, text, detail, id)
        if (chat != null) host.echo("[$name] $text", chat)
    }

    /** What the script itself prints with `echo`. */
    internal fun output(text: String) {
        logs.add(name, ScriptLogs.Level.ECHO, text, run = id)
        host.echo(text)
    }

    /** [output] with styled text: [plain] goes to the log. */
    internal fun output(text: net.minecraft.network.chat.Component, plain: String) {
        logs.add(name, ScriptLogs.Level.ECHO, plain, run = id)
        host.echo(text, plain)
    }

    // ---- CPU use --------------------------------------------------------------------------------

    private var lastCpuNanos = -1L
    private var lastSampleNanos = 0L
    private var warnedCpu = false

    /** Share of one CPU core this job used since the previous sample, in percent; null until known. */
    @Volatile var cpuPercent: Int? = null
        private set

    /** Called every few seconds by [JobManager]; warns once if the script keeps a core busy. */
    internal fun sampleCpu() {
        threads.removeIf { !it.isAlive && it !== thread }
        val cpu = Watchdog.cpuTime(threads) ?: return
        val now = System.nanoTime()
        // Compiling is the compiler's work, not the script's: measure only whole windows after it.
        val compiling = state == State.COMPILING
        if (!compiling && lastCpuNanos >= 0 && now > lastSampleNanos) {
            val percent = ((cpu - lastCpuNanos).coerceAtLeast(0) * 100 / (now - lastSampleNanos)).toInt()
            cpuPercent = percent
            if (percent >= CPU_WARN_PERCENT && !warnedCpu) {
                warnedCpu = true
                log(ScriptLogs.Level.WARN, "is keeping a CPU core busy ($percent%). ${host.commandPrefix}kill $id stops it.", chat = MessageKind.ERROR)
            }
        }
        lastCpuNanos = if (compiling) -1 else cpu
        lastSampleNanos = now
    }

    // ---- coroutines -----------------------------------------------------------------------------

    /**
     * Coroutines run on this job's own cached thread pool, so blocking API calls inside them
     * (`game { }`, `sleep`) are fine. Killing the job cancels the scope and interrupts the pool.
     */
    private val coroutinePool = lazy<ExecutorService> {
        Executors.newCachedThreadPool { runnable ->
            Watchdog.JobThread(this, runnable, "minekt-job-$id-$name-co").apply {
                isDaemon = true
                contextClassLoader = Job::class.java.classLoader
                threads += this
            }
        }
    }
    private val scopeDelegate = lazy {
        CoroutineScope(
            SupervisorJob() + coroutinePool.value.asCoroutineDispatcher() +
                CoroutineExceptionHandler { _, t -> onCoroutineFailure(t) },
        )
    }
    internal val scope: CoroutineScope by scopeDelegate

    /** One at a time and in order: for callbacks from the game (screen widgets) that must not race each other. */
    private val callbackDispatcher by lazy { coroutinePool.value.asCoroutineDispatcher().limitedParallelism(1) }

    /**
     * Run [action] on this job's side, off the game thread, so it can use the blocking API. Runs
     * alongside the script body; an exception in it ends the script like one in a handler would.
     */
    internal fun runCallback(action: () -> Unit) {
        if (killed || state == State.DONE) return
        scope.launch(callbackDispatcher) { action() }
    }

    /** Start a coroutine that runs its callbacks in line with [runCallback]'s (timers). */
    internal fun launchSerial(block: suspend CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
        checkAlive()
        return scope.launch(callbackDispatcher, block = block)
    }

    /** Script screens currently open; the job stays alive while any are. */
    internal val openScreens = java.util.concurrent.atomic.AtomicInteger()

    private fun coroutinesActive() =
        scopeDelegate.isInitialized() && scope.coroutineContext[kotlinx.coroutines.Job]!!.children.any { it.isActive }

    private fun onCoroutineFailure(t: Throwable) {
        if (t !is CancellationException) handleThrowable(t)
        stopRequested = true // a failing (or stop()ping) coroutine ends the whole script
    }

    private fun shutdownCoroutines() {
        if (scopeDelegate.isInitialized()) scope.cancel()
        if (coroutinePool.isInitialized()) coroutinePool.value.shutdownNow()
    }

    // ---- events ---------------------------------------------------------------------------------

    /** One-shot listeners behind the suspend `await…` functions. Checked before normal dispatch. */
    private class Waiter(val matches: (Event) -> Boolean, val result: CompletableDeferred<Event>)
    private val waiters = CopyOnWriteArrayList<Waiter>()

    internal suspend fun awaitEvent(matches: (Event) -> Boolean): Event {
        val waiter = Waiter(matches, CompletableDeferred())
        waiters += waiter
        try {
            return waiter.result.await()
        } finally {
            waiters -= waiter
        }
    }

    /**
     * At most one Tick is queued at a time: a script slower than the game skips ticks instead of
     * building a backlog that would delay its other events (commands, keys, chat).
     */
    private val tickQueued = AtomicBoolean(false)

    internal fun post(event: Event) {
        if (state != State.LISTENING && state != State.RUNNING) return
        for (waiter in waiters) {
            if (waiter.matches(event) && waiter.result.complete(event)) waiters -= waiter
        }
        val wanted = when (event) {
            Event.Tick -> tickHandlers.isNotEmpty() && tickQueued.compareAndSet(false, true)
            is Event.Chat -> chatHandlers.isNotEmpty()
            is Event.Command -> commandHandlers.containsKey(event.name)
            is Event.Keybind -> keybindHandlers[event.id]?.any { it.onPress == event.pressed } == true
            is Event.Click -> clickActions.containsKey(event.id)
            is Event.Game -> gameHandlers.isNotEmpty() && gameEventTypes.any { it.isInstance(event.event) }
            is Event.Message -> messageHandlers[event.topic]?.isNotEmpty() == true
            is Event.Packet -> packetHandlers.any { it.matches(event.packet, event.incoming) }
            is Event.Setting -> settingHandlers.isNotEmpty()
            is Event.Server -> serverHandlers.isNotEmpty()
        }
        if (wanted) events.offer(event)
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private fun run() {
        try {
            try {
                CompilerSetup.awaitReady { status -> host.echo("[$name] $status… the script starts when it's done", MessageKind.INFO) }
            } catch (e: IllegalStateException) {
                log(ScriptLogs.Level.ERROR, "Scripts can't run yet: ${e.message}", chat = MessageKind.ERROR)
                return
            }
            log(ScriptLogs.Level.INFO, "Started" + if (args.isEmpty()) "" else " with ${args.joinToString(" ")}")
            // Watchdog-instrumented, so \kill works even on loops that never call the API.
            val compiled = when (val result = host.compiler.compile(source)) {
                is ResultWithDiagnostics.Success -> host.compiler.instrumented(result.value)
                is ResultWithDiagnostics.Failure -> {
                    reportDiagnostics(result.reports)
                    this.result.completeExceptionally(IllegalStateException("$name didn't compile (its errors are in chat and ${host.commandPrefix}log $name)"))
                    return
                }
            }
            checkAlive()
            state = State.RUNNING
            val api = host.receiver(this)
            val evaluation = ScriptEvaluationConfiguration {
                implicitReceivers(api)
                jvm { baseClassLoader(Job::class.java.classLoader) }
            }
            val result = runBlocking { BasicJvmScriptEvaluator()(compiled, evaluation) }
            when (result) {
                is ResultWithDiagnostics.Failure -> reportDiagnostics(result.reports)
                is ResultWithDiagnostics.Success -> when (val value = result.value.returnValue) {
                    is ResultValue.Error -> {
                        // stop() in the body is a normal end, not a failure.
                        if (rootCause(value.error) is JobStopException) this.result.complete(null)
                        else this.result.completeExceptionally(IllegalStateException("$name failed: ${ErrorReport.describe(rootCause(value.error))}", value.error))
                        handleThrowable(value.error)
                    }
                    is ResultValue.Value -> {
                        this.result.complete(value.value)
                        // `\eval launch { … }` or `\eval onTick { … }` return a handle; not worth echoing.
                        if (name == EVAL_NAME && value.value !is kotlinx.coroutines.Job && value.value !is dev.ilgax.minekt.api.Handler) {
                            logs.add(name, ScriptLogs.Level.ECHO, "= ${value.value}", run = id)
                            host.echo("= ${value.value}", MessageKind.INFO)
                        }
                        listen()
                    }
                    else -> {
                        this.result.complete(Unit)
                        listen()
                    }
                }
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        } finally {
            if (!result.isDone) {
                result.completeExceptionally(IllegalStateException(if (killed) "$name was killed before it finished" else "$name ended before its body finished"))
            }
            runStopHandlers()
            state = State.DONE
            cleanup()
            logEnd()
            onExit(this)
        }
    }

    private val startedNanos = System.nanoTime()
    @Volatile private var failed = false

    private fun logEnd() {
        val seconds = (System.nanoTime() - startedNanos) / 1e9
        val how = when {
            killed -> "Killed"
            failed -> "Ended by an error"
            else -> "Finished"
        }
        logs.add(name, ScriptLogs.Level.INFO, "%s after %.1f s".format(how, seconds), run = id)
    }

    private fun keepAlive() = !killed && !stopRequested && (hasHandlers() || coroutinesActive())

    /** After the script body returns, keep dispatching events while it has handlers or coroutines. */
    private fun listen() {
        if (!keepAlive()) return
        state = State.LISTENING
        try {
            while (keepAlive()) {
                val event = events.poll(250, TimeUnit.MILLISECONDS) ?: continue
                deliver(event)
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        }
    }

    /** Throw if this is the game's thread, where waiting would freeze the game (see `Minekt.checkCanWait`). */
    internal fun checkCanWait(what: String) {
        checkAlive()
        check(!host.isGameThread()) {
            "$what can't be used here: this code runs on the game's own thread (like onHudRender, onWorldRender, " +
                "onRawKey, onRawMouse, onMouseMove, hooks and Fabric event listeners do), and waiting there freezes " +
                "the game. Do the slow part in onTick or launch { } and only use its result here."
        }
    }

    /** Set while handlers run, so a wait inside a handler doesn't run other handlers in the middle of it. */
    private var delivering = false

    /** Run [event]'s handlers, on this job's main thread. */
    private fun deliver(event: Event) {
        delivering = true
        try {
            when (event) {
                Event.Tick -> {
                    tickQueued.set(false)
                    tickHandlers.forEach { it() }
                }
                is Event.Chat -> chatHandlers.forEach { it(event.message) }
                is Event.Command -> commandHandlers[event.name]?.invoke(event)
                is Event.Keybind -> keybindHandlers[event.id]?.forEach { if (it.onPress == event.pressed) it.handler() }
                is Event.Click -> clickActions[event.id]?.invoke()
                is Event.Game -> gameHandlers.forEach { it(event.event) }
                is Event.Message -> messageHandlers[event.topic]?.forEach { it(event.value) }
                is Event.Packet -> packetHandlers.forEach { if (it.matches(event.packet, event.incoming)) it.handler(event.packet, event.player) }
                is Event.Setting -> settingHandlers.forEach { it(dev.ilgax.minekt.api.SettingChange(event.key, event.value)) }
                is Event.Server -> serverHandlers.forEach { it(event.event) }
            }
        } finally {
            delivering = false
        }
    }

    /**
     * Wait until [done], checked again after [checkMs]. When the script's body waits (`sleep`, `waitTicks`),
     * handlers for events that arrive meanwhile run right here, on the same thread, so a loop in the body
     * sees what its handlers do. Elsewhere (coroutines, inside a handler) it only waits.
     * Throws [InterruptedException] when the job is killed.
     */
    internal fun waitDelivering(checkMs: () -> Long, done: () -> Boolean) {
        val pump = Thread.currentThread() === thread && !delivering && state == State.RUNNING
        while (!done()) {
            checkAlive()
            if (pump) events.poll(checkMs(), TimeUnit.MILLISECONDS)?.let(::deliver)
            else Thread.sleep(checkMs())
        }
    }

    private fun hasHandlers() =
        tickHandlers.isNotEmpty() || chatHandlers.isNotEmpty() || commandHandlers.isNotEmpty() ||
            gameHandlers.isNotEmpty() || serverHandlers.isNotEmpty() || keybindHandlers.values.any { it.isNotEmpty() } ||
            messageHandlers.values.any { it.isNotEmpty() } || openScreens.get() > 0 || settingHandlers.isNotEmpty() || inputHandlers.isNotEmpty() ||
            hudRenderHandlers.isNotEmpty() || worldRenderHandlers.isNotEmpty() ||
            packetHandlers.isNotEmpty() || packetFilters.isNotEmpty() || MethodHooks.hasHooks(this) || ClassRewrites.has(this)

    internal fun handleThrowable(t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        when {
            killed || root is JobKilledException || root is InterruptedException -> Unit
            t is JobStopException || root is JobStopException -> stopRequested = true
            else -> {
                failed = true
                report(t)
            }
        }
    }

    private fun report(t: Throwable) {
        val report = ErrorReport.fromThrowable(name, t, source)
        logs.add(name, ScriptLogs.Level.ERROR, report.headline.substringAfter("] "), report.detail, id)
        host.showError(report)
        LOGGER.error("Script {} failed", name, t)
    }

    /**
     * The script ended: run its `onStop` handlers on its own thread, with the API still working (even
     * after a kill, for up to [STOP_GRACE_MS]), before its handlers, HUD, keybinds… are taken down.
     */
    private fun runStopHandlers() {
        if (stopHandlers.isEmpty()) return
        val reason = when {
            killed -> dev.ilgax.minekt.api.StopReason.KILLED
            failed -> dev.ilgax.minekt.api.StopReason.FAILED
            else -> dev.ilgax.minekt.api.StopReason.FINISHED
        }
        Thread.interrupted() // \kill interrupts the thread; let the handlers sleep and wait
        stopGraceUntil = System.nanoTime() + STOP_GRACE_MS * 1_000_000
        try {
            for (handler in stopHandlers) {
                try {
                    handler(reason)
                } catch (t: Throwable) {
                    val root = generateSequence(t) { it.cause }.last()
                    if (root is JobKilledException) {
                        log(ScriptLogs.Level.WARN, "onStop took longer than ${STOP_GRACE_MS / 1000} s and was stopped", chat = MessageKind.ERROR)
                        break
                    }
                    if (root !is JobStopException && root !is InterruptedException) report(t)
                }
            }
        } finally {
            stopGraceUntil = 0L
        }
    }

    private fun reportDiagnostics(reports: List<ScriptDiagnostic>) {
        failed = true
        val errors = reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.map { ErrorReport.fromDiagnostic(name, it, source) }
        reports.filter { it.severity == ScriptDiagnostic.Severity.WARNING }.forEach { warning ->
            val report = ErrorReport.fromDiagnostic(name, warning, source)
            logs.add(name, ScriptLogs.Level.WARN, "line ${report.line ?: "?"}: ${report.summary}", run = id)
        }
        errors.forEach { report ->
            logs.add(name, ScriptLogs.Level.ERROR, "line ${report.line ?: "?"}: ${report.summary}", report.detail.takeIf { '\n' in it }, id)
        }
        // A broken line often causes several errors: show the first few, the rest are in the log.
        errors.take(MAX_ERRORS_SHOWN).forEach(host::showError)
        if (errors.size > MAX_ERRORS_SHOWN) host.echo("[$name] …and ${errors.size - MAX_ERRORS_SHOWN} more errors (${host.commandPrefix}log $name)", MessageKind.ERROR)
        reports.mapNotNull { it.exception }.forEach { LOGGER.error("Script {} compile error", name, it) }
    }

    private fun cleanup() {
        shutdownCoroutines()
        waiters.forEach { it.result.cancel() }
        packetFilters.clear()
        MethodHooks.removeAll(this)
        ClassRewrites.removeAll(this)
        ChatClicks.removeAll(this)
        if (declaredSettings.isNotEmpty() && !killed && !failed) runCatching { settings.keepOnly(name, declaredSettings) }
        try {
            storage.release(this) // final save of `by persistent` values
        } catch (e: Exception) {
            log(ScriptLogs.Level.ERROR, "Couldn't save persistent data: ${e.message}", chat = MessageKind.ERROR)
            LOGGER.error("Saving persistent data of {} failed", name, e)
        }
        cleanups.forEach { runCatching(it) }
        commandHandlers.keys.forEach { name -> runCatching { host.unregisterCommand(name, this) } }
    }

    private fun rootCause(t: Throwable) = generateSequence(t) { it.cause }.last()

    companion object {
        const val EVAL_NAME = "eval"
        /** How long `onStop` handlers may run after a kill. */
        private const val STOP_GRACE_MS = 3000L
        private const val CPU_WARN_PERCENT = 90
        private const val MAX_ERRORS_SHOWN = 3
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
    }
}
