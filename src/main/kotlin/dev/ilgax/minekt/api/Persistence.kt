package dev.ilgax.minekt.api

import net.minecraft.world.phys.Vec3
import com.google.gson.reflect.TypeToken
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.ScriptLogs
import java.lang.reflect.Type
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// Values that survive script restarts, game restarts and world changes:
//
//   val homes by persistent { mutableMapOf<String, Vec3>() }     // in-place changes are saved too
//   var runs by persistent { 0 }; runs++
//   val seen by persistent(perWorld = true) { mutableSetOf<String>() }
//
// Stored as JSON in .minecraft/minekt/data/<script>.json (pass `store` to share between scripts).
// Saved when a var is assigned, every few seconds, when the script ends, and when the game closes.
// Lists, sets, maps, strings, numbers, booleans, Minekt's types and your own data classes all work.

/**
 * A value restored from disk when the script starts, or created by [default] the first time.
 *
 * @param key name to store it under; defaults to the property name.
 * @param perWorld keep a separate value per singleplayer world / server.
 * @param store file to keep it in (`<store>.json`); defaults to the script's name.
 */
inline fun <reified T> ScriptContext.persistent(
    key: String? = null,
    perWorld: Boolean = false,
    store: String? = null,
    noinline default: () -> T,
): PersistentProperty<T> = PersistentProperty(this, key, perWorld, store, object : TypeToken<T>() {}.type, default)

/** Write this script's persistent values to disk now (they're also saved automatically). */
fun ScriptContext.save() {
    job.checkAlive()
    job.storage.saveOwned(job)
}

class PersistentProperty<T> @PublishedApi internal constructor(
    private val api: ScriptContext,
    private val key: String?,
    private val perWorld: Boolean,
    private val store: String?,
    private val type: Type,
    private val default: () -> T,
) {
    operator fun provideDelegate(thisRef: Any?, property: KProperty<*>): ReadWriteProperty<Any?, T> {
        val job = api.job
        job.checkAlive()
        val storeName = store ?: job.name
        val fullKey = (key ?: property.name).let { if (perWorld) "$it@${api.worldKey()}" else it }
        val initial: T = try {
            job.storage.read<T>(storeName, fullKey, type)
        } catch (e: Exception) {
            job.log(ScriptLogs.Level.WARN, "Saved '$fullKey' doesn't fit its type any more (${e.message}); starting fresh", chat = MessageKind.ERROR)
            null
        } ?: default()
        val holder = Holder(initial)
        job.storage.bind(storeName, fullKey, job, type) { holder.value }
        return object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = holder.value
            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                holder.value = value
                job.storage.save(storeName)
            }
        }
    }

    private class Holder<T>(@Volatile var value: T)
}
