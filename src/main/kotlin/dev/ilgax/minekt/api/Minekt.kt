package dev.ilgax.minekt.api

import com.mojang.blaze3d.platform.InputConstants
import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.JobKilledException
import dev.ilgax.minekt.job.JobStopException
import dev.ilgax.minekt.job.TickClock
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import kotlin.math.atan2
import kotlin.math.sqrt
import net.minecraft.core.BlockPos as McBlockPos

/**
 * The implicit receiver of every script: everything here is callable unqualified from a `.kts`.
 *
 * Calls run on the script's own thread; anything touching the game hops to the client thread via
 * [game]. Every call checks whether the job was killed, so loops that use the API stay killable.
 */
class Minekt internal constructor(private val job: Job) {
    /** Arguments passed after the script name: `\hello a b` → `["a", "b"]`. */
    val args: List<String> get() = job.args

    val jobId: Int get() = job.id

    /** Helpers for Baritone, if it is installed. See [Baritone]. */
    val baritone: Baritone by lazy { Baritone(this) }

    // ---- output -------------------------------------------------------------------------------

    /** Print to the local chat (only you see it). */
    fun echo(vararg parts: Any?) {
        job.checkAlive()
        job.host.echo(parts.joinToString(" "))
    }

    /** Send a chat message to the server. Messages starting with `/` are sent as commands. */
    fun chat(message: String) = game { mc ->
        val connection = mc.connection ?: error("Not connected to a world")
        if (message.startsWith("/")) connection.sendCommand(message.substring(1)) else connection.sendChat(message)
    }

    /** Run a command (leading `/` optional). */
    fun execute(command: String) = chat(if (command.startsWith("/")) command else "/$command")

    /** Show [lines] in the top-left HUD until changed or the script ends. `hud()` clears it. */
    fun hud(vararg lines: String) {
        job.checkAlive()
        ScriptHud.set(job.id, lines.toList())
        if (lines.isNotEmpty()) job.cleanups.addIfAbsent(hudCleanup)
    }

    private val hudCleanup: () -> Unit = { ScriptHud.set(job.id, emptyList()) }

    // ---- timing / control ---------------------------------------------------------------------

    fun sleep(ms: Long) {
        job.checkAlive()
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            throw JobKilledException()
        }
    }

    /** Wait for [ticks] client ticks (20 per second). */
    fun waitTicks(ticks: Int = 1) {
        job.checkAlive()
        try {
            TickClock.await(ticks)
        } catch (_: InterruptedException) {
            throw JobKilledException()
        }
    }

    /** End the script now. Works from inside event handlers too. */
    fun stop(): Nothing = throw JobStopException()

    // ---- raw access ---------------------------------------------------------------------------

    /**
     * Run [block] on the client thread with the live [Minecraft] instance and return its result.
     * This is the escape hatch: anything the game or any installed mod exposes is reachable here.
     */
    fun <T> game(block: (Minecraft) -> T): T {
        job.checkAlive()
        return job.host.onGameThread(block)
    }

    fun isModLoaded(id: String): Boolean = FabricLoader.getInstance().isModLoaded(id)

    fun modVersion(id: String): String? =
        FabricLoader.getInstance().getModContainer(id).map { it.metadata.version.friendlyString }.orElse(null)

    // ---- player -------------------------------------------------------------------------------

    val player: PlayerInfo
        get() = game { mc ->
            val p = mc.player ?: error("Not in a world")
            PlayerInfo(
                name = p.name.string,
                uuid = p.uuid.toString(),
                position = Vec3(p.x, p.y, p.z),
                yaw = p.yRot,
                pitch = p.xRot,
                health = p.health,
                maxHealth = p.maxHealth,
                dimension = mc.level!!.dimension().identifier().toString(),
                selectedSlot = p.inventory.selectedSlot,
            )
        }

    val position: Vec3 get() = player.position

    fun setOrientation(yaw: Float, pitch: Float) = game { mc ->
        val p = mc.player ?: error("Not in a world")
        p.yRot = yaw
        p.xRot = pitch.coerceIn(-90f, 90f)
        p.setYHeadRot(yaw)
    }

    /** Turn the player's view towards a point. */
    fun lookAt(x: Double, y: Double, z: Double) = game { mc ->
        val p = mc.player ?: error("Not in a world")
        val dx = x - p.x
        val dy = y - p.eyeY
        val dz = z - p.z
        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val pitch = (-Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))).toFloat()
        p.yRot = yaw
        p.xRot = pitch
        p.setYHeadRot(yaw)
    }

    fun lookAt(pos: Vec3) = lookAt(pos.x, pos.y, pos.z)

    /** Hold or release a key. Held keys are released automatically when the script ends. */
    fun press(key: Key, down: Boolean = true) = game { mc ->
        val mapping = mc.options.mapping(key)
        mapping.setDown(down)
        if (down) job.heldKeys += mapping else job.heldKeys -= mapping
    }

    fun release(key: Key) = press(key, false)

    /** Hold [key] for [ms] milliseconds. */
    fun hold(key: Key, ms: Long) {
        press(key, true)
        try {
            sleep(ms)
        } finally {
            runCatching { job.host.onGameThread { mc -> mc.options.mapping(key).setDown(false) } }
        }
    }

    fun selectSlot(slot: Int) = game { mc ->
        require(slot in 0..8) { "Hotbar slot must be 0..8" }
        mc.player?.inventory?.selectedSlot = slot
    }

    /** All non-empty inventory slots. */
    fun inventory(): List<ItemStackInfo> = game { mc ->
        val inv = mc.player?.inventory ?: error("Not in a world")
        (0 until inv.containerSize).mapNotNull { slot ->
            val stack = inv.getItem(slot)
            if (stack.isEmpty) null
            else ItemStackInfo(slot, BuiltInRegistries.ITEM.getKey(stack.item).toString(), stack.count)
        }
    }

    // ---- world --------------------------------------------------------------------------------

    /** Block id at a position, e.g. `minecraft:stone`. Unloaded chunks read as `minecraft:air`. */
    fun getBlock(x: Int, y: Int, z: Int): String = game { mc ->
        val level = mc.level ?: error("Not in a world")
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(McBlockPos(x, y, z)).block).toString()
    }

    fun getBlock(pos: BlockPos) = getBlock(pos.x, pos.y, pos.z)

    /** Every block within [radius] of the player whose id matches [predicate]. One game-thread hop. */
    fun findBlocks(radius: Int, predicate: (String) -> Boolean): List<Block> = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val origin = mc.player!!.blockPosition()
        val found = mutableListOf<Block>()
        val cursor = McBlockPos.MutableBlockPos()
        for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
            cursor.set(origin.x + dx, origin.y + dy, origin.z + dz)
            val id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(cursor).block).toString()
            if (predicate(id)) found += Block(BlockPos(cursor.x, cursor.y, cursor.z), id)
        }
        found.sortedBy { (it.pos.x - origin.x).sq() + (it.pos.y - origin.y).sq() + (it.pos.z - origin.z).sq() }
    }

    /** Entities loaded on the client, nearest first, optionally limited to [radius] blocks. */
    fun entities(radius: Double = Double.MAX_VALUE): List<EntityInfo> = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val self = mc.player
        level.entitiesForRendering()
            .filter { it !== self }
            .map { it.toInfo(self) }
            .filter { it.distance <= radius }
            .sortedBy { it.distance }
    }

    fun entities(radius: Int): List<EntityInfo> = entities(radius.toDouble())

    fun players(): List<EntityInfo> = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val self = mc.player
        level.players().filter { it !== self }.map { it.toInfo(self) }.sortedBy { it.distance }
    }

    // ---- events -------------------------------------------------------------------------------

    /**
     * Call [handler] with the text of every chat/system message received. Handlers run on the
     * script thread after the script body finishes; the script stays alive until `stop()`/`\kill`.
     */
    fun onChat(handler: (String) -> Unit) {
        job.chatHandlers += handler
    }

    /** Call [handler] every client tick (see [onChat] for lifecycle). */
    fun onTick(handler: () -> Unit) {
        job.tickHandlers += handler
    }

    /**
     * Call [handler] when a physical key is pressed, e.g. `onKey("k") { ... }`. Names are GLFW
     * names without the `key.keyboard.` prefix: `"k"`, `"f6"`, `"left.shift"`. Polled each tick.
     * The key is fixed by the script; use [onBinding] to let the player choose it in Controls.
     */
    fun onKey(name: String, handler: () -> Unit) {
        val key = InputConstants.getKey(if ('.' in name && name.startsWith("key.")) name else "key.keyboard.$name")
        var wasDown = false
        onTick {
            val down = game { mc -> mc.gui.screen() == null && InputConstants.isKeyDown(key.value) }
            if (down && !wasDown) handler()
            wasDown = down
        }
    }

    /**
     * Call [handler] when the player presses script keybind [slot] (1–5). Players choose the key
     * in Options → Controls → Minekt. Prefer this over [onKey] for scripts you share: the player
     * picks the key, it shows up in Controls, and it never clashes with other bindings.
     */
    fun onBinding(slot: Int, handler: () -> Unit) {
        require(slot in 1..SCRIPT_KEY_SLOTS) { "Keybind slot must be 1..$SCRIPT_KEY_SLOTS" }
        job.bindingHandlers.computeIfAbsent(slot) { java.util.concurrent.CopyOnWriteArrayList() } += handler
    }

    /** The key currently bound to script keybind [slot], e.g. `"K"` or `"Not Bound"`. */
    fun bindingKey(slot: Int): String {
        job.checkAlive()
        return job.host.bindingKeyName(slot)
    }

    /**
     * Register `/name` as a client-side command while this script runs. [handler] gets the words
     * typed after the name (`"quoted words"` are grouped). [completions] are offered on Tab.
     * Script commands take priority over a server command of the same name until the script ends.
     */
    fun command(name: String, completions: List<String> = emptyList(), handler: (args: List<String>) -> Unit) {
        job.checkAlive()
        job.commandHandlers[name] = handler
        job.host.registerCommand(name, completions, job)?.let { error ->
            job.commandHandlers.remove(name)
            throw IllegalStateException(error)
        }
    }

    private fun Entity.toInfo(self: Entity?) = EntityInfo(
        id = id,
        uuid = uuid.toString(),
        name = name.string,
        type = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(),
        position = Vec3(x, y, z),
        health = (this as? LivingEntity)?.health,
        distance = self?.distanceTo(this)?.toDouble() ?: 0.0,
    )

    private fun Int.sq() = this * this

    private fun net.minecraft.client.Options.mapping(key: Key): KeyMapping = when (key) {
        Key.FORWARD -> keyUp
        Key.BACK -> keyDown
        Key.LEFT -> keyLeft
        Key.RIGHT -> keyRight
        Key.JUMP -> keyJump
        Key.SNEAK -> keyShift
        Key.SPRINT -> keySprint
        Key.ATTACK -> keyAttack
        Key.USE -> keyUse
        Key.DROP -> keyDrop
        Key.SWAP_OFFHAND -> keySwapOffhand
    }
}

/** Number of "Script key N" slots in the Controls menu. */
const val SCRIPT_KEY_SLOTS = 5
