package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.Minekt
import dev.ilgax.minekt.hooks.ClassRewrites
import dev.ilgax.minekt.hooks.MethodHooks
import dev.ilgax.minekt.script.CompilerSetup
import dev.ilgax.minekt.script.ScriptCompiler
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.minecraft.client.KeyMapping
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
        data class Chat(val text: String) : Event
        data class Command(val name: String, val args: List<String>) : Event
        /** A script keybind went down ([pressed]) or up. */
        data class Keybind(val id: String, val pressed: Boolean = true) : Event
        data class Game(val event: GameEvent) : Event
        data class Message(val topic: String, val value: Any?, val from: String) : Event
        data class Packet(val packet: net.minecraft.network.protocol.Packet<*>, val incoming: Boolean) : Event
        data class Setting(val key: String, val value: Any) : Event
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
    internal val chatHandlers = CopyOnWriteArrayList<(String) -> Unit>()
    internal val commandHandlers = ConcurrentHashMap<String, (List<String>) -> Unit>()
    /** `keybind(…).onPress { }`, by keybind id. */
    /** Keybind id → handlers for it going down (`onPress`) and up (`onRelease`). */
    internal val keybindHandlers = ConcurrentHashMap<String, CopyOnWriteArrayList<KeybindHandler>>()

    class KeybindHandler(val onPress: Boolean, val handler: () -> Unit)
    internal val gameHandlers = CopyOnWriteArrayList<(GameEvent) -> Unit>()
    /** Event types the handlers above are for (`GameEvent` itself: all), so unwanted events cost nothing. */
    internal val gameEventTypes: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()
    /** `awaitEvent` calls in progress: they may want any event. */
    internal val gameAwaiters = java.util.concurrent.atomic.AtomicInteger()

    internal fun wantsGame(type: Class<*>) = gameAwaiters.get() > 0 || gameEventTypes.any { it.isAssignableFrom(type) }
    internal val messageHandlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()
    internal val settingHandlers = CopyOnWriteArrayList<(String, Any) -> Unit>()
    /** Settings this run declared with `by setting(…)`; others are dropped from the file when it ends cleanly. */
    internal val declaredSettings: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** A packet listener or filter for packets of [type] in one direction. */
    class PacketHook(val type: Class<*>, val incoming: Boolean, val handler: (net.minecraft.network.protocol.Packet<*>) -> Any?) {
        fun matches(packet: net.minecraft.network.protocol.Packet<*>, incoming: Boolean) = incoming == this.incoming && type.isInstance(packet)
    }

    /** `onPacket` / `onPacketSent`: run on this job's thread. */
    internal val packetHandlers = CopyOnWriteArrayList<PacketHook>()
    /** `interceptPacket` / `interceptPacketSent`: run synchronously on the network thread; see [JobManager]. */
    internal val packetFilters = CopyOnWriteArrayList<PacketHook>()


    /** A raw keyboard or mouse listener; runs on the game thread, see [JobManager.filterInput]. */
    class InputHandler(val type: Class<out dev.ilgax.minekt.api.InputEvent>, val handler: (dev.ilgax.minekt.api.InputEvent) -> Unit)

    internal val inputHandlers = CopyOnWriteArrayList<InputHandler>()

    /** `onHudRender` / `onWorldRender` handlers, run on the game thread every frame. */
    internal val hudRenderHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.HudCanvas) -> Unit>()
    internal val worldRenderHandlers = CopyOnWriteArrayList<(dev.ilgax.minekt.api.WorldCanvas) -> Unit>()

    /** Keys the script pressed; released when the job ends so the player doesn't keep walking. */
    internal val heldKeys: MutableSet<KeyMapping> = ConcurrentHashMap.newKeySet()
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
        if (killed) throw JobKilledException()
    }

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
                log(ScriptLogs.Level.WARN, "is keeping a CPU core busy ($percent%). \\kill $id stops it.", chat = MessageKind.ERROR)
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
            is Event.Game -> gameHandlers.isNotEmpty() && gameEventTypes.any { it.isInstance(event.event) }
            is Event.Message -> messageHandlers[event.topic]?.isNotEmpty() == true
            is Event.Packet -> packetHandlers.any { it.matches(event.packet, event.incoming) }
            is Event.Setting -> settingHandlers.isNotEmpty()
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
            val compiled = when (val result = ScriptCompiler.compile(source)) {
                is ResultWithDiagnostics.Success -> ScriptCompiler.instrumented(result.value)
                is ResultWithDiagnostics.Failure -> {
                    reportDiagnostics(result.reports)
                    this.result.completeExceptionally(IllegalStateException("$name didn't compile (its errors are in chat and \\log $name)"))
                    return
                }
            }
            checkAlive()
            state = State.RUNNING
            val api = Minekt(this)
            val evaluation = ScriptEvaluationConfiguration {
                implicitReceivers(api)
                jvm { baseClassLoader(Minekt::class.java.classLoader) }
            }
            val result = runBlocking { BasicJvmScriptEvaluator()(compiled, evaluation) }
            when (result) {
                is ResultWithDiagnostics.Failure -> reportDiagnostics(result.reports)
                is ResultWithDiagnostics.Success -> when (val value = result.value.returnValue) {
                    is ResultValue.Error -> {
                        this.result.completeExceptionally(IllegalStateException("$name failed: ${ErrorReport.describe(rootCause(value.error))}", value.error))
                        handleThrowable(value.error)
                    }
                    is ResultValue.Value -> {
                        this.result.complete(value.value)
                        // `\eval launch { … }` returns a coroutine handle; not worth echoing.
                        if (name == EVAL_NAME && value.value !is kotlinx.coroutines.Job) {
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
                when (event) {
                    Event.Tick -> {
                        tickQueued.set(false)
                        tickHandlers.forEach { it() }
                    }
                    is Event.Chat -> chatHandlers.forEach { it(event.text) }
                    is Event.Command -> commandHandlers[event.name]?.invoke(event.args)
                    is Event.Keybind -> keybindHandlers[event.id]?.forEach { if (it.onPress == event.pressed) it.handler() }
                    is Event.Game -> gameHandlers.forEach { it(event.event) }
                    is Event.Message -> messageHandlers[event.topic]?.forEach { it(event.value) }
                    is Event.Packet -> packetHandlers.forEach { if (it.matches(event.packet, event.incoming)) it.handler(event.packet) }
                    is Event.Setting -> settingHandlers.forEach { it(event.key, event.value) }
                }
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        }
    }

    private fun hasHandlers() =
        tickHandlers.isNotEmpty() || chatHandlers.isNotEmpty() || commandHandlers.isNotEmpty() ||
            gameHandlers.isNotEmpty() || keybindHandlers.values.any { it.isNotEmpty() } ||
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
                val report = ErrorReport.fromThrowable(name, t, source)
                logs.add(name, ScriptLogs.Level.ERROR, report.headline.substringAfter("] "), report.detail, id)
                host.showError(report)
                LOGGER.error("Script {} failed", name, t)
            }
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
        if (errors.size > MAX_ERRORS_SHOWN) host.echo("[$name] …and ${errors.size - MAX_ERRORS_SHOWN} more errors (\\log $name)", MessageKind.ERROR)
        reports.mapNotNull { it.exception }.forEach { LOGGER.error("Script {} compile error", name, it) }
    }

    private fun cleanup() {
        shutdownCoroutines()
        waiters.forEach { it.result.cancel() }
        packetFilters.clear()
        MethodHooks.removeAll(this)
        ClassRewrites.removeAll(this)
        if (declaredSettings.isNotEmpty() && !killed && !failed) runCatching { settings.keepOnly(name, declaredSettings) }
        try {
            storage.release(this) // final save of `by persistent` values
        } catch (e: Exception) {
            log(ScriptLogs.Level.ERROR, "Couldn't save persistent data: ${e.message}", chat = MessageKind.ERROR)
            LOGGER.error("Saving persistent data of {} failed", name, e)
        }
        cleanups.forEach { runCatching(it) }
        commandHandlers.keys.forEach { name -> runCatching { host.unregisterCommand(name, this) } }
        if (heldKeys.isNotEmpty()) {
            runCatching { host.onGameThread { heldKeys.forEach { key -> key.setDown(false) } } }
            heldKeys.clear()
        }
    }

    private fun rootCause(t: Throwable) = generateSequence(t) { it.cause }.last()

    companion object {
        const val EVAL_NAME = "eval"
        private const val CPU_WARN_PERCENT = 90
        private const val MAX_ERRORS_SHOWN = 3
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
    }
}
