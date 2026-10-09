package dev.ilgax.minekt.script

import dev.ilgax.minekt.job.Watchdog
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.impl.KJvmCompiledModule
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking

/** Compiles scripts against [MinektScript], caching results by source content. */
object ScriptCompiler {
    private val configuration by lazy {
        createCompilationConfigurationFromTemplate(
            KotlinType(MinektScript::class),
            defaultJvmScriptingHostConfiguration,
            MinektScript::class,
        )
    }
    private val compiler by lazy { JvmScriptCompiler(defaultJvmScriptingHostConfiguration) }
    private val cache = ConcurrentHashMap<String, CompiledScript>()
    private val lock = Any()

    fun compile(source: SourceCode): ResultWithDiagnostics<CompiledScript> {
        val key = cacheKey(source)
        cache[key]?.let { return it.asSuccess() }
        CompilerSetup.awaitReady()
        // The embedded compiler isn't designed for concurrent use; compiles are serialized.
        val result = synchronized(lock) { runBlocking { compiler(source, configuration) } }
        // Scripts with @file:Import depend on other files, so only cache self-contained ones.
        if (result is ResultWithDiagnostics.Success && "@file:Import" !in source.text) cache[key] = result.value
        return result
    }

    private val instrumented = Collections.synchronizedMap(WeakHashMap<CompiledScript, Map<String, ByteArray>>())

    /**
     * [compiled] with every class rewritten by [Watchdog] so each run can always be stopped. The
     * rewrite is done once per compiled script; each run still gets its own class loader.
     */
    fun instrumented(compiled: CompiledScript): CompiledScript {
        val script = compiled as KJvmCompiledScript
        val classes = instrumented.getOrPut(compiled) {
            val module = script.getCompiledModule() as? KJvmCompiledModuleInMemory ?: error("Unexpected compiled script format")
            Watchdog.instrument(module.compilerOutputFiles)
        }
        val module = object : KJvmCompiledModule {
            override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader =
                ScriptClassLoader(classes, baseClassLoader ?: ScriptCompiler::class.java.classLoader)
        }
        return KJvmCompiledScript(script.sourceLocationId, script.compilationConfiguration, script.scriptClassFQName, script.resultField, script.otherScripts, module)
    }

    /** Compile a trivial script so the first real one doesn't pay the compiler's startup cost. */
    fun warmUp() {
        compile("Unit".toScriptSource("warmup.kts"))
    }

    fun clearCache() = cache.clear()

    private fun cacheKey(source: SourceCode): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.text.toByteArray())
        return "${source.name}:${digest.joinToString("") { "%02x".format(it) }}"
    }
}
