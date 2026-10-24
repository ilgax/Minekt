package dev.ilgax.minekt

import dev.ilgax.minekt.api.ScriptContext
import dev.ilgax.minekt.api.server.ServerEvent
import dev.ilgax.minekt.api.server.ServerMinekt
import dev.ilgax.minekt.job.Host
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobManager
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.TickClock
import dev.ilgax.minekt.script.ScriptCompiler
import dev.ilgax.minekt.script.ServerScriptCompiler
import dev.ilgax.minekt.script.TemplateCompiler
import dev.ilgax.minekt.server.ServerScripts
import net.minecraft.core.BlockPos
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Server scripts: what compiles against [ServerMinekt], and the side-neutral engine without a client. */
class ServerScriptsTest {
    /** A server without a server: enough for scripts that only use what both sides share, and server events. */
    private class FakeServerHost : Host {
        override val scriptDir: Path = Files.createTempDirectory("minekt-server-test")
        override val tickClock = TickClock()
        override val compiler: TemplateCompiler get() = ServerScriptCompiler
        override val commandPrefix get() = "/mks "
        override fun receiver(job: Job): ScriptContext = ServerMinekt(job)
        val messages = LinkedBlockingQueue<String>()
        override fun echo(message: String, kind: MessageKind) { messages.put(message) }
        override fun registerCommand(name: String, completions: List<String>, owner: Job): String? = null
        override fun unregisterCommand(name: String, owner: Job) = Unit
        fun next(): String = messages.poll(60, TimeUnit.SECONDS) ?: fail("timed out waiting for script output")
    }

    private val host = FakeServerHost()
    private val jobs = JobManager(host)

    private fun errors(result: ResultWithDiagnostics<*>) =
        result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.joinToString("\n") { "${it.location?.start?.line}: ${it.message}" }

    @Test
    fun `server scripts compile against the shared helpers and the server API`() {
        val code = """
            var count by persistent { 0 }
            val greeting by setting("Welcome")
            every(5.seconds) { publish("count", count++) }
            subscribe<Int>("count") { writeLog("count", it) }
            launch { awaitTicks(20); waitTicks(1) }
            onTick { }
            onChat { (player, message) -> if (message == "!spawn") teleport(player, Vec3(0.5, 80.0, 0.5), "overworld") }
            onPlayerJoin { name -> tell(name, chatText(greeting, Color.GREEN)); give(name, "minecraft:bread", 3) }
            onPlayerLeave { broadcast(it, "left") }
            onBlockBreak { (player, pos, block, dimension) -> if (block == "minecraft:diamond_ore") echo(player, pos, dimension) }
            command("heal", CommandPermission.OPS) { call -> call.reply("healed", call.sender, call.args) }
            val online: Int = server { it.playerList.playerCount }
            setBlock(BlockPos(0, 64, 0), "minecraft:oak_log[axis=x]")
            echo(players.map { it.name }, player("Steve")?.isOp, blockId(0, 64, 0, "the_nether"), runCommand("time query daytime"))
            val n = eval("1 + 1") as Int
            onStop { save() }
        """.trimIndent()
        val result = ServerScriptCompiler.compile(code.toScriptSource("server.kts"))
        assertTrue(result is ResultWithDiagnostics.Success, errors(result))
    }

    @Test
    fun `client-only calls don't compile in server scripts, nor server calls in client scripts`() {
        for (code in listOf("""toast("hi")""", "echo(player.position)", "game { it.player }", "onHudRender { }")) {
            val result = ServerScriptCompiler.compile(code.toScriptSource("client-call.kts"))
            assertTrue(result is ResultWithDiagnostics.Failure, "server script compiled: $code")
        }
        val result = ScriptCompiler.compile("""broadcast("hi")""".toScriptSource("server-call.kts"))
        assertTrue(result is ResultWithDiagnostics.Failure, "client script compiled broadcast()")
    }

    @Test
    fun `server scripts run without a client and get server events`() {
        val job = jobs.start("events", emptyList(), """
            onChat { (player, message) -> echo("chat", player, message) }
            onPlayerJoin { echo("join", it) }
            onBlockBreak { (player, pos, block) -> echo("break", player, pos, block) }
            echo("ready")
        """.trimIndent().toScriptSource("events.kts"))
        assertEquals("ready", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.Chat("Alex", "hello")))
        assertEquals("chat Alex hello", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.PlayerJoin("Steve")))
        assertEquals("join Steve", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.BlockBreak("Steve", BlockPos(1, 2, 3), "minecraft:stone", "minecraft:overworld")))
        assertEquals("break Steve (1, 2, 3) minecraft:stone", host.next())
        job.kill()
    }

    @Test
    fun `waitTicks counts the host's own ticks`() {
        jobs.start("ticks", emptyList(), """
            echo("waiting")
            waitTicks(3)
            echo("waited")
        """.trimIndent().toScriptSource("ticks.kts"))
        assertEquals("waiting", host.next())
        val deadline = System.currentTimeMillis() + 60_000
        while (host.messages.isEmpty() && System.currentTimeMillis() < deadline) {
            host.tickClock.advance()
            Thread.sleep(20)
        }
        assertEquals("waited", host.next())
        assertTrue(host.tickClock.ticks >= 3)
    }

    @Test
    fun `server autorun lines`() {
        val text = """
            # Starts with the server
            greeter
            \backup 30m
            /mks run announcer "hello there"   # a comment
            mks run spawnguard

        """.trimIndent()
        assertEquals(listOf("greeter", "backup 30m", "announcer \"hello there\"", "spawnguard"), ServerScripts.autorunLines(text))
    }
}
