package dev.ilgax.minekt.hooks

import net.bytebuddy.agent.ByteBuddyAgent
import java.lang.instrument.Instrumentation

/**
 * The Java agent behind method hooks and class rewrites, attached the first time either is used.
 * Script rewrites are registered first, so method hooks wrap the rewritten code.
 */
internal object Agent {
    val instrumentation: Instrumentation by lazy {
        val inst = try {
            ByteBuddyAgent.install()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Changing game code at runtime needs to attach a Java agent, and this Java runtime doesn't allow it (${e.message}). " +
                    "Running Minecraft on a full JDK usually fixes this.",
                e,
            )
        }
        check(inst.isRetransformClassesSupported) { "This Java runtime can't change loaded classes" }
        inst.addTransformer(ClassRewrites.Transformer, true)
        inst.addTransformer(MethodHooks.Transformer, true)
        inst
    }
}
