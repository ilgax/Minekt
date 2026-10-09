package dev.ilgax.minekt.script

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries

/**
 * Finds or downloads a library jar Minekt doesn't always ship: the Kotlin compiler (small build) and
 * the Maven resolver behind `@file:DependsOn` (fetched on first use).
 *
 * The exact file is pinned at build time ([Pin]: version and SHA-256). Any copy with that hash is
 * accepted, wherever it comes from: `minekt/lib/`, a Gradle or Maven cache, a copy shipped inside
 * the full jar ([embedded]), or a download from Maven Central (or a mirror). Anything else is
 * ignored, so a wrong or tampered file never loads.
 */
class PinnedJarProvider(
    private val pin: Pin,
    /** Where downloaded copies are kept (`.minecraft/minekt/lib`). */
    private val libDir: Path,
    /** Extra places to look for an existing copy (Gradle / Maven caches). */
    private val caches: List<Path> = defaultCaches(pin),
    private val mirrors: List<String> = MIRRORS,
    /** A copy bundled with the mod, if this build has one. */
    private val embedded: () -> java.io.InputStream? = { null },
    private val fetch: (URI, Path, (Long) -> Unit) -> Unit = ::download,
) {
    /** One exact jar from Maven Central. */
    data class Pin(val group: String, val name: String, val version: String, val sha256: String, val size: Long, val classifier: String? = null) {
        val fileName get() = "$name-$version${classifier?.let { "-$it" } ?: ""}.jar"
        val mavenPath get() = "${group.replace('.', '/')}/$name/$version/$fileName"

        companion object {
            /**
             * Pins written into the jar by the build, one per line: `group:name:version[:classifier] sha256 size`.
             * Empty when the resource is missing.
             */
            fun fromResource(name: String): List<Pin> {
                val stream = PinnedJarProvider::class.java.getResourceAsStream("/$name") ?: return emptyList()
                return stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
                    val (coordinates, sha256, size) = line.trim().split(Regex("\\s+"))
                    val parts = coordinates.split(':')
                    Pin(parts[0], parts[1], parts[2], sha256, size.toLong(), parts.getOrNull(3))
                }
            }
        }
    }

    /** An existing copy with the right hash, or null. Never touches the network. */
    fun locate(): Path? = (listOf(libDir.resolve(pin.fileName)) + caches).firstOrNull { it.isRegularFile() && matches(it) }

    /**
     * An existing copy, or a fresh download into [libDir]. [progress] gets the bytes received so
     * far (out of [Pin.size]). Throws [IOException] with a readable message if every mirror fails.
     */
    fun obtain(progress: (Long) -> Unit = {}): Path {
        locate()?.let { return it }
        Files.createDirectories(libDir)
        val target = libDir.resolve(pin.fileName)
        val temp = libDir.resolve("${pin.fileName}.part")
        embedded()?.use { input ->
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
            if (matches(temp)) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return target
            }
            Files.deleteIfExists(temp)
        }
        val problems = mutableListOf<String>()
        for (mirror in mirrors) {
            try {
                Files.deleteIfExists(temp)
                fetch(URI("${mirror.trimEnd('/')}/${pin.mavenPath}"), temp, progress)
                if (!matches(temp)) throw IOException("the file's checksum doesn't match")
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                return target
            } catch (e: Exception) {
                problems += "${URI(mirror).host}: ${describe(e)}"
            } finally {
                Files.deleteIfExists(temp)
            }
        }
        throw IOException(
            "Couldn't download ${pin.fileName} (${problems.joinToString("; ")}). " +
                "Connect to the internet once, or download ${pin.fileName} from Maven Central into $libDir",
        )
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.ConnectException, is java.net.UnknownHostException, is java.net.http.HttpConnectTimeoutException -> "no connection"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun matches(file: Path): Boolean = Files.size(file) == pin.size && sha256(file) == pin.sha256

    companion object {
        val MIRRORS = listOf(
            "https://repo.maven.apache.org/maven2",
            "https://maven-central.storage-download.googleapis.com/maven2",
        )

        fun sha256(file: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /** Copies a developer may already have from building Kotlin projects. */
        fun defaultCaches(pin: Pin): List<Path> {
            val home = Path.of(System.getProperty("user.home"))
            val gradleHome = System.getenv("GRADLE_USER_HOME")?.let(Path::of) ?: home.resolve(".gradle")
            val gradle = gradleHome.resolve("caches/modules-2/files-2.1/${pin.group}/${pin.name}/${pin.version}")
            val gradleCopies = if (gradle.exists()) runCatching { gradle.listDirectoryEntries().map { it.resolve(pin.fileName) } }.getOrDefault(emptyList()) else emptyList()
            return gradleCopies + home.resolve(".m2/repository/${pin.mavenPath}")
        }

        private fun download(uri: URI, target: Path, progress: (Long) -> Unit) {
            require(uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1")) { "Only https downloads are allowed" }
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
            val request = HttpRequest.newBuilder(uri).header("User-Agent", "Minekt").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                response.body().close()
                throw IOException("HTTP ${response.statusCode()}")
            }
            response.body().use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        total += n
                        progress(total)
                    }
                }
            }
        }
    }
}
