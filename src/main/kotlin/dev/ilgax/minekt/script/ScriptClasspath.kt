package dev.ilgax.minekt.script

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import net.fabricmc.loader.impl.launch.FabricLauncherBase
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The classpath scripts are compiled against: the JVM classpath plus every loaded mod's jar
 * (Minecraft itself, Fabric API, fabric-language-kotlin, Baritone, ...). This is what lets a
 * script `import baritone.api.BaritoneAPI` or any other mod's classes directly.
 *
 * In a real install many mods sit inside other jars (each Fabric API module, Kotlin's stdlib in
 * fabric-language-kotlin). The loader reads those in place, but the compiler needs real files, so
 * they are packed into jars under `minekt/lib/classpath/` once per version.
 */
object ScriptClasspath {
    private val logger = LoggerFactory.getLogger("minekt")

    @Volatile
    var scriptDir: Path? = null

    val files: List<File> by lazy {
        val fromJvm = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map(::File)
        val fromMods = runCatching { FabricLoader.getInstance().allMods.toList() }.getOrDefault(emptyList())
            .flatMap { mod ->
                runCatching { filesOf(mod) }
                    .onFailure { logger.warn("Scripts can't use {}'s classes: {}", mod.metadata.id, it.toString()) }
                    .getOrDefault(emptyList())
            }
        // The game's own libraries (Brigadier, DataFixerUpper, …): on a dedicated server Fabric's class loader
        // loads them from the server's libraries/ folder, so they aren't on the JVM's class path.
        val onError = { what: String, e: Throwable -> logger.warn("Scripts may miss some of the game's libraries: couldn't read {}: {}", what, e.toString()) }
        val fromLauncher = runCatching { FabricLauncherBase.getLauncher().classPath.map(Path::toFile) }
            .onFailure { onError("Fabric's launch class path", it) }.getOrDefault(emptyList())
        val fromLoader = (fromLauncher + filesOf(ScriptClasspath::class.java.classLoader, onError)).distinctBy(File::getAbsolutePath)
        val all = (fromJvm + fromLoader + fromMods).filter(File::exists).distinctBy(File::getAbsolutePath)
        logger.info("Scripts compile against {} jars and folders ({} from the JVM, {} from the game's class loader, {} from mods)", all.size, fromJvm.size, fromLoader.size, fromMods.size)
        logger.debug("Script classpath: {}", all)
        all
    }

    /**
     * The jars and folders on disk [loader] reads classes from: its URLs when it has them (a [URLClassLoader], or
     * one keeping a [URLClassLoader] in a field, like Fabric's Knot), plus every jar with a `META-INF/MANIFEST.MF`
     * it can see. A manifest-less jar in a loader that has no URLs isn't found. [onError] gets what couldn't be read.
     */
    internal fun filesOf(loader: ClassLoader, onError: (String, Throwable) -> Unit): List<File> {
        val fromUrls = runCatching { urlsOf(loader).mapNotNull(::fileOf) }
            .onFailure { onError("the class loader's URLs", it) }.getOrDefault(emptyList())
        val fromManifests = runCatching { loader.getResources("META-INF/MANIFEST.MF").toList().mapNotNull(::fileOf) }
            .onFailure { onError("the class loader's manifests", it) }.getOrDefault(emptyList())
        return (fromUrls + fromManifests).distinctBy(File::getAbsolutePath)
    }

    private fun urlsOf(loader: ClassLoader): List<URL> {
        if (loader is URLClassLoader) return loader.urLs.toList()
        val field = loader.javaClass.declaredFields.firstOrNull { URLClassLoader::class.java.isAssignableFrom(it.type) } ?: return emptyList()
        field.isAccessible = true
        return (field.get(loader) as? URLClassLoader)?.urLs?.toList().orEmpty()
    }

    /** The file or folder behind a `file:` URL, or the jar behind a `jar:file:…!/entry` one. */
    private fun fileOf(url: URL): File? {
        val location = when (url.protocol) {
            "file" -> url.toString()
            "jar" -> url.path.substringBefore("!/").takeIf { it.startsWith("file:") } ?: return null
            else -> return null
        }
        return runCatching { File(URI(location)) }.getOrNull()?.takeIf { it.exists() }
    }

    private fun filesOf(mod: ModContainer): List<File> = mod.rootPaths.mapIndexed { i, root ->
        if (root.fileSystem == FileSystems.getDefault()) return@mapIndexed root.toFile()
        // A jar opened as a file system: on disk already if it's a top-level jar, otherwise pack it.
        val outer = runCatching { Path.of(root.fileSystem.toString()) }.getOrNull()
        if (outer != null && outer.fileSystem == FileSystems.getDefault() && outer.isRegularFile()) return@mapIndexed outer.toFile()
        packed(mod, i, root)
    }

    private fun packed(mod: ModContainer, index: Int, root: Path): File {
        val dir = cacheDir()
        val name = "${mod.metadata.id}-${mod.metadata.version.friendlyString}${if (index > 0) "-$index" else ""}.jar"
            .replace(Regex("[^A-Za-z0-9._+-]"), "_")
        val jar = dir.resolve(name)
        if (Files.isRegularFile(jar)) return jar.toFile()
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, name, ".tmp")
        ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
            Files.walk(root).use { paths ->
                for (path in paths) {
                    if (path == root) continue
                    val entry = root.relativize(path).joinToString("/")
                    if (path.isDirectory()) {
                        zip.putNextEntry(ZipEntry("$entry/"))
                    } else {
                        zip.putNextEntry(ZipEntry(entry))
                        Files.copy(path, zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        Files.move(temp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return jar.toFile()
    }

    private fun cacheDir(): Path =
        (scriptDir ?: FabricLoader.getInstance().gameDir.resolve("minekt")).resolve("lib").resolve("classpath")
}
