package dev.ilgax.minekt.job

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Script code behind clickable chat text (`ChatClick.run`). The text runs `/minekt click <id>`;
 * the click is queued to the owning script, which runs the action on its own thread.
 */
object ChatClicks {
    private val next = AtomicLong()
    private val owners = ConcurrentHashMap<Long, Job>()

    fun add(job: Job, action: () -> Unit): Long {
        val id = next.incrementAndGet()
        job.clickActions[id] = action
        owners[id] = job
        return id
    }

    /** The player clicked [id]: false if its script has ended. */
    fun click(id: Long): Boolean {
        val job = owners[id] ?: return false
        if (job.state == Job.State.DONE) {
            owners.remove(id)
            return false
        }
        job.post(Job.Event.Click(id))
        return true
    }

    /** [job] ended: forget its clicks. */
    internal fun removeAll(job: Job) {
        job.clickActions.keys.forEach { owners.remove(it) }
        job.clickActions.clear()
    }
}
