package dev.ilgax.minekt.api

import com.google.gson.reflect.TypeToken
import dev.ilgax.minekt.job.MessageKind
import net.minecraft.world.level.storage.LevelResource
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
inline fun <reified T> Minekt.persistent(
    key: String? = null,
    perWorld: Boolean = false,
    store: String? = null,
    noinline default: () -> T,
): PersistentProperty<T> = PersistentProperty(this, key, perWorld, store, object : TypeToken<T>() {}.type, default)

/** Write this script's persistent values to disk now (they're also saved automatically). */
fun Minekt.save() {
    job.checkAlive()
    job.storage.save(job.name)
}

class PersistentProperty<T> @PublishedApi internal constructor(
    private val api: Minekt,
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
            job.host.echo("[${job.name}] Saved '$fullKey' doesn't fit its type any more (${e.message}); starting fresh", MessageKind.ERROR)
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

/** Stable id of the current world: the save folder in singleplayer, the address on a server. */
internal fun Minekt.worldKey(): String = game { mc ->
    mc.singleplayerServer?.let { "singleplayer:" + it.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().fileName }
        ?: mc.currentServer?.let { "server:" + it.ip }
        ?: error("perWorld values need you to be in a world")
}
