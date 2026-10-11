package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.ScriptLogs
import dev.ilgax.minekt.job.JobKilledException
import dev.ilgax.minekt.job.JobStopException
import dev.ilgax.minekt.job.TickClock
import dev.ilgax.minekt.mixin.KeyMappingAccessor
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
class Minekt internal constructor(internal val job: Job) {
    /** Arguments passed after the script name: `\hello a b` → `["a", "b"]`. */
    val args: List<String> get() = job.args

    val jobId: Int get() = job.id

    /** Helpers for Baritone, if it is installed. See [Baritone]. */
    val baritone: Baritone by lazy { Baritone(this) }

    // ---- output -------------------------------------------------------------------------------

    /** Print to the local chat (only you see it). */
    fun echo(vararg parts: Any?) {
        job.checkAlive()
        if (parts.none { it is ChatText }) return job.output(parts.joinToString(" "))
        // Styled: plain parts become unstyled text, joined with spaces like the rest.
        val text = net.minecraft.network.chat.Component.empty()
        parts.forEachIndexed { i, part ->
            if (i > 0) text.append(" ")
            if (part is ChatText) text.append(part.toComponent(job)) else text.append(part.toString())
        }
        job.output(text, parts.joinToString(" "))
    }

    /**
     * Write to this script's log only, not chat: `minekt/logs/<script>.log`, also shown by
     * `\log <script>` and the Log button in the script manager.
     */
    fun writeLog(vararg parts: Any?) {
        job.checkAlive()
        job.logs.add(job.name, ScriptLogs.Level.LOG, parts.joinToString(" "), run = job.id)
    }

    /** Send a chat message to the server. Messages starting with `/` are sent as commands. */
    fun chat(message: String) = game { mc ->
        val connection = mc.connection ?: error("Not connected to a world")
        if (message.startsWith("/")) connection.sendCommand(message.substring(1)) else connection.sendChat(message)
    }

    /** Run a command (leading `/` optional). */
    fun execute(command: String) = chat(if (command.startsWith("/")) command else "/$command")

    /** The text on the system clipboard ("" if there's none). */
    fun clipboard(): String = game { mc -> mc.keyboardHandler.clipboard }

    /** Put [text] on the system clipboard. */
    fun setClipboard(text: String) = game { mc -> mc.keyboardHandler.clipboard = text }

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
                absorption = p.absorptionAmount,
                food = p.foodData.foodLevel,
                saturation = p.foodData.saturationLevel,
                xpLevel = p.experienceLevel,
                xpProgress = p.experienceProgress,
                air = p.airSupply,
                maxAir = p.maxAirSupply,
                armor = p.armorValue,
                effects = p.activeEffects.map { EffectInfo(it.effect.registeredName, it.amplifier, it.duration) },
                velocity = p.deltaMovement.let { Vec3(it.x, it.y, it.z) },
                onGround = p.onGround(),
                inWater = p.isInWater,
                inLava = p.isInLava,
                onFire = p.isOnFire,
                sneaking = p.isShiftKeyDown,
                sprinting = p.isSprinting,
                flying = p.abilities.flying,
                gliding = p.isFallFlying,
                gameMode = mc.gameMode?.playerMode?.serializedName ?: "survival",
                vehicle = p.vehicle?.let { BuiltInRegistries.ENTITY_TYPE.getKey(it.type).toString() },
                eyePosition = p.eyePosition.let { Vec3(it.x, it.y, it.z) },
                lookDirection = p.lookAngle.let { Vec3(it.x, it.y, it.z) },
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

    /**
     * Hold ([down]) or let go of one of the game's bindings, whatever key it's bound to. Going down
     * also counts as a click, like a real key press (so `"inventory"` opens the inventory and
     * `"attack"` swings). Held bindings are released when the script ends.
     */
    fun press(key: Key, down: Boolean = true) = game { mc -> press(mc.options.mapping(key), down) }

    /**
     * [press] by the binding's name, as in `options.txt` with or without `key.`: `"forward"`, `"inventory"`,
     * `"hotbar.3"`, `"togglePerspective"`, `"key.pickItem"`, or a binding another mod adds. [bindings] lists them.
     */
    fun press(binding: String, down: Boolean = true) = game { mc ->
        val mapping = KeyMapping.get(binding) ?: KeyMapping.get("key.$binding")
            ?: throw IllegalArgumentException("No binding named '$binding' (bindings() lists them)")
        press(mapping, down)
    }

    fun release(key: Key) = press(key, false)

    fun release(binding: String) = press(binding, false)

    /** The names of all the game's bindings, for [press]: `key.forward`, `key.inventory`, … */
    fun bindings(): List<String> = game { mc -> mc.options.keyMappings.map { it.name } }

    private fun press(mapping: KeyMapping, down: Boolean) {
        if (down && !mapping.isDown) (mapping as KeyMappingAccessor).run { `minekt$setClickCount`(`minekt$getClickCount`() + 1) }
        mapping.setDown(down)
        if (down) job.heldKeys += mapping else job.heldKeys -= mapping
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
            else stack.toInfo(slot)
        }
    }

    // ---- world --------------------------------------------------------------------------------

    /** Block id at a position, e.g. `minecraft:stone`. Unloaded chunks read as `minecraft:air`. */
    fun getBlock(x: Int, y: Int, z: Int): String = game { mc ->
        val level = mc.level ?: error("Not in a world")
        BuiltInRegistries.BLOCK.getKey(level.getBlockState(McBlockPos(x, y, z)).block).toString()
    }

    fun getBlock(pos: BlockPos) = getBlock(pos.x, pos.y, pos.z)

    /** Everything about the block at [pos]: its state, light, biome, and a sign's text. */
    fun blockAt(pos: BlockPos): BlockInfo = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val at = pos.toMc()
        val state = level.getBlockState(at)
        val sign = level.getBlockEntity(at) as? net.minecraft.world.level.block.entity.SignBlockEntity
        fun lines(slot: net.minecraft.world.level.block.entity.SignTextSlot) = sign?.getText(slot)?.getMessages(false)?.map { it.string }
        BlockInfo(
            pos = pos,
            id = BuiltInRegistries.BLOCK.getKey(state.block).toString(),
            properties = state.values.toList().associate { it.property().name to it.valueName() },
            biome = level.getBiome(at).registeredName,
            skyLight = level.getBrightness(net.minecraft.world.level.LightLayer.SKY, at),
            blockLight = level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, at),
            light = level.getMaxLocalRawBrightness(at),
            isAir = state.isAir,
            isSolid = state.isSolid,
            hardness = state.getDestroySpeed(level, at),
            lightEmission = state.lightEmission,
            signFront = lines(net.minecraft.world.level.block.entity.SignTextSlot.FRONT),
            signBack = lines(net.minecraft.world.level.block.entity.SignTextSlot.BACK),
        )
    }

    fun blockAt(x: Int, y: Int, z: Int) = blockAt(BlockPos(x, y, z))

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
            .filter { it !== self && (self == null || self.distanceTo(it) <= radius) }
            .map { it.toInfo(self) }
            .sortedBy { it.distance }
    }

    /** The entity with [id] (from `entities()`, an event or `targetedEntity()`), or null if it's gone. */
    fun entity(id: Int): EntityInfo? = game { mc -> mc.level?.getEntity(id)?.toInfo(mc.player) }

    fun entities(radius: Int): List<EntityInfo> = entities(radius.toDouble())

    fun players(): List<EntityInfo> = game { mc ->
        val level = mc.level ?: error("Not in a world")
        val self = mc.player
        level.players().filter { it !== self }.map { it.toInfo(self) }.sortedBy { it.distance }
    }

    // ---- world rendering ----------------------------------------------------------------------
    // Shapes stay until removed (handle.remove() / clearShapes()) or the script ends. throughWalls
    // draws them on top of terrain (handy for finding things, and also what an ESP does).

    /** Outline (and by default fill) one block. */
    fun highlight(pos: BlockPos, color: Int = Color.GREEN, fill: Boolean = true, throughWalls: Boolean = true) =
        box(Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()), Vec3(pos.x + 1.0, pos.y + 1.0, pos.z + 1.0), color, fill, throughWalls)

    fun highlight(x: Int, y: Int, z: Int, color: Int = Color.GREEN, fill: Boolean = true, throughWalls: Boolean = true) =
        highlight(BlockPos(x, y, z), color, fill, throughWalls)

    /** A box between two corners, in world coordinates. */
    fun box(min: Vec3, max: Vec3, color: Int = Color.GREEN, fill: Boolean = false, throughWalls: Boolean = true): ShapeHandle =
        draw(Shape.Box(min, max, color, fill, throughWalls))

    fun line(from: Vec3, to: Vec3, color: Int = Color.WHITE, width: Float = 2f, throughWalls: Boolean = true): ShapeHandle =
        draw(Shape.Line(from, to, color, width, arrow = false, throughWalls = throughWalls))

    fun arrow(from: Vec3, to: Vec3, color: Int = Color.WHITE, width: Float = 2f, throughWalls: Boolean = true): ShapeHandle =
        draw(Shape.Line(from, to, color, width, arrow = true, throughWalls = throughWalls))

    /** Floating text that always faces the camera. */
    fun text(pos: Vec3, text: String, color: Int = Color.WHITE, scale: Float = 1f, throughWalls: Boolean = true): ShapeHandle =
        draw(Shape.Text(pos, text, color, scale, throughWalls))

    /** Remove every shape this script has drawn. */
    fun clearShapes() = ShapeRegistry.global.clear(job.id)

    private fun draw(shape: Shape): ShapeHandle {
        job.checkAlive()
        job.cleanups.addIfAbsent(shapeCleanup)
        return ShapeRegistry.global.add(job.id, shape)
    }

    private val shapeCleanup: () -> Unit = { ShapeRegistry.global.clear(job.id) }

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


    private fun Int.sq() = this * this

}
