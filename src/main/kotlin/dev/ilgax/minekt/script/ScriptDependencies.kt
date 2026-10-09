package dev.ilgax.minekt.script

import kotlinx.coroutines.runBlocking
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptCollectedData
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.ScriptConfigurationRefinementContext
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.asSuccess
import kotlin.script.experimental.api.collectedAnnotations
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.api.with
import kotlin.script.experimental.dependencies.CompoundDependenciesResolver
import kotlin.script.experimental.dependencies.ExternalDependenciesResolver
import kotlin.script.experimental.dependencies.FileSystemDependenciesResolver
import kotlin.script.experimental.dependencies.RepositoryCoordinates
import kotlin.script.experimental.jvm.JvmDependency

/**
 * `@file:DependsOn("group:artifact:version")` and `@file:Repository("https://…")`.
 *
 * Libraries are resolved with Kotlin's own Maven resolver (full Maven rules: parent POMs, BOMs,
 * version conflicts, `~/.m2/settings.xml`) into the standard Maven cache, `~/.m2/repository`, and
 * put on that one script's classpath. The resolver itself (a few MB of Maven code) isn't on the mod
 * classpath: the first script that needs it loads it into its own class loader, from the copy in the
 * full jar or, in the small jar, a download of the exact files pinned at build time.
 */
object ScriptDependencies {
    private const val MAVEN_RESOLVER = "kotlin.script.experimental.dependencies.maven.MavenDependenciesResolver"
    private const val DEPENDS_ON = "kotlin.script.experimental.dependencies.DependsOn"
    private const val REPOSITORY = "kotlin.script.experimental.dependencies.Repository"

    /** Told when the resolver has to be downloaded (small jar, first use). */
    @Volatile var onDownload: (String) -> Unit = {}

    /** Where the resolver's own jars are kept; set by the mod (`.minekt/lib`). */
    @Volatile var libDir: Path = Path.of(System.getProperty("java.io.tmpdir"), "minekt-lib")

    /** The resolver's class loader, created on first use. Its parent is the mod's, so it shares the game's libraries. */
    private val mavenLoader: ClassLoader by lazy {
        val pins = PinnedJarProvider.Pin.fromResource("minekt-resolver.txt")
        check(pins.isNotEmpty()) { "This Minekt build can't resolve @file:DependsOn" }
        val missing = pins.filter { PinnedJarProvider(it, libDir, embedded = { embedded(it) }).locate() == null && embedded(it) == null }
        if (missing.isNotEmpty()) onDownload("Downloading Maven support for @file:DependsOn (${missing.sumOf { it.size } / 1_000_000 + 1} MB, first use only)")
        val jars = pins.map { pin -> PinnedJarProvider(pin, libDir, embedded = { embedded(pin) }).obtain() }
        URLClassLoader("minekt-maven", jars.map { it.toUri().toURL() }.toTypedArray(), ScriptDependencies::class.java.classLoader)
    }

    private fun embedded(pin: PinnedJarProvider.Pin) = ScriptDependencies::class.java.getResourceAsStream("/minekt-resolver/${pin.fileName}")

    /** A fresh resolver per script, so one script's `@file:Repository` doesn't leak into others. */
    private fun newResolver(): ExternalDependenciesResolver {
        val maven = mavenLoader.loadClass(MAVEN_RESOLVER).getConstructor().newInstance() as ExternalDependenciesResolver
        return CompoundDependenciesResolver(FileSystemDependenciesResolver(), maven)
    }

    /** Refinement handler: resolve this script's `@DependsOn`s and add them to its classpath. */
    fun refine(context: ScriptConfigurationRefinementContext): ResultWithDiagnostics<ScriptCompilationConfiguration> {
        // Matched by name, like @Import: the compiler may create annotations through another class loader.
        val annotations = context.collectedData?.get(ScriptCollectedData.collectedAnnotations).orEmpty().map { it.annotation }
        fun strings(name: String, member: String) = annotations.filter { it.annotationClass.java.name == name }
            .flatMap { (it.javaClass.getMethod(member).invoke(it) as Array<*>).map(Any?::toString) }
        val repositories = strings(REPOSITORY, "repositoriesCoordinates")
        val artifacts = strings(DEPENDS_ON, "artifactsCoordinates")
        if (artifacts.isEmpty()) return context.compilationConfiguration.asSuccess()

        val location = context.script.locationId
        val resolver = try {
            newResolver()
        } catch (e: Exception) {
            return fail("Couldn't load the dependency resolver: ${e.message ?: e.javaClass.simpleName}", location, e)
        }
        for (repository in repositories) {
            val added = resolver.addRepository(RepositoryCoordinates(repository))
            if (added is ResultWithDiagnostics.Failure) return ResultWithDiagnostics.Failure(added.reports)
        }
        val files = mutableListOf<java.io.File>()
        for (artifact in artifacts) {
            when (val result = runBlocking { resolver.resolve(artifact) }) {
                is ResultWithDiagnostics.Success -> files += result.value
                is ResultWithDiagnostics.Failure -> return fail(
                    "Couldn't get $artifact: " + result.reports.joinToString("; ") { it.message }.ifEmpty { "unknown error" },
                    location, null,
                )
            }
        }
        return context.compilationConfiguration.with { dependencies.append(JvmDependency(files.distinct())) }.asSuccess()
    }

    private fun fail(message: String, location: String?, e: Throwable?) =
        ResultWithDiagnostics.Failure(ScriptDiagnostic(ScriptDiagnostic.unspecifiedError, message, ScriptDiagnostic.Severity.ERROR, location, exception = e))
}
