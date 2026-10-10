package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.Job
import dev.ilgax.minekt.script.ScriptCompiler
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.isRegularFile
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.KotlinType
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.api.implicitReceivers
import kotlin.script.experimental.api.providedProperties
import kotlin.script.experimental.jvm.BasicJvmScriptEvaluator
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.time.Duration

// Code that runs code:
//
//   val n = eval("(1..10).sum()") as Int
//   eval("echo(greeting.uppercase())", "greeting" to "hi")        // pass values in by name
//   val ores = runScript("ores", "32")                            // run another script, get its result
//   val miner = startScript("miner"); …; miner.kill()             // or start it and carry on

/**
 * Compile and run [code] as part of this script, and return its last expression (`Unit` if
 * there is none). The whole API is in scope, and handlers it registers belong to this script.
 * [variables] are available in the code by name, typed by their values.
 * A compile error throws [IllegalArgumentException]; an exception in the code is rethrown.
 */
fun Minekt.eval(code: String, vararg variables: Pair<String, Any?>): Any? {
    job.checkAlive()
    val values = variables.toMap()
    values.keys.forEach { require(it.matches(IDENTIFIER)) { "'$it' can't be a variable name" } }
    // Variables become typed vals, declared in one line after any imports; error lines are shifted back.
    val lines = code.lines()
    val header = lines.indexOfFirst { line -> line.isNotBlank() && !line.trimStart().let { it.startsWith("import ") || it.startsWith("@file:") } }
        .let { if (it < 0) lines.size else it }
    val declarations = values.entries.joinToString("; ") { (name, value) -> "val $name = $EVAL_VARIABLES.get(\"$name\") as ${kotlinType(value)}" }
    val source = if (values.isEmpty()) code else (lines.take(header) + declarations + lines.drop(header)).joinToString("\n")
    fun userLine(line: Int) = if (values.isNotEmpty() && line > header + 1) line - 1 else line
    val compiled = when (val result = ScriptCompiler.compileEval(source) { if (values.isNotEmpty()) providedProperties(EVAL_VARIABLES to KotlinType(EvalVariables::class)) }) {
        is ResultWithDiagnostics.Success -> ScriptCompiler.instrumented(result.value)
        is ResultWithDiagnostics.Failure -> {
            val errors = result.reports.filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
            throw IllegalArgumentException(
                "eval didn't compile: " + errors.take(3).joinToString("; ") { d ->
                    d.location?.start?.let { "line ${userLine(it.line)}:${it.col} " }.orEmpty() + d.message
                },
            )
        }
    }
    val api = this
    val evaluation = ScriptEvaluationConfiguration {
        implicitReceivers(api)
        if (values.isNotEmpty()) providedProperties(EVAL_VARIABLES to EvalVariables(values))
        jvm { baseClassLoader(Minekt::class.java.classLoader) }
    }
    return when (val result = runBlocking { BasicJvmScriptEvaluator()(compiled, evaluation) }) {
        is ResultWithDiagnostics.Failure -> throw IllegalStateException("eval failed: " + result.reports.joinToString("; ") { it.message })
        is ResultWithDiagnostics.Success -> when (val value = result.value.returnValue) {
            is ResultValue.Value -> value.value
            is ResultValue.Error -> throw value.error
            else -> Unit
        }
    }
}

/** The values passed to [eval], read by the declarations it adds in front of the code. */
class EvalVariables internal constructor(private val values: Map<String, Any?>) {
    fun get(name: String): Any? = values[name]
}

private const val EVAL_VARIABLES = "__minektEvalVariables"

/** A script started by [startScript]. */
class ScriptRun internal constructor(private val caller: Job, private val started: Job) {
    val id: Int get() = started.id
    val name: String get() = started.name
    /** Until it ends: its body may be done while it keeps running for its handlers. */
    val isRunning: Boolean get() = started.state != Job.State.DONE

    /**
     * Wait until the script's body has finished and return what it evaluated to (its last
     * expression). Throws if it didn't compile, failed, or was killed first, or after [timeout].
     */
    fun await(timeout: Duration = Duration.INFINITE): Any? {
        val deadline = if (timeout == Duration.INFINITE) Long.MAX_VALUE else System.currentTimeMillis() + timeout.inWholeMilliseconds
        while (true) {
            caller.checkAlive()
            val waitMs = minOf(100L, deadline - System.currentTimeMillis())
            if (waitMs <= 0) throw TimeoutException("$name didn't finish within $timeout")
            try {
                return started.result.get(waitMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                continue
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    /** Stop it, like `\kill`. */
    fun kill() {
        started.kill()
    }

    override fun toString() = "ScriptRun($id $name)"
}

/**
 * Start `minekt/<name>.kts` with [args] as a script of its own, like typing `\name args`, and
 * return right away. It runs in parallel and stays running after this script ends.
 */
fun Minekt.startScript(name: String, vararg args: String): ScriptRun {
    job.checkAlive()
    require(job.host.scriptDir.resolve("$name.kts").isRegularFile()) { "No script named '$name'" }
    val started = job.jobs.runScript(name, args.toList()) ?: error("Couldn't start $name")
    return ScriptRun(job, started)
}

/** [startScript] and wait for its result: what its body evaluated to (see [ScriptRun.await]). */
fun Minekt.runScript(name: String, vararg args: String, timeout: Duration = Duration.INFINITE): Any? =
    startScript(name, *args).await(timeout)

private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

/** The Kotlin type an eval variable is declared with: collections by their interface, other classes by name if eval can see them. */
private fun kotlinType(value: Any?): String = when (value) {
    null -> "kotlin.Any?"
    is String -> "kotlin.String"
    is Int -> "kotlin.Int"
    is Long -> "kotlin.Long"
    is Double -> "kotlin.Double"
    is Float -> "kotlin.Float"
    is Boolean -> "kotlin.Boolean"
    is Short -> "kotlin.Short"
    is Byte -> "kotlin.Byte"
    is Char -> "kotlin.Char"
    is List<*> -> "kotlin.collections.List<kotlin.Any?>"
    is Set<*> -> "kotlin.collections.Set<kotlin.Any?>"
    is Map<*, *> -> "kotlin.collections.Map<kotlin.Any?, kotlin.Any?>"
    is Function<*> -> "kotlin.Any"
    else -> {
        // Classes defined in a script live in that script's own class loader: eval can't name them.
        val cls = value.javaClass
        val visible = !cls.isAnonymousClass && runCatching { Class.forName(cls.name, false, Minekt::class.java.classLoader) == cls }.getOrDefault(false)
        val stars = cls.typeParameters.joinToString(", ", "<", ">") { "*" }.takeIf { cls.typeParameters.isNotEmpty() }.orEmpty()
        if (visible) cls.name.replace('$', '.') + stars else "kotlin.Any"
    }
}
