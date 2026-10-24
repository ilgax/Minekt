package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

// Coroutines: run several things at once in one script, and wait for events without callbacks.
//
//   launch { while (true) { awaitTicks(20); actionBar("HP ${player.health}") } }
//   launch { val msg = awaitChat { "ready" in it }; echo("got $msg") }
//   val reply = runBlocking { withTimeoutOrNull(5_000) { awaitChat() } }
//
// Coroutines belong to the script: it stays alive while any are running, and killing the script
// cancels them. Blocking API calls (game { }, sleep) are fine inside them. `kotlinx.coroutines.*`
// is imported by default (delay, withTimeout, coroutineScope, ...).

/** Start a coroutine owned by this script. */
fun ScriptContext.launch(block: suspend CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
    job.checkAlive()
    return job.scope.launch(block = block)
}

/** Start a coroutine that produces a value; get it with `.await()`. */
fun <T> ScriptContext.async(block: suspend CoroutineScope.() -> T): Deferred<T> {
    job.checkAlive()
    return job.scope.async(block = block)
}

/** Suspend for [ticks] game ticks. */
suspend fun ScriptContext.awaitTicks(ticks: Int = 1) {
    repeat(ticks) { job.awaitEvent { it == Job.Event.Tick } }
}

/** Suspend until a chat message matching [predicate] arrives (`awaitChat { "ready" in it.text }`), and return it. */
suspend fun Minekt.awaitChat(predicate: (ChatMessage) -> Boolean = { true }): ChatMessage =
    (job.awaitEvent { it is Job.Event.Chat && predicate(it.message) } as Job.Event.Chat).message

/** Suspend until a [MinektEvent] matching [predicate] happens. */
suspend fun Minekt.awaitEvent(predicate: (MinektEvent) -> Boolean = { true }): MinektEvent {
    job.gameAwaiters.incrementAndGet()
    try {
        return (job.awaitEvent { it is Job.Event.Game && predicate(it.event) } as Job.Event.Game).event
    } finally {
        job.gameAwaiters.decrementAndGet()
    }
}

/** Suspend until a screen matching [predicate] opens, e.g. `awaitScreen { it.isContainer }`. */
suspend fun Minekt.awaitScreen(predicate: (ScreenInfo) -> Boolean = { true }): ScreenInfo =
    (awaitEvent { it is MinektEvent.ScreenOpen && predicate(it.screen) } as MinektEvent.ScreenOpen).screen
