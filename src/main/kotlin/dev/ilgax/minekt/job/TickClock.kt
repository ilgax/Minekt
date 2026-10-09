package dev.ilgax.minekt.job

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Counts client ticks so scripts can wait on game time rather than wall-clock time. */
object TickClock {
    private val lock = ReentrantLock()
    private val ticked = lock.newCondition()

    @Volatile var ticks = 0L
        private set

    fun advance() = lock.withLock {
        ticks++
        ticked.signalAll()
    }

    /** Block until [count] more ticks have passed. Throws [InterruptedException] if interrupted. */
    fun await(count: Int) = lock.withLock {
        val target = ticks + count
        while (ticks < target) ticked.await(1, TimeUnit.SECONDS)
    }
}
