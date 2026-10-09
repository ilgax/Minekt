package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.GameEvent
import dev.ilgax.minekt.api.Minekt
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
    private val onExit: (Job) -> Unit,
) {
    sealed interface Event {
        data object Tick : Event
        data class Chat(val text: String) : Event
        data class Command(val name: String, val args: List<String>) : Event
        data class Binding(val slot: Int) : Event
        data class Key(val watch: KeyWatch) : Event
        data class Game(val event: GameEvent) : Event
        data class Message(val topic: String, val value: Any?, val from: String) : Event
    }

    enum class State { COMPILING, RUNNING, LISTENING, DONE }

    @Volatile var state = State.COMPILING
        private set

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
    internal val bindingHandlers = ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
    internal val gameHandlers = CopyOnWriteArrayList<(GameEvent) -> Unit>()
    internal val messageHandlers = ConcurrentHashMap<String, CopyOnWriteArrayList<(Any?) -> Unit>>()

    /** Run synchronously on the client thread for each incoming / outgoing chat line. See [JobManager]. */
    internal val incomingInterceptors = CopyOnWriteArrayList<(String) -> String?>()
    internal val outgoingInterceptors = CopyOnWriteArrayList<(String) -> String?>()

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
                host.echo("[$name] is keeping a CPU core busy ($percent%). \\kill $id stops it.", MessageKind.ERROR)
            }
        }
        lastCpuNanos = if (compiling) -1 else cpu
        lastSampleNanos = now
    }

    // ---- keys -----------------------------------------------------------------------------------

    /** A physical key a script watches via `onKey`; polled on the client thread, see [pollKeys]. */
    class KeyWatch(val keyCode: Int, val handler: () -> Unit) {
        internal var wasDown = false
    }

    internal val keyWatches = CopyOnWriteArrayList<KeyWatch>()

    /** Client thread, once per tick: turn watched keys going down into [Event.Key]s. */
    internal fun pollKeys(isDown: (Int) -> Boolean) {
        for (watch in keyWatches) {
            val down = isDown(watch.keyCode)
            if (down && !watch.wasDown) post(Event.Key(watch))
            watch.wasDown = down
        }
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
            is Event.Key -> true
            is Event.Chat -> chatHandlers.isNotEmpty()
            is Event.Command -> commandHandlers.containsKey(event.name)
            is Event.Binding -> bindingHandlers[event.slot]?.isNotEmpty() == true
            is Event.Game -> gameHandlers.isNotEmpty()
            is Event.Message -> messageHandlers[event.topic]?.isNotEmpty() == true
        }
        if (wanted) events.offer(event)
    }

    // ---- lifecycle ------------------------------------------------------------------------------

    private fun run() {
        try {
            // Watchdog-instrumented, so \kill works even on loops that never call the API.
            val compiled = when (val result = ScriptCompiler.compile(source)) {
                is ResultWithDiagnostics.Success -> ScriptCompiler.instrumented(result.value)
                is ResultWithDiagnostics.Failure -> {
                    reportDiagnostics(result.reports)
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
                    is ResultValue.Error -> handleThrowable(value.error)
                    is ResultValue.Value -> {
                        // `\eval launch { … }` returns a coroutine handle; not worth echoing.
                        if (name == EVAL_NAME && value.value !is kotlinx.coroutines.Job) host.echo("= ${value.value}", MessageKind.INFO)
                        listen()
                    }
                    else -> listen()
                }
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        } finally {
            state = State.DONE
            cleanup()
            onExit(this)
        }
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
                    is Event.Key -> event.watch.handler()
                    is Event.Chat -> chatHandlers.forEach { it(event.text) }
                    is Event.Command -> commandHandlers[event.name]?.invoke(event.args)
                    is Event.Binding -> bindingHandlers[event.slot]?.forEach { it() }
                    is Event.Game -> gameHandlers.forEach { it(event.event) }
                    is Event.Message -> messageHandlers[event.topic]?.forEach { it(event.value) }
                }
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        }
    }

    private fun hasHandlers() =
        tickHandlers.isNotEmpty() || chatHandlers.isNotEmpty() || commandHandlers.isNotEmpty() ||
            keyWatches.isNotEmpty() || gameHandlers.isNotEmpty() || bindingHandlers.values.any { it.isNotEmpty() } ||
            messageHandlers.values.any { it.isNotEmpty() } || openScreens.get() > 0 ||
            incomingInterceptors.isNotEmpty() || outgoingInterceptors.isNotEmpty()

    internal fun handleThrowable(t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        when {
            killed || root is JobKilledException || root is InterruptedException -> Unit
            t is JobStopException || root is JobStopException -> stopRequested = true
            else -> {
                val frame = root.stackTrace.firstOrNull { it.className.startsWith("Script") || it.fileName?.endsWith(".kts") == true }
                val where = frame?.let { " (line ${it.lineNumber})" }.orEmpty()
                // The watchdog's own message says it all; for anything else the exception type helps.
                val what = if (root is ScriptTimeoutException) root.message else "${root.javaClass.simpleName}: ${root.message}"
                host.echo("[$name] $what$where", MessageKind.ERROR)
                LOGGER.error("Script {} failed", name, t)
            }
        }
    }

    private fun reportDiagnostics(reports: List<ScriptDiagnostic>) {
        reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.forEach { diagnostic ->
            val line = diagnostic.location?.start?.let { ":${it.line}:${it.col}" }.orEmpty()
            host.echo("[$name$line] ${diagnostic.message}", MessageKind.ERROR)
            diagnostic.exception?.let { LOGGER.error("Script {} compile error", name, it) }
        }
    }

    private fun cleanup() {
        shutdownCoroutines()
        waiters.forEach { it.result.cancel() }
        incomingInterceptors.clear()
        outgoingInterceptors.clear()
        try {
            storage.release(this) // final save of `by persistent` values
        } catch (e: Exception) {
            host.echo("[$name] Couldn't save persistent data: ${e.message}", MessageKind.ERROR)
            LOGGER.error("Saving persistent data of {} failed", name, e)
        }
        cleanups.forEach { runCatching(it) }
        commandHandlers.keys.forEach { name -> runCatching { host.unregisterCommand(name, this) } }
        if (heldKeys.isNotEmpty()) {
            runCatching { host.onGameThread { heldKeys.forEach { key -> key.setDown(false) } } }
            heldKeys.clear()
        }
    }

    companion object {
        const val EVAL_NAME = "eval"
        private const val CPU_WARN_PERCENT = 90
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
    }
}
