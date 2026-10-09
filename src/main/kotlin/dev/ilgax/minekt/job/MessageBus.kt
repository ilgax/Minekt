package dev.ilgax.minekt.job

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap

/**
 * Messages between running scripts: `publish(topic, value)` reaches every script subscribed to
 * that topic, and the latest value of each topic is kept for scripts that start later.
 */
class MessageBus(private val jobs: () -> Collection<Job>) {
    /** A published value, boxed so that `null` can be published and retained too. */
    private class Retained(val value: Any?)

    private val retained = ConcurrentHashMap<String, Retained>()

    private val gson: Gson = GsonBuilder()
        .enableComplexMapKeySerialization()
        .serializeSpecialFloatingPointValues()
        .create()

    fun publish(topic: String, value: Any?, from: Job) {
        retained[topic] = Retained(value)
        val message = Job.Event.Message(topic, value, from.name)
        jobs().forEach { it.post(message) }
    }

    /** Whether [topic] has been published to, and its latest value. */
    fun latest(topic: String): Pair<Boolean, Any?> = retained[topic]?.let { true to it.value } ?: (false to null)

    /**
     * [value] as [type]. Every script has its own class loader, so a data class from the sender
     * isn't the receiver's class even when it's declared the same way: such values (and generic
     * collections, whose elements can't be checked) are converted by copying through JSON.
     */
    fun convert(value: Any?, type: Type): Any? {
        if (value == null) return null
        if (type is Class<*> && type.isInstance(value)) return value
        return try {
            gson.fromJson(gson.toJsonTree(value), type)
        } catch (e: Exception) {
            throw IllegalArgumentException("A ${value.javaClass.simpleName} message can't be read as ${type.typeName}", e)
        }
    }
}
