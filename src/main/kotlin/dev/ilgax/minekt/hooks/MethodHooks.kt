package dev.ilgax.minekt.hooks

import dev.ilgax.minekt.api.HookCall
import dev.ilgax.minekt.job.Job
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.commons.AdviceAdapter
import org.objectweb.asm.commons.Method as AsmMethod
import java.lang.instrument.ClassFileTransformer
import java.lang.instrument.Instrumentation
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.security.ProtectionDomain
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lets scripts run code before or after any method of an already-loaded class.
 *
 * A Java agent is attached at runtime (the first time a hook is added) to get [Instrumentation].
 * Hooked methods are rewritten to call [enter] on entry and [exit] before each return; removing the
 * last hook of a class restores its original code. Hook handlers run on whatever thread called the
 * method, before it continues, so they can change arguments, the result, or skip the method.
 */
object MethodHooks {
    /** Returned by [enter] when the method should run normally. */
    @JvmField
    val CONTINUE = Any()

    private class Handler(val job: Job, val after: Boolean, val run: (HookCall) -> Unit)

    private class Entry(val method: Method) {
        val handlers = CopyOnWriteArrayList<Handler>()
    }

    /** Hook id → entry. Ids are baked into rewritten bytecode and never reused. */
    private val entries = ConcurrentHashMap<Int, Entry>()
    /** Internal class name → (method name + descriptor → hook id), for the transformer. */
    private val hooked = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()
    private val failures = ConcurrentHashMap<String, Throwable>()
    private var nextId = 0

    /** Nested hooked calls made by a handler itself don't run handlers again. */
    private val inHandler = ThreadLocal.withInitial { false }

    private val instrumentation: Instrumentation get() = Agent.instrumentation

    // ---- adding and removing ------------------------------------------------------------------

    fun add(job: Job, method: Method, after: Boolean, run: (HookCall) -> Unit): () -> Unit {
        require(!Modifier.isAbstract(method.modifiers) && !Modifier.isNative(method.modifiers)) {
            "${describe(method)} has no code to hook (abstract or native); hook a class that implements it"
        }
        val owner = method.declaringClass
        requireNotNull(owner.classLoader) { "JDK classes can't be hooked" }
        val handler = Handler(job, after, run)
        synchronized(this) {
            val key = method.name + Type.getMethodDescriptor(method)
            val internalName = Type.getInternalName(owner)
            val methods = hooked.computeIfAbsent(internalName) { ConcurrentHashMap() }
            val id = methods[key]
            if (id != null) {
                entries.getValue(id).handlers += handler
            } else {
                check(instrumentation.isModifiableClass(owner)) { "${owner.name} can't be changed at runtime" }
                val newId = nextId++
                entries[newId] = Entry(method).also { it.handlers += handler }
                methods[key] = newId
                try {
                    retransform(owner)
                } catch (t: Throwable) {
                    methods.remove(key)
                    entries.remove(newId)
                    throw t
                }
            }
        }
        return { remove(method, handler) }
    }

    fun hasHooks(job: Job): Boolean = entries.values.any { entry -> entry.handlers.any { it.job === job } }

    /** Remove every hook [job] added (when it ends). */
    fun removeAll(job: Job) {
        synchronized(this) {
            for ((_, entry) in entries) {
                entry.handlers.filter { it.job === job }.forEach { remove(entry.method, it) }
            }
        }
    }

    private fun remove(method: Method, handler: Handler) = synchronized(this) {
        val internalName = Type.getInternalName(method.declaringClass)
        val methods = hooked[internalName] ?: return@synchronized
        val key = method.name + Type.getMethodDescriptor(method)
        val id = methods[key] ?: return@synchronized
        val entry = entries.getValue(id)
        entry.handlers -= handler
        if (entry.handlers.isEmpty()) {
            // Last hook on this method: put its original code back.
            methods.remove(key)
            entries.remove(id)
            runCatching { retransform(method.declaringClass) }
        }
    }

    private fun retransform(owner: Class<*>) {
        val internalName = Type.getInternalName(owner)
        failures.remove(internalName)
        instrumentation.retransformClasses(owner)
        failures.remove(internalName)?.let { throw IllegalStateException("Couldn't hook ${owner.name}: ${it.message}", it) }
    }

    // ---- called from rewritten methods --------------------------------------------------------

    @JvmStatic
    fun enter(id: Int, self: Any?, args: Array<Any?>): Any? {
        val entry = entries[id] ?: return CONTINUE
        if (entry.handlers.none { !it.after } || inHandler.get()) return CONTINUE
        val call = HookCall(self, args, entry.method)
        run(entry, after = false, call)
        // Let handlers put any number in a parameter: convert to what the method expects.
        entry.method.parameterTypes.forEachIndexed { i, type -> args[i] = coerce(args[i], type) }
        return if (call.cancelled) coerce(call.result, entry.method.returnType) else CONTINUE
    }

    @JvmStatic
    fun exit(result: Any?, id: Int, self: Any?, args: Array<Any?>): Any? {
        val entry = entries[id] ?: return result
        if (entry.handlers.none { it.after } || inHandler.get()) return result
        val call = HookCall(self, args, entry.method)
        call.result = result
        run(entry, after = true, call)
        return coerce(call.result, entry.method.returnType)
    }

    private fun run(entry: Entry, after: Boolean, call: HookCall) {
        inHandler.set(true)
        try {
            for (handler in entry.handlers) {
                if (handler.after != after || handler.job.isKilled) continue
                try {
                    handler.run(call)
                } catch (t: Throwable) {
                    // A broken hook must not break the game: drop it and tell the player.
                    Thread { remove(entry.method, handler) }.start()
                    handler.job.handleThrowable(t)
                }
            }
        } finally {
            inHandler.set(false)
        }
    }

    /** [value] as something [type] accepts: numbers are converted, null becomes 0/false for primitives. */
    internal fun coerce(value: Any?, type: Class<*>): Any? {
        if (!type.isPrimitive) return value
        return when (type) {
            Void.TYPE -> null
            java.lang.Boolean.TYPE -> value as? Boolean ?: false
            Character.TYPE -> value as? Char ?: (value as? Number)?.toInt()?.toChar() ?: '\u0000'
            else -> {
                val n = value as? Number ?: 0
                when (type) {
                    Integer.TYPE -> n.toInt()
                    java.lang.Long.TYPE -> n.toLong()
                    java.lang.Float.TYPE -> n.toFloat()
                    java.lang.Double.TYPE -> n.toDouble()
                    java.lang.Short.TYPE -> n.toShort()
                    java.lang.Byte.TYPE -> n.toByte()
                    else -> value
                }
            }
        }
    }

    fun describe(method: Method) = "${method.declaringClass.simpleName}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"

    // ---- bytecode -----------------------------------------------------------------------------

    internal object Transformer : ClassFileTransformer {
        override fun transform(loader: ClassLoader?, className: String?, classBeingRedefined: Class<*>?, domain: ProtectionDomain?, bytes: ByteArray): ByteArray? {
            val methods = className?.let(hooked::get)?.takeIf { it.isNotEmpty() } ?: return null
            return try {
                rewrite(bytes, loader, methods)
            } catch (t: Throwable) {
                failures[className] = t
                null
            }
        }
    }

    private val DISPATCH: Type = Type.getType(MethodHooks::class.java)
    private val OBJECT: Type = Type.getType(Any::class.java)
    private val OBJECT_ARRAY: Type = Type.getType(Array<Any>::class.java)

    private fun rewrite(bytes: ByteArray, loader: ClassLoader?, methods: Map<String, Int>): ByteArray {
        val reader = ClassReader(bytes)
        val writer = object : ClassWriter(reader, COMPUTE_FRAMES) {
            override fun getClassLoader(): ClassLoader = loader ?: super.getClassLoader()
        }
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                    val id = methods[name + descriptor] ?: return next
                    return HookAdapter(next, access, name, descriptor, id)
                }
            },
            ClassReader.SKIP_FRAMES,
        )
        return writer.toByteArray()
    }

    /** `enter` at the start (maybe returning early), arguments written back, `exit` before each return. */
    private class HookAdapter(next: MethodVisitor, access: Int, name: String, descriptor: String, private val id: Int) :
        AdviceAdapter(Opcodes.ASM9, next, access, name, descriptor) {
        private val static = access and Opcodes.ACC_STATIC != 0
        private val returns = Type.getReturnType(descriptor)
        private val params = Type.getArgumentTypes(descriptor)
        private var argsLocal = -1

        private fun loadSelf() = if (static) visitInsn(Opcodes.ACONST_NULL) else loadThis()

        override fun onMethodEnter() {
            loadArgArray()
            argsLocal = newLocal(OBJECT_ARRAY)
            storeLocal(argsLocal)
            push(id)
            loadSelf()
            loadLocal(argsLocal)
            invokeStatic(DISPATCH, AsmMethod("enter", "(ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))
            dup()
            getStatic(DISPATCH, "CONTINUE", OBJECT)
            val proceed = newLabel()
            ifCmp(OBJECT, EQ, proceed)
            // Cancelled: return the handler's value. Written to the next visitor so it skips onMethodExit.
            if (returns.sort == Type.VOID) pop() else unbox(returns)
            mv.visitInsn(returns.getOpcode(Opcodes.IRETURN))
            mark(proceed)
            pop()
            params.forEachIndexed { i, type ->
                loadLocal(argsLocal)
                push(i)
                arrayLoad(OBJECT)
                unbox(type)
                storeArg(i)
            }
        }

        override fun onMethodExit(opcode: Int) {
            if (opcode == Opcodes.ATHROW) return
            if (returns.sort == Type.VOID) visitInsn(Opcodes.ACONST_NULL) else box(returns)
            push(id)
            loadSelf()
            loadLocal(argsLocal)
            invokeStatic(DISPATCH, AsmMethod("exit", "(Ljava/lang/Object;ILjava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"))
            if (returns.sort == Type.VOID) pop() else unbox(returns)
        }
    }
}
