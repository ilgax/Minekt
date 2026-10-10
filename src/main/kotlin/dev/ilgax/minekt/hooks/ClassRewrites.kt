package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.job.ScriptLogs
import java.lang.instrument.ClassFileTransformer
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripts replacing the bytecode of classes, loaded or not yet loaded.
 *
 * Every rewrite of a class sees the bytes as the class was defined (after Fabric and mixins) with the
 * earlier rewrites applied, and returns new bytes or null for "unchanged". A loaded class is
 * retransformed right away; one that isn't loaded yet is changed when it loads. Removing a rewrite
 * (or its script ending) retransforms again without it, which puts the code back.
 */
object ClassRewrites {
    class Rewrite internal constructor(val job: Job, val className: String, val transform: (ClassLoader?, ByteArray) -> ByteArray?)

    /** Internal class name (`a/b/C`) → rewrites, oldest first. */
    private val rewrites = ConcurrentHashMap<String, CopyOnWriteArrayList<Rewrite>>()
    /** What went wrong in the transformer during the retransform in progress on this thread. */
    private val failure = ThreadLocal<Pair<Rewrite, Throwable>?>()

    fun add(job: Job, className: String, transform: (ClassLoader?, ByteArray) -> ByteArray?): Rewrite {
        val internalName = className.replace('.', '/')
        val rewrite = Rewrite(job, className, transform)
        synchronized(this) {
            rewrites.computeIfAbsent(internalName) { CopyOnWriteArrayList() } += rewrite
            try {
                retransform(className)
            } catch (t: Throwable) {
                rewrites[internalName]?.remove(rewrite)
                throw t
            }
        }
        return rewrite
    }

    fun remove(rewrite: Rewrite) = synchronized(this) {
        val list = rewrites[rewrite.className.replace('.', '/')] ?: return@synchronized
        if (list.remove(rewrite)) runCatching { retransform(rewrite.className) }
    }

    fun has(job: Job): Boolean = rewrites.values.any { list -> list.any { it.job === job } }

    fun removeAll(job: Job) = synchronized(this) {
        rewrites.values.flatMap { list -> list.filter { it.job === job } }.forEach(::remove)
    }

    /** The loaded classes called [className] (one per class loader that loaded it). */
    fun loaded(className: String): List<Class<*>> = Agent.instrumentation.allLoadedClasses.filter { it.name == className }

    private fun retransform(className: String) {
        val classes = loaded(className)
        if (classes.isEmpty()) return // changed when it loads
        for (cls in classes) check(Agent.instrumentation.isModifiableClass(cls)) { "$className can't be changed at runtime" }
        failure.remove()
        try {
            Agent.instrumentation.retransformClasses(*classes.toTypedArray())
        } catch (e: UnsupportedOperationException) {
            throw IllegalStateException(
                "The JVM refused the new $className: ${e.message}. A loaded class can change its method bodies, but not add, " +
                    "remove or rename methods or fields, or change its superclass or interfaces.",
                e,
            )
        } catch (e: LinkageError) {
            throw IllegalStateException("The JVM refused the new $className: ${e.javaClass.simpleName}: ${e.message}", e)
        }
        failure.get()?.let { (_, t) ->
            failure.remove()
            throw IllegalStateException("Rewriting $className failed: ${t.message ?: t.javaClass.simpleName}", t)
        }
    }

    internal object Transformer : ClassFileTransformer {
        override fun transform(loader: ClassLoader?, className: String?, classBeingRedefined: Class<*>?, domain: ProtectionDomain?, bytes: ByteArray): ByteArray? {
            val list = className?.let(rewrites::get)?.takeIf { it.isNotEmpty() } ?: return null
            var current = bytes
            for (rewrite in list) {
                if (rewrite.job.isKilled) continue
                try {
                    rewrite.transform(loader, current)?.let { current = it }
                } catch (t: Throwable) {
                    if (classBeingRedefined != null) {
                        // Retransform from add(): reported to the script there.
                        failure.set(rewrite to t)
                    } else {
                        // The class is loading somewhere in the game: leave it alone and tell the script's owner.
                        list.remove(rewrite)
                        rewrite.job.log(ScriptLogs.Level.ERROR, "Rewriting ${rewrite.className} as it loaded failed: ${t.message ?: t.javaClass.simpleName}", t.stackTraceToString(), chat = MessageKind.ERROR)
                    }
                    return null
                }
            }
            return if (current === bytes) null else current
        }
    }
}
