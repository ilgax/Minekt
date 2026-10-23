package dev.ilgax.minekt.api

/**
 * Something a script added that runs until the script ends, like an `onTick` or `onChat` handler,
 * a `command` or a `subscribe`: [remove] takes it out earlier.
 */
class Handler internal constructor(private val onRemove: () -> Unit) {
    @Volatile
    private var removed = false

    /** Take it out. Calling it again does nothing. */
    fun remove() {
        if (removed) return
        removed = true
        onRemove()
    }
}

/** Add [item] to [list] and return a [Handler] that takes it out again. */
internal fun <T> addHandler(list: MutableCollection<T>, item: T): Handler {
    list += item
    return Handler { list.remove(item) }
}
