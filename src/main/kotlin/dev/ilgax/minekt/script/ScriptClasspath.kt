package dev.ilgax.minekt.script

import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer
import org.slf4j.LoggerFactory
import java.io.File
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
        val all = (fromJvm + fromMods).filter(File::exists).distinctBy(File::getAbsolutePath)
        logger.info("Scripts compile against {} jars and folders ({} from the JVM, {} from mods)", all.size, fromJvm.size, fromMods.size)
        logger.debug("Script classpath: {}", all)
        all
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
