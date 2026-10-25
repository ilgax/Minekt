package dev.ilgax.minekt.script

import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.ObjectStreamClass
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.isRegularFile
import kotlin.reflect.KClass
import kotlin.script.experimental.api.CompiledScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.SourceCode
import kotlin.script.experimental.api.dependencies
import kotlin.script.experimental.api.importScripts
import kotlin.script.experimental.host.FileBasedScriptSource
import kotlin.script.experimental.host.FileScriptSource
import kotlin.script.experimental.jvm.JvmDependency
import kotlin.script.experimental.jvm.impl.KJvmCompiledModuleInMemory
import kotlin.script.experimental.jvm.impl.KJvmCompiledScript

/**
 * Compiled scripts on disk, in `minekt/lib/compiled/`, so a script that hasn't changed starts without
 * compiling, even right after launch. An entry's name covers the script's location and text, template,
 * classpath and Kotlin version. Its compiled metadata keeps the source snapshots of every resolved
 * import, including nested helpers; those are checked against disk before the entry can be used.
 * Editing a helper invalidates the entry regardless of annotation spelling. The oldest entries are
 * deleted once there are too many.
 * Anything that can't be stored or read back is simply compiled again.
 */
object CompiledScriptCache {
    private val logger = LoggerFactory.getLogger("minekt")

    /** Where entries go; null (as in tests that don't set it) turns the cache off. */
    @Volatile
    var dir: Path? = null

    /** The entry [source] compiled against [template] would have, or null when the cache is off. */
    internal fun entryFor(template: KClass<*>, source: SourceCode): Path? = try {
        dir?.let { entryIn(it, template, source) }
    } catch (e: Exception) {
        logger.debug("Compiled script {} won't be kept on disk: {}", source.locationId, e.toString())
        null
    }

    private fun entryIn(dir: Path, template: KClass<*>, source: SourceCode): Path {
        val key = buildString {
            append(FORMAT).append('\n').append(template.java.name).append('\n').append(environment)
            append((source as? FileBasedScriptSource)?.file?.absolutePath ?: source.locationId).append('\n')
            append(source.name).append('\n').append(source.text).append('\n')
            if (source !is FileBasedScriptSource) append(ScriptClasspath.scriptDir).append('\n')
        }
        return dir.resolve(sha256(key) + SUFFIX)
    }

    /** The script stored at [entry], or null when there is none or it can't be used. */
    internal fun load(entry: Path): CompiledScript? {
        if (!entry.isRegularFile()) return null
        val script = try {
            Input(BufferedInputStream(Files.newInputStream(entry))).use { it.readObject() as KJvmCompiledScript }
        } catch (e: Exception) {
            discard(entry, e)
            return null
        } catch (e: LinkageError) {
            discard(entry, e)
            return null
        }
        // @file:DependsOn jars are on its classpath: if one was deleted, resolve them again by compiling.
        if (!dependencyFiles(script).all(File::exists)) return null
        val imports = importFingerprints(script) ?: return null
        if (!importsUnchanged(imports)) return null
        runCatching { Files.setLastModifiedTime(entry, FileTime.fromMillis(System.currentTimeMillis())) }
        return script
    }

    /** Write [compiled] to [entry]; failing quietly, since the script still runs. */
    internal fun store(entry: Path, compiled: CompiledScript) {
        val dir = entry.parent
        var temp: Path? = null
        try {
            val script = portable(compiled as KJvmCompiledScript) ?: return
            // Force the actual source snapshots into the serialized metadata; never guess imports from text.
            if (importFingerprints(script) == null) return

            val bytes = ByteArrayOutputStream().also { out -> ObjectOutputStream(out).use { it.writeObject(script) } }.toByteArray()
            Files.createDirectories(dir)
            temp = Files.createTempFile(dir, entry.fileName.toString(), ".tmp")
            Files.write(temp, bytes)
            Files.move(temp, entry, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            prune(dir)
        } catch (e: Exception) {
            if (warned.compareAndSet(false, true)) logger.warn("Compiled scripts can't be kept on disk ({}): they compile on each launch", e.toString())
            else logger.debug("Couldn't keep compiled script {} on disk: {}", compiled.sourceLocationId, e.toString())
        } finally {
            temp?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** [script] with its classes in a [CachedModule], which (unlike the compiler's own) can be written out. */
    private fun portable(script: KJvmCompiledScript): KJvmCompiledScript? {
        val module = when (val compiled = script.getCompiledModule()) {
            null -> null
            is KJvmCompiledModuleInMemory -> CachedModule(HashMap(compiled.compilerOutputFiles))
            else -> return null
        }
        val others = script.otherScripts.map { other -> (other as? KJvmCompiledScript)?.let(::portable) ?: return null }
        return KJvmCompiledScript(script.sourceLocationId, script.compilationConfiguration, script.scriptClassFQName, script.resultField, others, module)
    }

    /** Fingerprint the source snapshots the compiler actually read, not today's contents of those files. */
    internal fun importFingerprints(script: CompiledScript): Map<Path, String>? {
        return try {
            val fingerprints = mutableMapOf<Path, String>()
            for (s in scriptTree(script)) {
                for (source in s.compilationConfiguration[ScriptCompilationConfiguration.importScripts].orEmpty()) {
                    val path = (source as? FileBasedScriptSource)?.file?.toPath()?.toAbsolutePath() ?: return null
                    val hash = sha256(source.text)
                    val previous = fingerprints.put(path, hash)
                    if (previous != null && previous != hash) return null
                }
            }
            fingerprints
        } catch (e: Exception) {
            logger.debug("Couldn't fingerprint imports of {}: {}", script.sourceLocationId, e.toString())
            null
        }
    }

    /** A fresh source uses the same UTF-8/BOM handling as the compiler. Missing files invalidate the entry. */
    internal fun importsUnchanged(imports: Map<Path, String>): Boolean = try {
        imports.all { (path, hash) -> sha256(FileScriptSource(path.toFile()).text) == hash }
    } catch (_: Exception) {
        false
    }

    private fun dependencyFiles(script: CompiledScript): List<File> = scriptTree(script).flatMap { s ->
        s.compilationConfiguration[ScriptCompilationConfiguration.dependencies].orEmpty().filterIsInstance<JvmDependency>().flatMap { it.classpath }
    }.distinct()

    private fun scriptTree(script: CompiledScript): List<CompiledScript> = listOf(script) + script.otherScripts.flatMap(::scriptTree)

    /** What every entry's name includes: the Kotlin version and the classpath, by file size and modification time. */
    private val environment: String by lazy {
        buildString {
            append(KotlinVersion.CURRENT).append('\n')
            for (file in ScriptClasspath.files) append(file.absolutePath).append('|').append(stamp(file)).append('\n')
        }
    }

    /** Changes when a jar, or any file in a folder (a dev build's classes), does. */
    private fun stamp(file: File): Long =
        if (file.isDirectory) file.walkTopDown().filter(File::isFile).sumOf { (it.path.hashCode() * 31L + it.length()) * 31 + it.lastModified() }
        else file.length() * 31 + file.lastModified()

    /** Keep the [MAX_ENTRIES] most recently used entries, and drop temporary files left by a crash. */
    private fun prune(dir: Path) {
        val files = Files.list(dir).use { it.toList() }
        val hourAgo = System.currentTimeMillis() - 3_600_000
        files.filter { it.fileName.toString().endsWith(".tmp") && Files.getLastModifiedTime(it).toMillis() < hourAgo }
            .forEach { runCatching { Files.deleteIfExists(it) } }
        val entries = files.filter { it.fileName.toString().endsWith(SUFFIX) }
        if (entries.size <= MAX_ENTRIES) return
        entries.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .take(entries.size - MAX_ENTRIES)
            .forEach { runCatching { Files.deleteIfExists(it) } }
    }

    private fun discard(entry: Path, e: Throwable) {
        logger.debug("Discarding unreadable compiled script {}: {}", entry.fileName, e.toString())
        runCatching { Files.deleteIfExists(entry) }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Reads classes through Minekt's class loader, which sees the scripting library and Minekt itself. */
    private class Input(stream: InputStream) : ObjectInputStream(stream) {
        override fun resolveClass(desc: ObjectStreamClass): Class<*> =
            try {
                Class.forName(desc.name, false, CompiledScriptCache::class.java.classLoader)
            } catch (e: ClassNotFoundException) {
                super.resolveClass(desc)
            }
    }

    private val warned = AtomicBoolean()

    /** Bump when what's stored changes shape. */
    private const val FORMAT = 2
    private const val SUFFIX = ".bin"
    private const val MAX_ENTRIES = 256
}

/** A compiled script's classes, in a form that can be written to disk. */
internal class CachedModule(override val compilerOutputFiles: Map<String, ByteArray>) : KJvmCompiledModuleInMemory, Serializable {
    override fun createClassLoader(baseClassLoader: ClassLoader?): ClassLoader =
        ScriptClassLoader(compilerOutputFiles, baseClassLoader ?: CachedModule::class.java.classLoader)

    private companion object {
        private const val serialVersionUID = 1L
    }
}
