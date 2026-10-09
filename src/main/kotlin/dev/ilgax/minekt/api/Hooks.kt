package dev.ilgax.minekt.api

import dev.ilgax.minekt.hooks.MethodHooks
import java.lang.reflect.Method

// Run code before or after any method of the game or another mod, even though it's already loaded:
//
//   hook<LocalPlayer>("jumpFromGround") { echo("jump!") }
//   hook<LocalPlayer>("jumpFromGround") { it.cancel() }                     // skip the method
//   hookAfter<LocalPlayer>("getSpeed") { it.result = (it.result as Float) * 2 }
//   hook("net.minecraft.client.gui.Gui", "setOverlayMessage", Component::class.java, Boolean::class.java) {
//       it.args[0] = Component.literal("rewritten")
//   }
//
// Handlers run on whatever thread called the method (usually the game thread), right then, so
// keep them quick. `args` can be changed; `cancel(value)` skips the method and returns `value`;
// in `hookAfter`, `result` is what the method returned and can be replaced. Only the class that
// declares the method is hooked, not subclasses that override it. A handler that throws is
// removed. Hooks are removed when the script ends, and the original code is restored.
//
// Under the hood a Java agent is attached at runtime; this needs a Java runtime that allows it.

/** One call of a hooked method. */
class HookCall internal constructor(
    /** The object the method was called on, or null for a static method. */
    val self: Any?,
    /** The arguments; assign to change them (before hooks). */
    val args: Array<Any?>,
    val method: Method,
) {
    internal var cancelled = false

    /** In `hookAfter`: the value being returned; assign to replace it. */
    var result: Any? = null

    /** Skip the method (before hooks) and return [returnValue] instead. */
    fun cancel(returnValue: Any? = null) {
        cancelled = true
        result = returnValue
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> arg(index: Int): T = args[index] as T
}

/** Returned by `hook`; [remove] takes the hook off again. */
class MethodHook internal constructor(private val remove: () -> Unit) {
    fun remove() = remove.invoke()
}

/** Run [handler] before every call of [owner]'s method [name] (pass [parameterTypes] if it's overloaded). */
fun Minekt.hook(owner: Class<*>, name: String, vararg parameterTypes: Class<*>, handler: (HookCall) -> Unit): MethodHook =
    addHook(findMethod(owner, name, parameterTypes), after = false, handler)

fun Minekt.hook(className: String, name: String, vararg parameterTypes: Class<*>, handler: (HookCall) -> Unit): MethodHook =
    hook(loadClass(className), name, *parameterTypes, handler = handler)

inline fun <reified T> Minekt.hook(name: String, vararg parameterTypes: Class<*>, noinline handler: (HookCall) -> Unit): MethodHook =
    hook(T::class.java, name, *parameterTypes, handler = handler)

/** Run [handler] after every call of [owner]'s method [name], with `result` set to what it returned. */
fun Minekt.hookAfter(owner: Class<*>, name: String, vararg parameterTypes: Class<*>, handler: (HookCall) -> Unit): MethodHook =
    addHook(findMethod(owner, name, parameterTypes), after = true, handler)

fun Minekt.hookAfter(className: String, name: String, vararg parameterTypes: Class<*>, handler: (HookCall) -> Unit): MethodHook =
    hookAfter(loadClass(className), name, *parameterTypes, handler = handler)

inline fun <reified T> Minekt.hookAfter(name: String, vararg parameterTypes: Class<*>, noinline handler: (HookCall) -> Unit): MethodHook =
    hookAfter(T::class.java, name, *parameterTypes, handler = handler)

private fun Minekt.loadClass(name: String): Class<*> =
    try {
        Class.forName(name, false, Minekt::class.java.classLoader)
    } catch (e: ClassNotFoundException) {
        throw IllegalArgumentException("No class named $name")
    }

private fun findMethod(owner: Class<*>, name: String, parameterTypes: Array<out Class<*>>): Method {
    val candidates = owner.declaredMethods.filter { it.name == name && !it.isSynthetic && !it.isBridge }
    if (parameterTypes.isNotEmpty()) {
        return candidates.firstOrNull { it.parameterTypes.contentEquals(parameterTypes) }
            ?: throw IllegalArgumentException("${owner.simpleName} has no $name(${parameterTypes.joinToString { it.simpleName }})")
    }
    return when (candidates.size) {
        1 -> candidates.single()
        0 -> throw IllegalArgumentException(
            "${owner.simpleName} declares no method '$name'" +
                (owner.superclass?.takeIf { s -> s.declaredMethods.any { it.name == name } }?.let { " (${it.simpleName} does: hook that class)" } ?: ""),
        )
        else -> throw IllegalArgumentException(
            "${owner.simpleName}.$name is overloaded; pass the parameter types of one of: " +
                candidates.joinToString("; ") { MethodHooks.describe(it) },
        )
    }
}

private fun Minekt.addHook(method: Method, after: Boolean, handler: (HookCall) -> Unit): MethodHook {
    job.checkAlive()
    return MethodHook(MethodHooks.add(job, method, after, handler))
}
