package dev.ilgax.minekt.api

// Game events. Like onChat/onTick, handlers run on the script's thread after the script body
// finishes, and keep the script alive until stop() or \kill.

/** Every [GameEvent]; use `when (event) { is GameEvent.Damage -> … }` or the specific helpers below. */
fun Minekt.onEvent(handler: (GameEvent) -> Unit) = addGameHandler(GameEvent::class.java, handler)

/** Why a script ended, for [onStop]. */
enum class StopReason {
    /** Its body finished and nothing kept it running, or it called `stop()`. */
    FINISHED,
    /** `\kill`, the Stop button, or the game closing. */
    KILLED,
    /** It ended with an error. */
    FAILED,
}

/**
 * Run [handler] once when the script ends, however it ends: to put back something it changed, save, or
 * say goodbye. The API still works in it, even after `\kill` (for up to 3 seconds). Doesn't keep the
 * script running.
 */
fun Minekt.onStop(handler: (reason: StopReason) -> Unit) {
    job.checkAlive()
    job.stopHandlers += handler
}

@PublishedApi
internal fun Minekt.addGameHandler(type: Class<out GameEvent>, handler: (GameEvent) -> Unit) {
    job.checkAlive()
    job.gameEventTypes += type
    job.gameHandlers += handler
}

@PublishedApi
internal inline fun <reified T : GameEvent> Minekt.onEventOf(crossinline handler: (T) -> Unit) =
    addGameHandler(T::class.java) { if (it is T) handler(it) }

/** The player lost health: [handler] gets the amount lost and the health left. */
fun Minekt.onDamage(handler: (amount: Float, health: Float) -> Unit) = onEventOf<GameEvent.Damage> { handler(it.amount, it.health) }

/**
 * The player lost health, and what did it: [cause] like `minecraft:fall` or `minecraft:mob_attack`, and the
 * [attacker] (the archer, not the arrow; `GameEvent.Damage.projectile` has that). Either is null when unknown.
 */
fun Minekt.onDamage(handler: (amount: Float, health: Float, cause: String?, attacker: EntityInfo?) -> Unit) =
    onEventOf<GameEvent.Damage> { handler(it.amount, it.health, it.cause, it.attacker) }
fun Minekt.onDeath(handler: () -> Unit) = onEventOf<GameEvent.Death> { handler() }
fun Minekt.onRespawn(handler: () -> Unit) = onEventOf<GameEvent.Respawn> { handler() }
fun Minekt.onJoinWorld(handler: (dimension: String) -> Unit) = onEventOf<GameEvent.JoinWorld> { handler(it.dimension) }
fun Minekt.onLeaveWorld(handler: () -> Unit) = onEventOf<GameEvent.LeaveWorld> { handler() }
fun Minekt.onDimensionChange(handler: (from: String, to: String) -> Unit) = onEventOf<GameEvent.DimensionChange> { handler(it.from, it.to) }
fun Minekt.onScreenOpen(handler: (ScreenInfo) -> Unit) = onEventOf<GameEvent.ScreenOpen> { handler(it.screen) }
fun Minekt.onScreenClose(handler: () -> Unit) = onEventOf<GameEvent.ScreenClose> { handler() }
fun Minekt.onChunkLoad(handler: (x: Int, z: Int) -> Unit) = onEventOf<GameEvent.ChunkLoad> { handler(it.x, it.z) }
fun Minekt.onItemGained(handler: (id: String, count: Int) -> Unit) = onEventOf<GameEvent.ItemGained> { handler(it.id, it.count) }

/** A block in a loaded chunk turned into another block (placed, broken, grown…): its position and old and new block ids. */
fun Minekt.onBlockChange(handler: (pos: BlockPos, old: String, new: String) -> Unit) = onEventOf<GameEvent.BlockChange> { handler(it.pos, it.old, it.new) }
/** An entity came into view. */
fun Minekt.onEntityAppear(handler: (EntityInfo) -> Unit) = onEventOf<GameEvent.EntityAppear> { handler(it.entity) }
/** An entity went away: [died], or despawned / moved out of range. */
fun Minekt.onEntityGone(handler: (entity: EntityInfo, died: Boolean) -> Unit) = onEventOf<GameEvent.EntityGone> { handler(it.entity, it.died) }
/** A sound started playing, e.g. `onSound { id, pos, _ -> if (id == "minecraft:entity.creeper.primed") … }`. */
fun Minekt.onSound(handler: (id: String, pos: Vec3, category: String) -> Unit) = onEventOf<GameEvent.Sound> { handler(it.id, it.pos, it.category) }
/** The server sent particles. */
fun Minekt.onParticle(handler: (id: String, pos: Vec3, count: Int) -> Unit) = onEventOf<GameEvent.Particle> { handler(it.id, it.pos, it.count) }
/** A player joined (appeared in the tab list). */
fun Minekt.onPlayerJoin(handler: (name: String) -> Unit) = onEventOf<GameEvent.PlayerJoin> { handler(it.name) }
/** A player left (disappeared from the tab list). */
fun Minekt.onPlayerLeave(handler: (name: String) -> Unit) = onEventOf<GameEvent.PlayerLeave> { handler(it.name) }
/** The sidebar scoreboard changed: its title and lines, top to bottom (empty when it went away). */
fun Minekt.onScoreboardChange(handler: (Sidebar) -> Unit) = onEventOf<GameEvent.ScoreboardChange> { handler(Sidebar(it.title, it.lines)) }

/** Names in the tab list. */
fun Minekt.tabList(): List<String> = game { mc -> readTabList(mc) }

/** The sidebar scoreboard, or null if there is none. */
fun Minekt.sidebar(): Sidebar? = game { mc -> readSidebar(mc) }

internal fun readTabList(mc: net.minecraft.client.Minecraft): List<String> =
    mc.connection?.listedOnlinePlayers?.map { it.profile.name() }?.sorted().orEmpty()

internal fun readSidebar(mc: net.minecraft.client.Minecraft): Sidebar? {
    val scoreboard = mc.level?.scoreboard ?: return null
    val objective = scoreboard.getDisplayObjective(net.minecraft.world.scores.DisplaySlot.SIDEBAR) ?: return null
    val lines = scoreboard.listPlayerScores(objective).filterNot { it.isHidden }
        .sortedWith(compareByDescending<net.minecraft.world.scores.PlayerScoreEntry> { it.value() }.thenBy { it.owner() })
        .take(15)
        .map { entry -> net.minecraft.world.scores.PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).string }
    return Sidebar(objective.displayName.string, lines)
}
