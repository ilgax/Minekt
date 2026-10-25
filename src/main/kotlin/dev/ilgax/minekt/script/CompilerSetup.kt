package dev.ilgax.minekt.script

import net.fabricmc.loader.impl.launch.FabricLauncherBase
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException

/**
 * Makes sure the Kotlin compiler is loadable before any script compiles.
 *
 * The full build bundles it, so there is nothing to do. The small build looks for (or downloads)
 * the pinned jar with [PinnedJarProvider] and adds it to Fabric's mod class loader at runtime, so
 * from then on everything works exactly as in the full build. Runs once, in the background.
 */
object CompilerSetup {
    /** A class only the compiler jar has. */
    private const val PROBE = "org.jetbrains.kotlin.config.CompilerConfiguration"

    private val ready = CompletableFuture<Unit>()
    @Volatile private var started = false
    private val launched = java.util.concurrent.atomic.AtomicBoolean()

    /** What's happening, for messages: null when the compiler is ready. */
    @Volatile var status: String? = "Preparing the Kotlin compiler"
        private set

    fun isPresent(): Boolean =
        runCatching { Class.forName(PROBE, false, CompilerSetup::class.java.classLoader) }.isSuccess

    /**
     * [run] on a background thread, adding the compiler to Fabric's class loader, then compile a trivial
     * script with [warmUp] so the first real one starts quickly. Only the first call does anything: on
     * the client, the integrated server shares the compiler with it.
     */
    fun startInBackground(libDir: Path, notify: (String, Boolean) -> Unit, warmUp: TemplateCompiler) {
        if (!launched.compareAndSet(false, true)) return
        started = true
        Thread({
            run(libDir, addToClassPath = { jar -> FabricLauncherBase.getLauncher().addToClassPath(jar) }, notify = notify)
            if (status == null) warmUp(warmUp, notify)
        }, "minekt-compiler").apply {
            isDaemon = true
            contextClassLoader = CompilerSetup::class.java.classLoader
        }.start()
    }

    /** Compile [compiler]'s warm-up script, saying whether the compiler is ready or what went wrong. */
    internal fun warmUp(compiler: TemplateCompiler, notify: (String, Boolean) -> Unit): Boolean {
        val begun = System.nanoTime()
        return runCatching { compiler.warmUp() }
            .onSuccess { LOGGER.info("Kotlin script compiler ready in {} ms", (System.nanoTime() - begun) / 1_000_000) }
            .onFailure {
                LOGGER.error("Kotlin script compiler warm-up failed", it)
                notify("The Kotlin compiler doesn't work: ${it.message ?: it.javaClass.simpleName}. See the game log.", true)
            }
            .isSuccess
    }

    /**
     * Start (in the calling thread) making the compiler available. [notify] gets short messages
     * worth showing the player: when a download starts, finishes or fails.
     */
    fun run(libDir: Path, addToClassPath: (Path) -> Unit, notify: (String, Boolean) -> Unit) {
        started = true
        try {
            if (!isPresent()) {
                val pin = PinnedJarProvider.Pin.fromResource("minekt-compiler.txt").singleOrNull() ?: error("This Minekt build has no compiler and no download information")
                val provider = PinnedJarProvider(pin, libDir)
                val jar = provider.locate() ?: run {
                    notify("Downloading the Kotlin compiler (${pin.size / 1_000_000} MB, first launch only)", false)
                    var lastShown = -1L
                    provider.obtain { got ->
                        val percent = got * 100 / pin.size
                        status = "Downloading the Kotlin compiler: $percent%"
                        if (percent / 25 != lastShown) {
                            lastShown = percent / 25
                            LOGGER.info("Kotlin compiler download: {}%", percent)
                        }
                    }.also { notify("Kotlin compiler downloaded: scripts are ready", false) }
                }
                LOGGER.info("Using the Kotlin compiler at {}", jar)
                addToClassPath(jar)
                check(isPresent()) { "The Kotlin compiler at $jar couldn't be loaded" }
            }
            status = null
            ready.complete(Unit)
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            status = message
            LOGGER.error("Kotlin compiler unavailable", e)
            notify(message, true)
            ready.completeExceptionally(IllegalStateException(message, e))
        }
    }

    /**
     * Block until the compiler is usable; [whileWaiting] is called once if that means waiting.
     * Throws [IllegalStateException] with a readable message if it can't be had.
     */
    fun awaitReady(whileWaiting: (String) -> Unit = {}) {
        if (!started) {
            // Not set up through the mod (tests, tools): the compiler is on the classpath or not.
            check(isPresent()) { "The Kotlin compiler isn't available" }
            return
        }
        if (!ready.isDone) status?.let(whileWaiting)
        try {
            ready.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private val LOGGER = org.slf4j.LoggerFactory.getLogger("minekt")
}
