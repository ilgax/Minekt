package dev.ilgax.minekt.api

import kotlinx.coroutines.delay
import kotlin.time.Duration

// Timers: run something later, repeatedly, or at an in-game time.
//
//   every(5.seconds) { actionBar("HP ${player.health}") }
//   after(30.seconds) { echo("half a minute later") }
//   everyTicks(10) { … }
//   atDaytime(13000) { echo("Night is falling") }       // in-game time, every Minecraft day
//
// Timer actions run alongside the script body, one at a time (also with screen callbacks), so they
// can use the whole API. The script stays alive while it has timers; `cancel()` stops one.

/** A running timer; [cancel] stops it. */
class Timer internal constructor(private val coroutine: kotlinx.coroutines.Job) {
    val isActive: Boolean get() = coroutine.isActive
    fun cancel() = coroutine.cancel()
}

/** Run [action] every [interval], first after [initialDelay]. The next wait starts when the action is done. */
fun ScriptContext.every(interval: Duration, initialDelay: Duration = interval, action: () -> Unit): Timer {
    require(interval.isPositive()) { "Interval must be positive" }
    return Timer(job.launchSerial {
        delay(initialDelay)
        while (true) {
            action()
            delay(interval)
        }
    })
}

/** Run [action] once, after [delay]. */
fun ScriptContext.after(delay: Duration, action: () -> Unit): Timer = Timer(job.launchSerial {
    delay(delay)
    action()
})

/** Run [action] every [ticks] game ticks (20 per second). */
fun ScriptContext.everyTicks(ticks: Int, action: () -> Unit): Timer {
    require(ticks > 0) { "ticks must be at least 1" }
    return Timer(job.launchSerial {
        while (true) {
            awaitTicks(ticks)
            action()
        }
    })
}

/** Run [action] once, after [ticks] game ticks. */
fun ScriptContext.afterTicks(ticks: Int, action: () -> Unit): Timer = Timer(job.launchSerial {
    awaitTicks(ticks)
    action()
})

/**
 * Run [action] whenever the in-game time of day passes [dayTime] (0–23999: 0 is sunrise, 6000 noon,
 * 13000 nightfall, 18000 midnight). Checked every tick; time jumps (`/time set`) count as passing.
 */
fun Minekt.atDaytime(dayTime: Long, action: () -> Unit): Timer {
    require(dayTime in 0..23999) { "dayTime must be 0..23999" }
    fun now() = game { mc -> mc.level?.let { Math.floorMod(it.defaultClockTime, 24000L) } }
    return Timer(job.launchSerial {
        var previous = now()
        while (true) {
            awaitTicks(1)
            val current = now()
            if (previous != null && current != null && passed(previous, current, dayTime)) action()
            previous = current
        }
    })
}

/** Whether going from [from] to [to] (wrapping at 24000) passed [target]. */
internal fun passed(from: Long, to: Long, target: Long): Boolean = when {
    from == to -> false
    from < to -> target in (from + 1)..to
    else -> target > from || target <= to
}
