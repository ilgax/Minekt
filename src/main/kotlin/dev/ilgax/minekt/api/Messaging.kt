package dev.ilgax.minekt.api

import com.google.gson.reflect.TypeToken
import dev.ilgax.minekt.job.Job
import java.lang.reflect.Type
import java.util.concurrent.CopyOnWriteArrayList

// Messages between running scripts. One script publishes, any number of others listen:
//
//   // stats.kts                                   // statshud.kts
//   data class Stats(val kills: Int, val hp: Float) data class Stats(val kills: Int, val hp: Float)
//   publish("stats", Stats(kills, player.health))  subscribe<Stats>("stats") { hud("Kills ${it.kills}") }
//
// Topics are any name you like. Values can be anything; scripts each have their own classes, so a
// data class declared the same way in both scripts (or a list/map/number/string) is the way to go:
// when the types differ, the value is copied across by its fields.

/** Send [value] to every script subscribed to [topic]. It's also kept as the topic's latest value. */
fun Minekt.publish(topic: String, value: Any? = null) {
    job.checkAlive()
    job.bus.publish(topic, value, job)
}

/**
 * Call [handler] with each value published to [topic] (by any script, this one included), on this
 * script's thread after its body finishes; the script stays alive while subscribed. Pass
 * `latest = true` to also get the topic's current value right away, if there is one.
 */
inline fun <reified T> Minekt.subscribe(topic: String, latest: Boolean = false, noinline handler: (T) -> Unit): Subscription =
    subscribe(topic, object : TypeToken<T>() {}.type, latest) { @Suppress("UNCHECKED_CAST") handler(it as T) }

/** Wait until something is published to [topic] and return it. */
suspend inline fun <reified T> Minekt.awaitMessage(topic: String): T =
    @Suppress("UNCHECKED_CAST") (awaitMessage(topic, object : TypeToken<T>() {}.type) as T)

/** The latest value published to [topic] (by any script, also before this one started), or null. */
inline fun <reified T> Minekt.lastMessage(topic: String): T? =
    @Suppress("UNCHECKED_CAST") (lastMessage(topic, object : TypeToken<T>() {}.type) as T?)

/** Returned by [subscribe]; [cancel] stops listening. */
class Subscription internal constructor(private val cancel: () -> Unit) {
    fun cancel() = cancel.invoke()
}

@PublishedApi
internal fun Minekt.subscribe(topic: String, type: Type, latest: Boolean, handler: (Any?) -> Unit): Subscription {
    job.checkAlive()
    val converting: (Any?) -> Unit = { value -> handler(job.bus.convert(value, type)) }
    val handlers = job.messageHandlers.computeIfAbsent(topic) { CopyOnWriteArrayList() }
    handlers += converting
    if (latest) {
        val (published, value) = job.bus.latest(topic)
        if (published) job.post(Job.Event.Message(topic, value, job.name))
    }
    return Subscription { handlers -= converting }
}

@PublishedApi
internal suspend fun Minekt.awaitMessage(topic: String, type: Type): Any? {
    val message = job.awaitEvent { it is Job.Event.Message && it.topic == topic } as Job.Event.Message
    return job.bus.convert(message.value, type)
}

@PublishedApi
internal fun Minekt.lastMessage(topic: String, type: Type): Any? {
    job.checkAlive()
    return job.bus.convert(job.bus.latest(topic).second, type)
}
