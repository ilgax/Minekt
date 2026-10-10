package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// Coroutines: run several things at once in one script, and wait for events without callbacks.
//
//   launch { while (true) { awaitTicks(20); hud("HP ${player.health}") } }
//   launch { val msg = awaitChat { "ready" in it }; echo("got $msg") }
//   val reply = runBlocking { withTimeoutOrNull(5_000) { awaitChat() } }
//
// Coroutines belong to the script: it stays alive while any are running, and killing the script
// cancels them. Blocking API calls (game { }, sleep) are fine inside them. `kotlinx.coroutines.*`
// is imported by default (delay, withTimeout, coroutineScope, ...).

/** Start a coroutine owned by this script. */
fun Minekt.launch(block: suspend CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
    job.checkAlive()
    return job.scope.launch(block = block)
}

/** Start a coroutine that produces a value; get it with `.await()`. */
fun <T> Minekt.async(block: suspend CoroutineScope.() -> T): Deferred<T> {
    job.checkAlive()
    return job.scope.async(block = block)
}

/** Suspend for [ticks] client ticks. */
suspend fun Minekt.awaitTicks(ticks: Int = 1) {
    repeat(ticks) { job.awaitEvent { it == Job.Event.Tick } }
}

/** Suspend until a chat message matching [predicate] arrives; returns its text. */
suspend fun Minekt.awaitChat(predicate: (String) -> Boolean = { true }): String =
    (job.awaitEvent { it is Job.Event.Chat && predicate(it.text) } as Job.Event.Chat).text

/** Suspend until a [GameEvent] matching [predicate] happens. */
suspend fun Minekt.awaitEvent(predicate: (GameEvent) -> Boolean = { true }): GameEvent {
    job.gameAwaiters.incrementAndGet()
    try {
        return (job.awaitEvent { it is Job.Event.Game && predicate(it.event) } as Job.Event.Game).event
    } finally {
        job.gameAwaiters.decrementAndGet()
    }
}

/** Suspend until a game event of type [T] happens, e.g. `awaitEventOf<GameEvent.Death>()`. */
suspend inline fun <reified T : GameEvent> Minekt.awaitEventOf(): T = awaitEvent { it is T } as T

/** Suspend until a screen matching [predicate] opens, e.g. `awaitScreen { it.isContainer }`. */
suspend fun Minekt.awaitScreen(predicate: (ScreenInfo) -> Boolean = { true }): ScreenInfo =
    (awaitEvent { it is GameEvent.ScreenOpen && predicate(it.screen) } as GameEvent.ScreenOpen).screen
