package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.script.ScriptClassLoader
import net.fabricmc.fabric.api.event.Event
import net.minecraft.resources.Identifier
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Fabric API events from scripts (experimental, opted into with `@file:OptIn(ExperimentalFabricEvents::class)`).
 *
 * A script writes `ClientTickEvents.END_CLIENT_TICK.register { … }` like a mod. [rewrite] turns those
 * calls in the compiled script into calls to [register] here, which registers a stand-in listener
 * instead: it runs the script's listener while the script runs (errors go to the script, not the
 * game) and is taken out of the event when the script ends. Fabric has no way to unregister, so
 * that edits the event's internal listener arrays; if those ever change, the stand-in stays
 * registered but does nothing.
 */
object FabricEvents {
    private val logger = LoggerFactory.getLogger("minekt")
    private const val EVENT = "net/fabricmc/fabric/api/event/Event"
    private val SELF = FabricEvents::class.java.name.replace('.', '/')
    private const val OPT_IN_MESSAGE =
        "Fabric events from scripts are experimental: add @file:OptIn(ExperimentalFabricEvents::class) at the top to use them"

    // ---- called by rewritten script code ---------------------------------------------------------

    @JvmStatic
    fun register(event: Event<Any>, listener: Any, caller: Class<*>) = register(event, null, listener, caller)

    @JvmStatic
    fun register(event: Event<Any>, phase: Identifier?, listener: Any, caller: Class<*>) {
        val job = (caller.classLoader as? ScriptClassLoader)?.job
        if (job == null) {
            // Not from a script run (shouldn't happen): behave like Fabric.
            if (phase == null) event.register(listener) else event.register(phase, listener)
            return
        }
        check(job.fabricEventsOptIn) { OPT_IN_MESSAGE }
        job.checkAlive()
        val type = listenerType(listener)
        val handler = StandIn(job, listener)
        val standIn = Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler)
        if (phase == null) event.register(standIn) else event.register(phase, standIn)
        job.cleanups += {
            handler.target = null
            if (!remove(event, standIn)) logger.warn("Couldn't take a listener of {} out of a Fabric event; it stays, doing nothing", job.name)
        }
    }

    /** The listener's callback interface: the one interface with a single abstract method. */
    private fun listenerType(listener: Any): Class<*> =
        generateSequence<Class<*>>(listener.javaClass) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .firstOrNull { type -> type.methods.count { Modifier.isAbstract(it.modifiers) } == 1 }
            ?: throw IllegalArgumentException("${listener.javaClass.name} isn't a Fabric event listener")

    /** Runs the script's listener while its script runs. One that throws is shown as an error and switched off. */
    private class StandIn(private val job: Job, @Volatile var target: Any?) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            when (method.name) {
                "equals" -> if (method.parameterCount == 1) return proxy === args!![0]
                "hashCode" -> if (method.parameterCount == 0) return System.identityHashCode(proxy)
                "toString" -> if (method.parameterCount == 0) return "Minekt listener of ${job.name}"
            }
            val target = target
            if (target != null && method.declaringClass.isInstance(target)) {
                try {
                    return method.invoke(target, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    this.target = null
                    job.handleThrowable(e.cause ?: e)
                } catch (e: Exception) {
                    this.target = null
                    job.handleThrowable(e)
                }
            }
            return neutral(method, args)
        }
    }

    /**
     * What a listener that does nothing returns: nothing, "allow" for `Boolean` events, `PASS` for
     * interaction results, and an unchanged argument for events that modify a value.
     */
    internal fun neutral(method: Method, args: Array<out Any?>?): Any? {
        val type = method.returnType
        return when {
            type == Void.TYPE -> null
            type == java.lang.Boolean.TYPE || type == java.lang.Boolean::class.java -> true
            type.name == "net.minecraft.world.InteractionResult" -> type.getField("PASS").get(null)
            type.isPrimitive -> when (type) {
                Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Byte.TYPE -> 0.toByte()
                Character.TYPE -> 0.toChar()
                else -> null
            }
            else -> args?.firstOrNull { it != null && type.isInstance(it) }
        }
    }

    /** Take [listener] out of [event]'s listeners (Fabric's ArrayBackedEvent internals). */
    internal fun remove(event: Event<*>, listener: Any): Boolean = try {
        val lock = field(event.javaClass, "lock").get(event)
        synchronized(lock) {
            @Suppress("UNCHECKED_CAST")
            val phases = field(event.javaClass, "phases").get(event) as Map<Any, Any>
            var total = 0
            for (phase in phases.values) {
                val listenersField = field(phase.javaClass, "listeners")
                val listeners = listenersField.get(phase) as Array<*>
                val kept = listeners.filter { it !== listener }
                val array = java.lang.reflect.Array.newInstance(listeners.javaClass.componentType, kept.size)
                kept.forEachIndexed { i, it -> java.lang.reflect.Array.set(array, i, it) }
                listenersField.set(phase, array)
                total += kept.size
            }
            event.javaClass.getDeclaredMethod("rebuildInvoker", Integer.TYPE).apply { isAccessible = true }.invoke(event, total)
        }
        true
    } catch (e: Exception) {
        logger.debug("Fabric event removal failed", e)
        false
    }

    private fun field(type: Class<*>, name: String) =
        generateSequence(type) { it.superclass }.firstNotNullOf { c -> c.declaredFields.firstOrNull { it.name == name } }.apply { isAccessible = true }

    // ---- rewriting script bytecode -----------------------------------------------------------------

    /** Point every `Event.register(…)` call in a compiled script at [register]. Other entries are kept. */
    fun rewrite(files: Map<String, ByteArray>): Map<String, ByteArray> =
        files.mapValues { (name, bytes) -> if (name.endsWith(".class")) rewrite(bytes) else bytes }

    fun rewrite(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        // Cheap check first: most script classes never mention Fabric events.
        if (!String(classBytes, Charsets.ISO_8859_1).contains(EVENT)) return classBytes
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                private lateinit var owner: String

                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    owner = name
                    super.visit(version, access, name, signature, superName, interfaces)
                }

                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
                        override fun visitMethodInsn(opcode: Int, insnOwner: String, insnName: String, insnDescriptor: String, isInterface: Boolean) {
                            if (opcode == Opcodes.INVOKEVIRTUAL && insnOwner == EVENT && insnName == "register") {
                                // Same arguments, plus the calling class (to find the script run it belongs to).
                                super.visitLdcInsn(Type.getObjectType(owner))
                                val descriptor = when (insnDescriptor) {
                                    "(Ljava/lang/Object;)V" -> "(L$EVENT;Ljava/lang/Object;Ljava/lang/Class;)V"
                                    else -> "(L$EVENT;Lnet/minecraft/resources/Identifier;Ljava/lang/Object;Ljava/lang/Class;)V"
                                }
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, SELF, "register", descriptor, false)
                                return
                            }
                            super.visitMethodInsn(opcode, insnOwner, insnName, insnDescriptor, isInterface)
                        }
                    }
            },
            0,
        )
        return writer.toByteArray()
    }
}
