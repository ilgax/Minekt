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
import dev.ilgax.minekt.server.MksPermissions
import dev.ilgax.minekt.server.ServerScripts
import dev.ilgax.minekt.share.ScriptInstaller
import dev.ilgax.minekt.job.ScriptSettings
import dev.ilgax.minekt.api.EntityInfo
import net.minecraft.world.phys.Vec3
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
import kotlin.test.assertFalse
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
            onChat { (player, message) -> if (message == "!spawn") runCommand("tp " + player + " 0.5 80 0.5") }
            onPlayerJoin { name -> tell(name, chatText(greeting, Color.GREEN)); inventory(name).give("minecraft:bread", 3) }
            onPlayerLeave { broadcast(it, "left") }
            onBlockBreak { (player, pos, block, dimension) -> if (block == "minecraft:diamond_ore") echo(player, pos, dimension) }
            command("heal", CommandPermission.OPS) { call -> call.reply("healed", call.sender, call.args) }
            val online: Int = server { it.playerList.playerCount }
            setBlock(BlockPos(0, 64, 0), "minecraft:oak_log[axis=x]")
            echo(players.map { it.name }, player("Steve")?.isOp, blockAt(BlockPos(0, 64, 0), "the_nether").id, runCommand("time query daytime"))
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
    fun `server scripts compile against entities, inventories, the world and the power tools`() {
        val code = """
            @file:OptIn(ExperimentalPackets::class, ExperimentalHooks::class)
            import net.minecraft.network.protocol.game.ServerboundChatPacket
            import net.minecraft.network.protocol.game.ClientboundSoundPacket
            import net.minecraft.server.level.ServerPlayer
            import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents

            val zombies: List<EntityInfo> = entities(type = "zombie", near = Vec3(0.0, 64.0, 0.0), radius = 32.0, dimension = "overworld")
            val cow = spawn("minecraft:cow", Vec3(0.5, 80.0, 0.5))
            entity(cow.id)?.dimension; entity(cow.uuid)
            killEntity(cow.id); removeEntity(zombies.first().id)
            onEntitySpawn { if (it.type == "minecraft:phantom") removeEntity(it.id) }
            onEntityRemoved { (entity, died, reason) -> echo(entity.name, died, reason) }
            onDamage { (entity, amount, health, cause, attacker, projectile) -> echo(entity.type, amount, health, cause, attacker?.name, projectile?.type) }
            onDeath { (entity, cause, killer) -> echo(entity.name, cause, killer?.name) }
            onEvent { if (it is ServerEvent.Damage) echo(it.amount) }

            val inv = inventory("Steve")
            inv.setSlot(0, "minecraft:diamond_sword", name = "Excalibur", lore = listOf("Sharp"), enchantments = mapOf("sharpness" to 5), damage = 10)
            inv.clearSlot(1); val taken: Int = inv.take("minecraft:emerald", 3); val fit: Boolean = inv.give("diamond")
            echo(inv.items.map { it.enchantments }, inv[0]?.durability, inv.size, inv.count("minecraft:diamond"))
            enderChest("Steve").give("minecraft:apple"); container(BlockPos(1, 64, 1), "overworld")?.items

            val w: WorldInfo = world("the_nether"); echo(w.timeOfDay, w.isRaining, w.isThundering)
            val info: BlockInfo = blockAt(BlockPos(0, 64, 0)); blockAt(BlockPos(0, 64, 0), "the_end").signFront
            findBlocks(BlockPos(0, 64, 0), 16) { it == "minecraft:chest" }.forEach { echo(it.pos) }
            when (val hit = raycast("Steve", 50.0)) { is RayHit.Block -> echo(hit.id, hit.face); is RayHit.Entity -> echo(hit.entity.name); null -> Unit }
            raycast(Vec3(0.0, 80.0, 0.0), Vec3(0.0, 0.0, 0.0), dimension = "overworld", entities = false)
            atDaytime(13000) { broadcast("Night") }

            hook<ServerPlayer>("tick") { }
            hookAfter("net.minecraft.server.level.ServerPlayer", "tick") { it.result }
            rewriteClass<ServerPlayer> { }; classBytes<ServerPlayer>()
            onPacket<ServerboundChatPacket> { packet, player -> echo(player, packet.message()) }
            interceptPacketSent<ClientboundSoundPacket> { packet, player -> if (player == "Steve") null else packet }
            ServerTickEvents.END_SERVER_TICK.register { }
        """.trimIndent()
        val result = ServerScriptCompiler.compile(code.toScriptSource("server-api.kts"))
        assertTrue(result is ResultWithDiagnostics.Success, errors(result))
    }

    @Test
    fun `server packets and hooks need the same opt-ins as on the client`() {
        for (code in listOf(
            "import net.minecraft.network.protocol.game.ServerboundChatPacket\nonPacket<ServerboundChatPacket> { _, _ -> }",
            "import net.minecraft.server.level.ServerPlayer\nhook<ServerPlayer>(\"tick\") { }",
        )) {
            val result = ServerScriptCompiler.compile(code.toScriptSource("no-opt-in.kts"))
            assertTrue(result is ResultWithDiagnostics.Failure, "compiled without opting in: $code")
        }
    }

    @Test
    fun `entity, damage and death events reach server scripts that listen for them`() {
        val zombie = EntityInfo(7, "u", "Zombie", "minecraft:zombie", Vec3(1.0, 2.0, 3.0), 20f, 0.0, dimension = "minecraft:overworld")
        val steve = EntityInfo(8, "s", "Steve", "minecraft:player", Vec3.ZERO, 20f, 0.0)
        assertFalse(jobs.wantsServer(ServerEvent.Damage::class.java))
        val job = jobs.start("entities", emptyList(), """
            onEntitySpawn { echo("spawn", it.name, it.dimension) }
            onEntityRemoved { (entity, died, reason) -> echo("removed", entity.name, died, reason) }
            onDamage { (entity, amount, health, cause, attacker) -> echo("damage", entity.name, amount, health, cause, attacker?.name) }
            onDeath { (entity, cause, killer) -> if (entity.type == "minecraft:player") echo("player death", entity.name, cause, killer?.name) }
            echo("ready")
        """.trimIndent().toScriptSource("entities.kts"))
        assertEquals("ready", host.next())
        assertTrue(jobs.wantsServer(ServerEvent.Damage::class.java))
        assertFalse(jobs.wantsServer(ServerEvent.BlockBreak::class.java))
        jobs.dispatch(Job.Event.Server(ServerEvent.EntitySpawn(zombie)))
        assertEquals("spawn Zombie minecraft:overworld", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.EntityRemoved(zombie, true, "killed")))
        assertEquals("removed Zombie true killed", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.Damage(zombie, 4f, 16f, "minecraft:player_attack", steve)))
        assertEquals("damage Zombie 4.0 16.0 minecraft:player_attack Steve", host.next())
        jobs.dispatch(Job.Event.Server(ServerEvent.Death(zombie, "minecraft:player_attack", steve))) // not a player
        jobs.dispatch(Job.Event.Server(ServerEvent.Death(steve, "minecraft:fall")))
        assertEquals("player death Steve minecraft:fall null", host.next())
        job.kill()
    }

    @Test
    fun `setting values typed in commands`() {
        assertEquals(true, ScriptSettings.Kind.Toggle.parse("on"))
        assertEquals(false, ScriptSettings.Kind.Toggle.parse("False"))
        assertEquals(null, ScriptSettings.Kind.Toggle.parse("maybe"))
        assertEquals(10, ScriptSettings.Kind.Whole(1, 10).parse("42"))
        assertEquals(null, ScriptSettings.Kind.Whole(1, 10).parse("ten"))
        assertEquals(2.5, ScriptSettings.Kind.Decimal(0.0, 5.0, 0.5).parse("2.5"))
        assertEquals("Fast", ScriptSettings.Kind.Choice(listOf("Slow", "Fast")).parse("fast"))
        assertEquals(null, ScriptSettings.Kind.Choice(listOf("Slow", "Fast")).parse("medium"))
        assertEquals("hel", ScriptSettings.Kind.Text(3).parse("hello"))
    }

    @Test
    fun `permission node names`() {
        assertEquals("minekt:command.run", MksPermissions.Node.RUN.id.toString())
        assertEquals("minekt:notify", MksPermissions.Node.NOTIFY.id.toString())
        assertEquals("minekt:script.run.my_backup-2", MksPermissions.scriptRunNode("My Backup-2").toString())
        assertEquals("minekt:script.command.heal", MksPermissions.commandNode("heal").toString())
    }

    @Test
    fun `the install preview lists what a server script can do`() {
        val caps = ScriptInstaller.capabilities(
            """
            runCommand("op Steve") // gives away op
            setBlock(0, 64, 0, "minecraft:tnt")
            inventory("Steve").give("minecraft:diamond", 64)
            command("heal") { }
            """.trimIndent(),
            server = true,
        )
        assertEquals(
            listOf("run any command as the server console", "change the world: blocks and entities", "change items in players' inventories and containers", "add commands for players"),
            caps,
        )
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
