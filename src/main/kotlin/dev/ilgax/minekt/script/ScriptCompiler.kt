package dev.ilgax.minekt.script

import dev.ilgax.minekt.hooks.FabricEvents
import dev.ilgax.minekt.job.Watchdog
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.ScriptingHostConfiguration
import kotlin.script.experimental.host.createCompilationConfigurationFromTemplate
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.defaultJvmScriptingHostConfiguration
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.impl.KJvmCompiledModule
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript
import kotlin.script.experimental.jvmhost.JvmScriptCompiler
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KClass

/** Compiles client scripts, against [MinektScript]. */
object ScriptCompiler : TemplateCompiler(MinektScript::class)

/** Compiles server scripts, against [MinektServerScript]. */
object ServerScriptCompiler : TemplateCompiler(MinektServerScript::class)

/** The embedded compiler isn't designed for concurrent use: compiles are serialized, whatever the template. */
private val compileLock = Any()

/** Compiles scripts against a script [template], caching results by source content. */
open class TemplateCompiler(private val template: KClass<*>) {
    /**
     * The default host takes its class loader from whichever thread first touched it, which under Fabric
     * can be none. The compiler then can't load `@file:Import`/`@DependsOn` and silently ignores them.
     */
    private val host by lazy {
        ScriptingHostConfiguration(defaultJvmScriptingHostConfiguration) {
            jvm { baseClassLoader(template.java.classLoader) }
        }
    }
    private val configuration by lazy {
        createCompilationConfigurationFromTemplate(KotlinType(template), host, template)
    }
    private val compiler by lazy { JvmScriptCompiler(host) }
    private val cache = ConcurrentHashMap<String, CompiledScript>()

    fun compile(source: SourceCode): ResultWithDiagnostics<CompiledScript> = compile(source, configuration, cacheKey(source))

    /** Compile code for `eval`: like a script, plus whatever [configure] adds (e.g. provided properties). */
    fun compileEval(code: String, configure: ScriptCompilationConfiguration.Builder.() -> Unit): ResultWithDiagnostics<CompiledScript> {
        val source = code.toScriptSource("eval.kts")
        val config = ScriptCompilationConfiguration(configuration, body = configure)
        // Evals are often generated on the fly: keep the cache from growing without bound.
        if (cache.size > MAX_CACHED) cache.clear()
        return compile(source, config, "eval+" + cacheKey(source))
    }

    private fun compile(source: SourceCode, config: ScriptCompilationConfiguration, key: String): ResultWithDiagnostics<CompiledScript> {
        cache[key]?.let { return it.asSuccess() }
        CompilerSetup.awaitReady()
        val result = synchronized(compileLock) { runBlocking { compiler(source, config) } }
        // Scripts with @file:Import depend on other files, so only cache self-contained ones.
        if (result is ResultWithDiagnostics.Success && "@file:Import" !in source.text) cache[key] = result.value
        return result
    }

    private val instrumented = Collections.synchronizedMap(WeakHashMap<CompiledScript, Map<String, ByteArray>>())

    /**
     * [compiled] with every class rewritten by [Watchdog] so each run can always be stopped (and its
     * Fabric event registrations pointed at [FabricEvents]). The
     * rewrite is done once per compiled script; each run still gets its own class loader.
     */
    fun instrumented(compiled: CompiledScript): CompiledScript {
        val script = compiled as KJvmCompiledScript
        val classes = instrumented.getOrPut(compiled) {
            val module = script.getCompiledModule() as? KJvmCompiledModuleInMemory ?: error("Unexpected compiled script format")
            FabricEvents.rewrite(Watchdog.instrument(module.compilerOutputFiles))
        }
        val module = object : KJvmCompiledModule {
            override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader =
                ScriptClassLoader(classes, baseClassLoader ?: TemplateCompiler::class.java.classLoader)
        }
        return KJvmCompiledScript(script.sourceLocationId, script.compilationConfiguration, script.scriptClassFQName, script.resultField, script.otherScripts, module)
    }

    /**
     * Compile a trivial script so the first real one doesn't pay the compiler's startup cost. Throws when it
     * doesn't compile: the compiler reports failures as a result, not an exception.
     */
    fun warmUp() {
        val result = compile(warmUpCode.toScriptSource("warmup.kts"))
        if (result is ResultWithDiagnostics.Failure) {
            val errors = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }.ifEmpty { result.reports }
            error("The warm-up script didn't compile: " + errors.joinToString("; ") { it.message })
        }
    }

    /** The script [warmUp] compiles. */
    protected open val warmUpCode = "Unit"

    fun clearCache() = cache.clear()

    private fun cacheKey(source: SourceCode): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(source.text.toByteArray())
        return "${source.name}:${digest.joinToString("") { "%02x".format(it) }}"
    }

    private companion object {
        const val MAX_CACHED = 500
    }
}
