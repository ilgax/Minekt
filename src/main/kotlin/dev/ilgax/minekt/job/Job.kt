package dev.ilgax.minekt.job

import dev.ilgax.minekt.api.Minekt
import dev.ilgax.minekt.script.ScriptCompiler
import net.minecraft.client.KeyMapping
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlinx.coroutines.runBlocking

/** Thrown inside a job's thread when it has been killed; unwinds the script. */
class JobKilledException : RuntimeException("job killed", null, false, false)

/** Thrown by `stop()` to end a job cleanly, e.g. from inside an event handler. */
class JobStopException : RuntimeException("job stopped", null, false, false)

/** A running script: one thread, its event handlers, and anything it must clean up on exit. */
class Job internal constructor(
    val id: Int,
    val name: String,
    val args: List<String>,
    private val source: SourceCode,
    internal val host: Host,
    private val onExit: (Job) -> Unit,
) {
    sealed interface Event {
        data object Tick : Event
        data class Chat(val text: String) : Event
        data class Command(val name: String, val args: List<String>) : Event
        data class Binding(val slot: Int) : Event
        data class Key(val watch: KeyWatch) : Event
    }

    enum class State { COMPILING, RUNNING, LISTENING, DONE }

    @Volatile var state = State.COMPILING
        private set

    @Volatile private var killed = false
    private val thread = Thread(::run, "minekt-job-$id-$name").apply {
        isDaemon = true
        // Under Fabric the mod classes live in Knot's loader; the compiler resolves script
        // annotations (e.g. @file:Import) through the context loader, so it must be Knot's.
        contextClassLoader = Job::class.java.classLoader
    }
    private val events = LinkedBlockingQueue<Event>()

    internal val tickHandlers = CopyOnWriteArrayList<() -> Unit>()
    internal val chatHandlers = CopyOnWriteArrayList<(String) -> Unit>()
    internal val commandHandlers = java.util.concurrent.ConcurrentHashMap<String, (List<String>) -> Unit>()
    internal val bindingHandlers = java.util.concurrent.ConcurrentHashMap<Int, CopyOnWriteArrayList<() -> Unit>>()
    /** Keys the script pressed; released when the job ends so the player doesn't keep walking. */
    internal val heldKeys: MutableSet<KeyMapping> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Arbitrary cleanup registered by API helpers (unregistering HUD lines, keybind handlers, ...). */
    internal val cleanups = CopyOnWriteArrayList<() -> Unit>()

    internal fun start() = thread.start()

    fun kill() {
        killed = true
        thread.interrupt()
    }

    /** Called from API entry points so tight loops that use the API can still be killed. */
    internal fun checkAlive() {
        if (killed) throw JobKilledException()
    }

    /** A physical key a script watches via `onKey`; polled on the client thread, see [pollKeys]. */
    class KeyWatch(val keyCode: Int, val handler: () -> Unit) {
        internal var wasDown = false
    }

    internal val keyWatches = CopyOnWriteArrayList<KeyWatch>()

    /**
     * At most one Tick is queued at a time: a script slower than the game skips ticks instead of
     * building a backlog that would delay its other events (commands, keys, chat).
     */
    private val tickQueued = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Client thread, once per tick: turn watched keys going down into [Event.Key]s. */
    internal fun pollKeys(isDown: (Int) -> Boolean) {
        for (watch in keyWatches) {
            val down = isDown(watch.keyCode)
            if (down && !watch.wasDown) post(Event.Key(watch))
            watch.wasDown = down
        }
    }

    internal fun post(event: Event) {
        if (state == State.LISTENING || state == State.RUNNING) {
            val wanted = when (event) {
                Event.Tick -> tickHandlers.isNotEmpty() && tickQueued.compareAndSet(false, true)
                is Event.Key -> true
                is Event.Chat -> chatHandlers.isNotEmpty()
                is Event.Command -> commandHandlers.containsKey(event.name)
                is Event.Binding -> bindingHandlers[event.slot]?.isNotEmpty() == true
            }
            if (wanted) events.offer(event)
        }
    }

    private fun run() {
        try {
            val compiled = when (val result = ScriptCompiler.compile(source)) {
                is ResultWithDiagnostics.Success -> result.value
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
                        if (name == EVAL_NAME) host.echo("= ${value.value}", MessageKind.INFO)
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

    /** After the script body returns, keep dispatching events while it has handlers registered. */
    private fun listen() {
        if (!hasHandlers()) return
        state = State.LISTENING
        try {
            while (!killed) {
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
                }
            }
        } catch (t: Throwable) {
            handleThrowable(t)
        }
    }

    private fun hasHandlers() =
        tickHandlers.isNotEmpty() || chatHandlers.isNotEmpty() || commandHandlers.isNotEmpty() || keyWatches.isNotEmpty() ||
            bindingHandlers.values.any { it.isNotEmpty() }

    private fun handleThrowable(t: Throwable) {
        val root = generateSequence(t) { it.cause }.last()
        when {
            killed || root is JobKilledException || root is InterruptedException -> Unit
            t is JobStopException || root is JobStopException -> Unit
            else -> {
                val frame = root.stackTrace.firstOrNull { it.className.startsWith("Script") || it.fileName?.endsWith(".kts") == true }
                val where = frame?.let { " (line ${it.lineNumber})" }.orEmpty()
                host.echo("[$name] ${root.javaClass.simpleName}: ${root.message}$where", MessageKind.ERROR)
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
        cleanups.forEach { runCatching(it) }
        commandHandlers.keys.forEach { name -> runCatching { host.unregisterCommand(name, this) } }
        if (heldKeys.isNotEmpty()) {
            runCatching { host.onGameThread { heldKeys.forEach { key -> key.setDown(false) } } }
            heldKeys.clear()
        }
    }

    companion object {
        const val EVAL_NAME = "eval"
        private val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
    }
}
