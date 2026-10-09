package dev.ilgax.minekt.job

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.management.ManagementFactory

/**
 * Makes every script stoppable, even one stuck in `while (true) {}` that never calls the API.
 *
 * Java can't force-stop a thread any more, so the stop has to come from inside: [instrument]
 * rewrites each compiled script class so that every loop (each backward jump) calls [check] first.
 * [check] throws [JobKilledException] on the script's own threads once its job was killed. Script
 * code running on the game thread (`game { }` blocks, chat filters, screen text) is not checked.
 *
 * Code the script calls into (the game, other mods, Java libraries) isn't rewritten: a script
 * stuck inside one of those calls stops when the call returns.
 */
object Watchdog {
    private val CHECK_OWNER = Watchdog::class.java.name.replace('.', '/')

    /** Script code running on a job's own threads. */
    class JobThread(val job: Job, runnable: Runnable, name: String) : Thread(runnable, name)

    /** Called by instrumented script code at every loop iteration. Keep it cheap. */
    @JvmStatic
    fun check() {
        val thread = Thread.currentThread()
        if (thread is JobThread && thread.job.isKilled) throw JobKilledException()
    }

    // ---- bytecode rewriting -------------------------------------------------------------------

    /** Rewrite every `.class` in a compiled script module; other entries are kept as they are. */
    fun instrument(files: Map<String, ByteArray>): Map<String, ByteArray> =
        files.mapValues { (name, bytes) -> if (name.endsWith(".class")) instrument(bytes) else bytes }

    fun instrument(classBytes: ByteArray): ByteArray {
        val reader = ClassReader(classBytes)
        // Inserting a no-argument static call needs no new stack-map frames, only recomputed maxs.
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    LoopCheckInserter(super.visitMethod(access, name, descriptor, signature, exceptions))
            },
            0,
        )
        return writer.toByteArray()
    }

    /** Inserts a [check] call before each jump to a label that was already visited (a loop's back edge). */
    private class LoopCheckInserter(next: MethodVisitor) : MethodVisitor(Opcodes.ASM9, next) {
        private val seen = HashSet<Label>()

        override fun visitLabel(label: Label) {
            seen += label
            super.visitLabel(label)
        }

        override fun visitJumpInsn(opcode: Int, label: Label) {
            if (label in seen) insertCheck()
            super.visitJumpInsn(opcode, label)
        }

        override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label, vararg labels: Label) {
            if (dflt in seen || labels.any { it in seen }) insertCheck()
            super.visitTableSwitchInsn(min, max, dflt, *labels)
        }

        override fun visitLookupSwitchInsn(dflt: Label, keys: IntArray, labels: Array<out Label>) {
            if (dflt in seen || labels.any { it in seen }) insertCheck()
            super.visitLookupSwitchInsn(dflt, keys, labels)
        }

        private fun insertCheck() = super.visitMethodInsn(Opcodes.INVOKESTATIC, CHECK_OWNER, "check", "()V", false)
    }

    // ---- CPU use ------------------------------------------------------------------------------

    private val cpu = ManagementFactory.getThreadMXBean()

    /** CPU time used so far by [threads] in nanoseconds, or null if the JVM can't tell. */
    fun cpuTime(threads: Collection<Thread>): Long? {
        if (!cpu.isThreadCpuTimeSupported) return null
        return threads.sumOf { thread -> cpu.getThreadCpuTime(thread.threadId()).coerceAtLeast(0) }
    }
}
