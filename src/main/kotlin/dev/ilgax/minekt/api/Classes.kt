package dev.ilgax.minekt.api

import dev.ilgax.minekt.hooks.ClassRewrites
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode

// Replacing the bytecode of any class, while the game runs:
//
//   transformClass("net.minecraft.client.player.LocalPlayer") { bytes -> myNewBytes(bytes) }
//   rewriteClass<LivingEntity> { node ->                          // ASM's tree API (org.objectweb.asm.tree)
//       val method = node.methods.first { it.name == "getSpeed" }
//       method.instructions.clear(); method.tryCatchBlocks.clear()
//       method.instructions.add(LdcInsnNode(0.5f)); method.instructions.add(InsnNode(FRETURN))
//   }
//
// Nothing is checked for you: the bytes you return are what the JVM gets.

/** A rewrite added by [transformClass] or [rewriteClass]; [remove] puts the original code back. */
class ClassRewrite internal constructor(private val rewrite: ClassRewrites.Rewrite) {
    val className: String get() = rewrite.className
    fun remove() = ClassRewrites.remove(rewrite)
}

/**
 * Replace the bytecode of [className] (`a.b.C`, `a.b.C$Inner`). [transform] gets the class's bytes
 * as defined (after Fabric's and mixins' changes, and earlier rewrites by any script) and returns the
 * new bytes, or null to leave them unchanged.
 *
 * A loaded class changes right away, and an error in [transform] or bytes the JVM refuses throw
 * here. A loaded class can only change method bodies: the JVM doesn't allow adding, removing or
 * renaming methods or fields. A class that isn't loaded yet changes when it loads (then [transform]
 * runs on the thread that loads it, and anything is allowed). The rewrite stays until [ClassRewrite.remove]
 * or the script ends, and keeps the script alive meanwhile. Method hooks still work on rewritten
 * methods: they wrap the new code.
 */
fun Minekt.transformClass(className: String, transform: (bytes: ByteArray) -> ByteArray?): ClassRewrite {
    job.checkAlive()
    return ClassRewrite(ClassRewrites.add(job, className) { _, bytes -> transform(bytes) })
}

fun Minekt.transformClass(target: Class<*>, transform: (bytes: ByteArray) -> ByteArray?): ClassRewrite = transformClass(target.name, transform)

inline fun <reified T> Minekt.transformClass(noinline transform: (bytes: ByteArray) -> ByteArray?): ClassRewrite = transformClass(T::class.java, transform)

/**
 * [transformClass] with ASM's tree API: change [ClassNode] in place. It's written back with stack
 * map frames computed for you (that loads the classes your code mentions, to find common superclasses).
 */
fun Minekt.rewriteClass(className: String, rewrite: (ClassNode) -> Unit): ClassRewrite {
    job.checkAlive()
    return ClassRewrite(
        ClassRewrites.add(job, className) { loader, bytes ->
            val node = ClassNode()
            ClassReader(bytes).accept(node, 0)
            rewrite(node)
            val writer = FrameWriter(loader)
            node.accept(writer)
            writer.toByteArray()
        },
    )
}

fun Minekt.rewriteClass(target: Class<*>, rewrite: (ClassNode) -> Unit): ClassRewrite = rewriteClass(target.name, rewrite)

inline fun <reified T> Minekt.rewriteClass(noinline rewrite: (ClassNode) -> Unit): ClassRewrite = rewriteClass(T::class.java, rewrite)

/**
 * The bytecode of a loaded class as the JVM runs it: after Fabric, mixins and scripts' rewrites
 * (not method hooks). E.g. to read it with ASM, or save it and look at it with `javap`.
 */
fun Minekt.classBytes(className: String): ByteArray {
    job.checkAlive()
    require(ClassRewrites.loaded(className).isNotEmpty()) { "$className isn't loaded" }
    var captured: ByteArray? = null
    val probe = ClassRewrites.add(job, className) { _, bytes -> captured = bytes; null }
    ClassRewrites.remove(probe)
    return checkNotNull(captured) { "Couldn't read $className" }
}

fun Minekt.classBytes(target: Class<*>): ByteArray = classBytes(target.name)

/** COMPUTE_FRAMES, looking classes up through the class's own loader (the game's, for game classes). */
private class FrameWriter(private val loader: ClassLoader?) : ClassWriter(COMPUTE_FRAMES) {
    override fun getClassLoader(): ClassLoader = loader ?: super.getClassLoader()

    override fun getCommonSuperClass(type1: String, type2: String): String = try {
        super.getCommonSuperClass(type1, type2)
    } catch (e: Throwable) {
        // A class that can't be loaded from here. Object is the usual fallback; the verifier may still reject
        // code that needs the real common type.
        "java/lang/Object"
    }
}
