package dev.ilgax.minekt.script

import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Path

/**
 * The classpath scripts are compiled against: the JVM classpath plus every loaded mod's jar
 * (Minecraft itself, Fabric API, fabric-language-kotlin, Baritone, ...). This is what lets a
 * script `import baritone.api.BaritoneAPI` or any other mod's classes directly.
 */
object ScriptClasspath {
    @Volatile
    var scriptDir: Path? = null

    val files: List<File> by lazy {
        val fromJvm = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map(::File)
        val fromMods = runCatching {
            FabricLoader.getInstance().allMods.flatMap { mod -> mod.origin.paths.map(Path::toFile) }
        }.getOrDefault(emptyList())
        (fromJvm + fromMods).filter(File::exists).distinctBy(File::getAbsolutePath)
    }
}
