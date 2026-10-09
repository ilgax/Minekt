package dev.ilgax.minekt

import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.MessageKind
import net.minecraft.client.Minecraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.fail

/** Runs `game { }` blocks on a separate "client" thread, like the real game. */
internal class TestHost : Host {
    override val scriptDir: Path = Files.createTempDirectory("minekt-test")
    val messages = LinkedBlockingQueue<String>()
    val clientThread = Executors.newSingleThreadExecutor { Thread(it, "fake-client").apply { isDaemon = true } }
    override fun echo(message: String, kind: MessageKind) { messages.put(message) }

    /** A blank instance, never initialised: the scripts under test don't touch it. */
    private val mc: Minecraft by lazy {
        val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        unsafe.allocateInstance(Minecraft::class.java) as Minecraft
    }

    override fun <T> onGameThread(block: (Minecraft) -> T): T =
        try {
            clientThread.submit<T> { block(mc) }.get()
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }

    override fun registerCommand(name: String, completions: List<String>, owner: Job): String? = null
    override fun unregisterCommand(name: String, owner: Job) = Unit
    override fun bindingKeyName(slot: Int) = "Key$slot"

    fun next(timeoutSeconds: Long = 60): String =
        messages.poll(timeoutSeconds, TimeUnit.SECONDS) ?: fail("timed out waiting for script output")
}

