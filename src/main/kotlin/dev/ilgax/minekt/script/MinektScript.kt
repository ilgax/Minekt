package dev.ilgax.minekt.script

import dev.ilgax.minekt.api.Minekt
import dev.ilgax.minekt.api.server.ServerMinekt
import kotlin.reflect.KClass
import java.io.File
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.dependencies.DependsOn
import kotlin.script.experimental.dependencies.Repository
import kotlin.script.experimental.api.*
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvm.updateClasspath

/**
 * Script template: every `.kts` run by Minekt is compiled as a subclass of this, with a [Minekt]
 * instance as implicit receiver so `echo(...)`, `player`, `baritone` etc. are in scope.
 */
@KotlinScript(
    displayName = "Minekt script",
    fileExtension = "kts",
    compilationConfiguration = MinektCompilationConfiguration::class,
)
abstract class MinektScript

/** `@file:Import("lib/util.kts")` pulls another script's declarations into this one. */
@Target(AnnotationTarget.FILE)
@Repeatable
@Retention(AnnotationRetention.SOURCE)
annotation class Import(vararg val paths: String)

class MinektCompilationConfiguration : ScriptCompilationConfiguration({ minektScript(Minekt::class) })

/**
 * Template of server scripts: like [MinektScript], with a [ServerMinekt] as implicit receiver. Only the
 * helpers that work on both sides (and the server API) are in scope, so client calls don't compile.
 */
@KotlinScript(
    displayName = "Minekt server script",
    fileExtension = "kts",
    compilationConfiguration = MinektServerCompilationConfiguration::class,
)
abstract class MinektServerScript

class MinektServerCompilationConfiguration : ScriptCompilationConfiguration({
    minektScript(ServerMinekt::class, "dev.ilgax.minekt.api.server.*")
})

/** What client and server scripts share: imports, `@file:Import`, `@file:DependsOn`, the classpath. */
private fun ScriptCompilationConfiguration.Builder.minektScript(receiver: KClass<*>, vararg extraImports: String) {
    implicitReceivers(receiver)
    defaultImports(
        "dev.ilgax.minekt.api.*",
        *extraImports,
        "net.minecraft.world.phys.Vec3",
        "net.minecraft.core.BlockPos",
        "kotlin.math.*",
        "kotlinx.coroutines.*",
        "kotlin.time.Duration",
        "kotlin.time.Duration.Companion.milliseconds",
        "kotlin.time.Duration.Companion.seconds",
        "kotlin.time.Duration.Companion.minutes",
        "kotlin.time.Duration.Companion.hours",
    )
    defaultImports(Import::class, DependsOn::class, Repository::class)
    // Same bytecode target as the mod, so scripts can inline Minekt's inline helpers.
    compilerOptions("-jvm-target", "25")
    jvm {
        val classpath = ScriptClasspath.files
        if (classpath.isEmpty()) dependenciesFromCurrentContext(wholeClasspath = true)
        else updateClasspath(classpath)
    }
    refineConfiguration {
        onAnnotations(Import::class, handler = ::resolveImports)
        onAnnotations(DependsOn::class, Repository::class, handler = ScriptDependencies::refine)
    }
    ide { acceptedLocations(ScriptAcceptedLocation.Everywhere) }
}

private fun resolveImports(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
    // Matched by name, not `is Import`: under Fabric the compiler may instantiate the annotation
    // through a different class loader than the one that loaded this file.
    val paths = context.collectedData?.get(ScriptCollectedData.collectedAnnotations)
        ?.map { it.annotation }
        ?.filter { it.annotationClass.java.name == Import::class.java.name }
        ?.flatMap { (it.javaClass.getMethod("paths").invoke(it) as Array<*>).map(Any?::toString) }
        ?: return context.compilationConfiguration.asSuccess()
    val baseDir = (context.script as? FileBasedScriptSource)?.file?.parentFile
        ?: ScriptClasspath.scriptDir?.toFile()
    val sources = paths.map { path ->
        val file = File(path).takeIf { it.isAbsolute } ?: File(baseDir, path)
        if (!file.isFile) {
            return makeFailureResult("Imported script not found: $path".asErrorDiagnostics(path = context.script.locationId))
        }
        FileScriptSource(file)
    }
    return ScriptCompilationConfiguration(context.compilationConfiguration) {
        importScripts.append(sources)
    }.asSuccess()
}
