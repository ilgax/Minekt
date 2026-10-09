package dev.ilgax.minekt.script

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
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
        // The embedded compiler isn't designed for concurrent use; compiles are serialized.
        val result = synchronized(lock) { runBlocking { compiler(source, configuration) } }
        // Scripts with @file:Import depend on other files, so only cache self-contained ones.
        if (result is ResultWithDiagnostics.Success && "@file:Import" !in source.text) cache[key] = result.value
        return result
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
