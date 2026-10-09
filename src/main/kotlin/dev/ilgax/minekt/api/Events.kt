package dev.ilgax.minekt.api

// Game events. Like onChat/onTick, handlers run on the script's thread after the script body
// finishes, and keep the script alive until stop() or \kill.

/** Every [GameEvent]; use `when (event) { is GameEvent.Damage -> … }` or the specific helpers below. */
fun Minekt.onEvent(handler: (GameEvent) -> Unit) {
    job.gameHandlers += handler
}

@PublishedApi
internal inline fun <reified T : GameEvent> Minekt.onEventOf(crossinline handler: (T) -> Unit) =
    onEvent { if (it is T) handler(it) }

/** The player lost health: [handler] gets the amount lost and the health left. */
fun Minekt.onDamage(handler: (amount: Float, health: Float) -> Unit) = onEventOf<GameEvent.Damage> { handler(it.amount, it.health) }
fun Minekt.onDeath(handler: () -> Unit) = onEventOf<GameEvent.Death> { handler() }
fun Minekt.onRespawn(handler: () -> Unit) = onEventOf<GameEvent.Respawn> { handler() }
fun Minekt.onJoinWorld(handler: (dimension: String) -> Unit) = onEventOf<GameEvent.JoinWorld> { handler(it.dimension) }
fun Minekt.onLeaveWorld(handler: () -> Unit) = onEventOf<GameEvent.LeaveWorld> { handler() }
fun Minekt.onDimensionChange(handler: (from: String, to: String) -> Unit) = onEventOf<GameEvent.DimensionChange> { handler(it.from, it.to) }
fun Minekt.onScreenOpen(handler: (ScreenInfo) -> Unit) = onEventOf<GameEvent.ScreenOpen> { handler(it.screen) }
fun Minekt.onScreenClose(handler: () -> Unit) = onEventOf<GameEvent.ScreenClose> { handler() }
fun Minekt.onChunkLoad(handler: (x: Int, z: Int) -> Unit) = onEventOf<GameEvent.ChunkLoad> { handler(it.x, it.z) }
fun Minekt.onItemGained(handler: (id: String, count: Int) -> Unit) = onEventOf<GameEvent.ItemGained> { handler(it.id, it.count) }

/**
 * Filter or rewrite chat **as it arrives**, before it is shown. Return the text to show (changed
 * or not), or null to hide the message. E.g. `interceptChat { if ("[AD]" in it) null else it }`.
 *
 * Unlike other handlers this runs **on the game thread, synchronously**: keep it fast, and don't
 * sleep or wait in it. Rewritten messages lose their original formatting.
 */
fun Minekt.interceptChat(handler: (String) -> String?) {
    job.checkAlive()
    job.incomingInterceptors += handler
}

/**
 * Filter or rewrite chat messages **the player sends** (not commands). Return the text to send,
 * or null to cancel. Runs on the game thread, like [interceptChat].
 */
fun Minekt.interceptSend(handler: (String) -> String?) {
    job.checkAlive()
    job.outgoingInterceptors += handler
}
