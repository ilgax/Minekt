package dev.ilgax.minekt.api

// Game events. Like onChat/onTick, handlers run on the script's thread after the script body
// finishes, and keep the script alive until stop() or \kill.

/** Every [MinektEvent]; use `when (event) { is MinektEvent.Damage -> … }` or the specific helpers below. */
fun Minekt.onEvent(handler: (MinektEvent) -> Unit): Handler = addGameHandler(MinektEvent::class.java, handler)

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
internal fun Minekt.addGameHandler(type: Class<out MinektEvent>, handler: (MinektEvent) -> Unit): Handler {
    job.checkAlive()
    job.gameEventTypes += type
    return addHandler(job.gameHandlers, handler)
}

@PublishedApi
internal inline fun <reified T : MinektEvent> Minekt.onEventOf(crossinline handler: (T) -> Unit): Handler =
    addGameHandler(T::class.java) { if (it is T) handler(it) }

// Handlers with more than one value get the event itself; unpack it like `onDamage { (amount, health) -> }`.

/**
 * The player lost health: `amount`, the `health` left, the `cause` (like `minecraft:fall` or
 * `minecraft:mob_attack`) and the `attacker` (the archer, not the arrow: `projectile` has that), the
 * last three null when unknown. `onDamage { (amount, health, cause, attacker) -> }`.
 */
fun Minekt.onDamage(handler: (MinektEvent.Damage) -> Unit): Handler = onEventOf<MinektEvent.Damage> { handler(it) }
fun Minekt.onDeath(handler: () -> Unit): Handler = onEventOf<MinektEvent.Death> { handler() }
fun Minekt.onRespawn(handler: () -> Unit): Handler = onEventOf<MinektEvent.Respawn> { handler() }
fun Minekt.onJoinWorld(handler: (dimension: String) -> Unit): Handler = onEventOf<MinektEvent.JoinWorld> { handler(it.dimension) }
fun Minekt.onLeaveWorld(handler: () -> Unit): Handler = onEventOf<MinektEvent.LeaveWorld> { handler() }
/** You went to another dimension: `onDimensionChange { (from, to) -> }`. */
fun Minekt.onDimensionChange(handler: (MinektEvent.DimensionChange) -> Unit): Handler = onEventOf<MinektEvent.DimensionChange> { handler(it) }
fun Minekt.onScreenOpen(handler: (ScreenInfo) -> Unit): Handler = onEventOf<MinektEvent.ScreenOpen> { handler(it.screen) }
fun Minekt.onScreenClose(handler: () -> Unit): Handler = onEventOf<MinektEvent.ScreenClose> { handler() }
/** A chunk loaded: `onChunkLoad { (x, z) -> }`, in chunk coordinates. */
fun Minekt.onChunkLoad(handler: (MinektEvent.ChunkLoad) -> Unit): Handler = onEventOf<MinektEvent.ChunkLoad> { handler(it) }
/** Items arrived in your inventory: `onItemGained { (id, count) -> }`. */
fun Minekt.onItemGained(handler: (MinektEvent.ItemGained) -> Unit): Handler = onEventOf<MinektEvent.ItemGained> { handler(it) }

/** A block in a loaded chunk turned into another block (placed, broken, grown…): `onBlockChange { (pos, old, new) -> }`, with block ids. */
fun Minekt.onBlockChange(handler: (MinektEvent.BlockChange) -> Unit): Handler = onEventOf<MinektEvent.BlockChange> { handler(it) }
/** An entity came into view. */
fun Minekt.onEntityAppear(handler: (EntityInfo) -> Unit): Handler = onEventOf<MinektEvent.EntityAppear> { handler(it.entity) }
/** An entity went away: `onEntityGone { (entity, died) -> }`, [died] or despawned / moved out of range. */
fun Minekt.onEntityGone(handler: (MinektEvent.EntityGone) -> Unit): Handler = onEventOf<MinektEvent.EntityGone> { handler(it) }
/** A sound started playing: `onSound { (id, pos, category) -> if (id == "minecraft:entity.creeper.primed") … }`. */
fun Minekt.onSound(handler: (MinektEvent.Sound) -> Unit): Handler = onEventOf<MinektEvent.Sound> { handler(it) }
/** The server sent particles: `onParticle { (id, pos, count) -> }`. */
fun Minekt.onParticle(handler: (MinektEvent.Particle) -> Unit): Handler = onEventOf<MinektEvent.Particle> { handler(it) }
/** A player joined (appeared in the tab list). */
fun Minekt.onPlayerJoin(handler: (name: String) -> Unit): Handler = onEventOf<MinektEvent.PlayerJoin> { handler(it.name) }
/** A player left (disappeared from the tab list). */
fun Minekt.onPlayerLeave(handler: (name: String) -> Unit): Handler = onEventOf<MinektEvent.PlayerLeave> { handler(it.name) }
/** The sidebar scoreboard changed: its title and lines, top to bottom (empty when it went away). */
fun Minekt.onScoreboardChange(handler: (Sidebar) -> Unit): Handler = onEventOf<MinektEvent.ScoreboardChange> { handler(Sidebar(it.title, it.lines)) }

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
