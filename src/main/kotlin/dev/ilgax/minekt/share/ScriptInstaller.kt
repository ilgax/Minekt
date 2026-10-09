package dev.ilgax.minekt.share

import dev.ilgax.minekt.script.ScriptCompiler
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.host.toScriptSource

/**
 * Installs scripts from a URL or pasted text in two steps: [fromUrl]/[fromText] fetch, check and
 * stage a [Pending] install for the player to review; only [confirm] writes it into the script
 * folder. Nothing downloaded runs before the player has seen the preview and said yes.
 */
class ScriptInstaller(
    private val scriptDir: Path,
    private val fetch: (URI) -> String = ::download,
) {
    data class Pending(
        val token: String,
        val name: String,
        val text: String,
        /** Where it came from (URL), or null for pasted text. */
        val source: String?,
        val lines: Int,
        /** Line count of the script this would replace, or null if it's new. */
        val replacesLines: Int?,
        /** What the script can do, most sensitive first. A quick scan, not a guarantee. */
        val capabilities: List<String>,
        /** Compile errors (and other problems) found before installing. */
        val problems: List<String>,
        /** Copy of the source the player can open before deciding. */
        val previewFile: Path,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var latest: String? = null

    /** Download a script (GitHub / gist / pastebin page links are turned into raw-file links) and stage it. */
    fun fromUrl(url: String, name: String? = null): Pending {
        val uri = rawUrl(url)
        val text = fetch(uri)
        return stage(name ?: nameFromUrl(uri) ?: nameFromHeader(text), text, uri.toString())
    }

    /** Stage pasted script text (e.g. from the clipboard). */
    fun fromText(text: String, name: String? = null): Pending = stage(name ?: nameFromHeader(text), text, null)

    /** Write the staged script into the script folder; [token] null means the most recent one. */
    fun confirm(token: String? = null): Pending {
        val install = take(token)
        val lines = install.text.lines().filterNot { it.startsWith(SOURCE_MARKER) }
        val text = (listOfNotNull(install.source?.let { "$SOURCE_MARKER $it" }) + lines).joinToString("\n")
        Files.createDirectories(scriptDir)
        Files.writeString(scriptDir.resolve("${install.name}.kts"), text)
        Files.deleteIfExists(install.previewFile)
        return install
    }

    fun cancel(token: String? = null): Pending = take(token).also { Files.deleteIfExists(it.previewFile) }

    /** The URL an installed script came from, if it was installed from one (for `\update`). */
    fun sourceOf(name: String): String? {
        val file = scriptDir.resolve("$name.kts")
        if (!file.exists()) return null
        return file.readText().lineSequence().firstOrNull { it.startsWith(SOURCE_MARKER) }?.removePrefix(SOURCE_MARKER)?.trim()
    }

    private fun take(token: String?): Pending {
        prune()
        val key = token ?: latest ?: error("Nothing waiting to be installed")
        val install = pending.remove(key) ?: error("That install has expired or was already handled")
        if (latest == key) latest = null
        return install
    }

    private fun stage(rawName: String?, text: String, source: String?): Pending {
        val name = rawName?.let(::sanitizeName)?.takeIf { it.isNotEmpty() }
            ?: error("Couldn't work out a name for this script; add one, e.g. \\install <url> myscript")
        check(text.isNotBlank()) { "That's empty" }
        check(text.length <= MAX_SIZE) { "That's too big for a script (${text.length / 1024} KB)" }
        check(!looksLikeHtml(text)) { "That's a web page, not a script. Use the link to the raw file." }

        val existing = scriptDir.resolve("$name.kts").takeIf { it.exists() }?.readText()
        val previewDir = scriptDir.resolve(".pending")
        Files.createDirectories(previewDir)
        val preview = previewDir.resolve("$name.kts")
        Files.writeString(preview, text)

        val install = Pending(
            token = UUID.randomUUID().toString().take(8),
            name = name,
            text = text,
            source = source,
            lines = lineCount(text),
            replacesLines = existing?.let(::lineCount),
            capabilities = capabilities(text),
            problems = compileProblems(name, text),
            previewFile = preview,
        )
        prune()
        pending[install.token] = install
        latest = install.token
        return install
    }

    private fun compileProblems(name: String, text: String): List<String> =
        when (val result = ScriptCompiler.compile(text.toScriptSource("$name.kts"))) {
            is ResultWithDiagnostics.Success -> emptyList()
            is ResultWithDiagnostics.Failure -> result.reports
                .filter { it.severity >= ScriptDiagnostic.Severity.ERROR }
                .map { d -> d.location?.start?.let { "line ${it.line}: ${d.message}" } ?: d.message }
                .distinct()
                .ifEmpty { listOf("doesn't compile") }
        }

    private fun prune() {
        val cutoff = System.currentTimeMillis() - EXPIRY_MS
        pending.values.removeIf { it.createdAt < cutoff }
    }

    companion object {
        const val SOURCE_MARKER = "// minekt:source"
        private const val MAX_SIZE = 512 * 1024
        private const val EXPIRY_MS = 10 * 60 * 1000L

        /** Turn page links into raw-file links; enforce https (plain http only for this computer). */
        fun rawUrl(input: String): URI {
            val uri = runCatching { URI(input.trim()) }.getOrNull()?.takeIf { it.host != null }
                ?: error("Not a valid URL: $input")
            val path = uri.path.orEmpty().trim('/').split('/').filter { it.isNotEmpty() }
            val raw = when {
                uri.host == "github.com" && path.size >= 5 && path[2] == "blob" ->
                    URI("https://raw.githubusercontent.com/${path[0]}/${path[1]}/${path.drop(3).joinToString("/")}")
                uri.host == "gist.github.com" && path.size == 2 ->
                    URI("https://gist.githubusercontent.com/${path[0]}/${path[1]}/raw")
                uri.host == "pastebin.com" && path.size == 1 ->
                    URI("https://pastebin.com/raw/${path[0]}")
                else -> uri
            }
            val loopback = runCatching { InetAddress.getByName(raw.host).isLoopbackAddress }.getOrDefault(false)
            check(raw.scheme == "https" || (raw.scheme == "http" && loopback)) {
                "Only https:// links are allowed (plain http could be tampered with on the way)"
            }
            return raw
        }

        fun nameFromUrl(uri: URI): String? =
            uri.path.orEmpty().substringAfterLast('/').takeIf { it.isNotEmpty() && it != "raw" }

        /** Scripts conventionally start with `// \name …` (see the bundled examples). */
        fun nameFromHeader(text: String): String? =
            Regex("""^\s*//\s*\\([A-Za-z0-9_-]+)""").find(text.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith(SOURCE_MARKER) }.orEmpty())?.groupValues?.get(1)

        /** Lines of actual content: not counting the recorded source line or trailing blank lines. */
        fun lineCount(text: String): Int = text.trimEnd().lines().count { !it.startsWith(SOURCE_MARKER) }

        fun sanitizeName(raw: String): String =
            raw.removeSuffix(".kts").removeSuffix(".main").removeSuffix(".kt").removeSuffix(".txt")
                .replace(Regex("[^A-Za-z0-9_-]"), "_").trim('_')

        private fun looksLikeHtml(text: String): Boolean {
            val start = text.trimStart().take(512).lowercase()
            return start.startsWith("<!doctype html") || start.startsWith("<html")
        }

        private val CAPABILITIES = listOf(
            """ProcessBuilder|Runtime\s*\.\s*getRuntime""" to "run programs on your computer",
            """java\.io\.|java\.nio\.|\bFile\s*\(|\bFiles\s*\.|\bPaths?\s*\.""" to "read or write files on your computer",
            """java\.net\.|\bHttpClient\b|\bURL\s*\(|\bURI\s*\(|\bSocket\b""" to "connect to the internet",
            """Class\s*\.\s*forName|getDeclared(Method|Field|Constructor)|setAccessible""" to "use reflection to reach hidden code",
            """\bgame\s*\{""" to "use raw game access (anything the game itself can do)",
            """(?m)^\s*import\s+(?!dev\.ilgax\.minekt\.|kotlin\.|kotlinx\.coroutines\.)""" to "use Minecraft's or other mods' code directly",
            """\binterceptSend\b""" to "change or block chat messages you send",
            """\b(chat|execute)\s*\(""" to "send chat messages or commands as you",
            """\binterceptChat\b""" to "hide or rewrite chat messages you receive",
            """\b(attack|interact)\s*\(""" to "attack or interact with mobs and players",
            """\b(breakBlock|placeBlock|useOn|useItem)\s*\(""" to "break or place blocks and use items",
            """\b(clickSlot|shiftClick|swapWithHotbar|dropSlot)\s*\(""" to "move or drop items in your inventory",
            """\b(press|hold|setOrientation|turnTo|turnToward|lookAt)\s*\(""" to "move you and turn your camera",
            """\bbaritone\b""" to "control Baritone",
            """@file:DependsOn""" to "download and run code libraries from Maven (@file:DependsOn)",
            """@file:Import""" to "load other script files",
        ).map { (pattern, label) -> Regex(pattern) to label }

        /** Plain-language list of what [text] can do, most sensitive first. Comments are ignored. */
        fun capabilities(text: String): List<String> {
            val code = text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .lines().joinToString("\n") { it.substringBefore("//") }
            return CAPABILITIES.filter { (regex, _) -> regex.containsMatchIn(code) }.map { it.second }
        }

        private fun download(uri: URI): String {
            val client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("User-Agent", "Minekt").GET().build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            check(response.statusCode() == 200) { "Download failed: HTTP ${response.statusCode()}" }
            check(response.uri().scheme == "https" || response.uri().scheme == uri.scheme) { "Refusing to follow a redirect to plain http" }
            response.body().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    check(out.size() <= MAX_SIZE) { "That's too big for a script" }
                }
                return out.toString(Charsets.UTF_8)
            }
        }
    }
}
