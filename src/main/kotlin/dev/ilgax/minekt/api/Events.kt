package dev.ilgax.minekt.api

// Game events. Like onChat/onTick, handlers run on the script's thread after the script body
// finishes, and keep the script alive until stop() or \kill.

/** Every [GameEvent]; use `when (event) { is GameEvent.Damage -> … }` or the specific helpers below. */
fun Minekt.onEvent(handler: (GameEvent) -> Unit): Handler = addGameHandler(GameEvent::class.java, handler)

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
fun Minekt.onStop(handler: (reason: StopReason) -> Unit): Handler {
    job.checkAlive()
    return addHandler(job.stopHandlers, handler)
}

@PublishedApi
internal fun Minekt.addGameHandler(type: Class<out GameEvent>, handler: (GameEvent) -> Unit): Handler {
    job.checkAlive()
    job.gameEventTypes += type
    return addHandler(job.gameHandlers, handler)
}

@PublishedApi
internal inline fun <reified T : GameEvent> Minekt.onEventOf(crossinline handler: (T) -> Unit): Handler =
    addGameHandler(T::class.java) { if (it is T) handler(it) }

// Handlers with more than one value get the event itself; unpack it like `onDamage { (amount, health) -> }`.

/**
 * The player lost health: `amount`, the `health` left, the `cause` (like `minecraft:fall` or
 * `minecraft:mob_attack`) and the `attacker` (the archer, not the arrow: `projectile` has that), the
 * last three null when unknown. `onDamage { (amount, health, cause, attacker) -> }`.
 */
fun Minekt.onDamage(handler: (GameEvent.Damage) -> Unit): Handler = onEventOf<GameEvent.Damage> { handler(it) }
fun Minekt.onDeath(handler: () -> Unit): Handler = onEventOf<GameEvent.Death> { handler() }
fun Minekt.onRespawn(handler: () -> Unit): Handler = onEventOf<GameEvent.Respawn> { handler() }
fun Minekt.onJoinWorld(handler: (dimension: String) -> Unit): Handler = onEventOf<GameEvent.JoinWorld> { handler(it.dimension) }
fun Minekt.onLeaveWorld(handler: () -> Unit): Handler = onEventOf<GameEvent.LeaveWorld> { handler() }
/** You went to another dimension: `onDimensionChange { (from, to) -> }`. */
fun Minekt.onDimensionChange(handler: (GameEvent.DimensionChange) -> Unit): Handler = onEventOf<GameEvent.DimensionChange> { handler(it) }
fun Minekt.onScreenOpen(handler: (ScreenInfo) -> Unit): Handler = onEventOf<GameEvent.ScreenOpen> { handler(it.screen) }
fun Minekt.onScreenClose(handler: () -> Unit): Handler = onEventOf<GameEvent.ScreenClose> { handler() }
/** A chunk loaded: `onChunkLoad { (x, z) -> }`, in chunk coordinates. */
fun Minekt.onChunkLoad(handler: (GameEvent.ChunkLoad) -> Unit): Handler = onEventOf<GameEvent.ChunkLoad> { handler(it) }
/** Items arrived in your inventory: `onItemGained { (id, count) -> }`. */
fun Minekt.onItemGained(handler: (GameEvent.ItemGained) -> Unit): Handler = onEventOf<GameEvent.ItemGained> { handler(it) }

/** A block in a loaded chunk turned into another block (placed, broken, grown…): `onBlockChange { (pos, old, new) -> }`, with block ids. */
fun Minekt.onBlockChange(handler: (GameEvent.BlockChange) -> Unit): Handler = onEventOf<GameEvent.BlockChange> { handler(it) }
/** An entity came into view. */
fun Minekt.onEntityAppear(handler: (EntityInfo) -> Unit): Handler = onEventOf<GameEvent.EntityAppear> { handler(it.entity) }
/** An entity went away: `onEntityGone { (entity, died) -> }`, [died] or despawned / moved out of range. */
fun Minekt.onEntityGone(handler: (GameEvent.EntityGone) -> Unit): Handler = onEventOf<GameEvent.EntityGone> { handler(it) }
/** A sound started playing: `onSound { (id, pos, category) -> if (id == "minecraft:entity.creeper.primed") … }`. */
fun Minekt.onSound(handler: (GameEvent.Sound) -> Unit): Handler = onEventOf<GameEvent.Sound> { handler(it) }
/** The server sent particles: `onParticle { (id, pos, count) -> }`. */
fun Minekt.onParticle(handler: (GameEvent.Particle) -> Unit): Handler = onEventOf<GameEvent.Particle> { handler(it) }
/** A player joined (appeared in the tab list). */
fun Minekt.onPlayerJoin(handler: (name: String) -> Unit): Handler = onEventOf<GameEvent.PlayerJoin> { handler(it.name) }
/** A player left (disappeared from the tab list). */
fun Minekt.onPlayerLeave(handler: (name: String) -> Unit): Handler = onEventOf<GameEvent.PlayerLeave> { handler(it.name) }
/** The sidebar scoreboard changed: its title and lines, top to bottom (empty when it went away). */
fun Minekt.onScoreboardChange(handler: (Sidebar) -> Unit): Handler = onEventOf<GameEvent.ScoreboardChange> { handler(Sidebar(it.title, it.lines)) }

/** Names in the tab list. */
val Minekt.tabList: List<String> get() = game { mc -> readTabList(mc) }

/** The sidebar scoreboard, or null if there is none. */
val Minekt.sidebar: Sidebar? get() = game { mc -> readSidebar(mc) }

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
