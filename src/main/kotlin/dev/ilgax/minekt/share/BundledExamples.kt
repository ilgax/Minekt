package dev.ilgax.minekt.share

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Keeps the example scripts Minekt ships in the script folder up to date. `.examples` in the folder remembers the
 * version of each example that was put there. On every start:
 * - a new example is added;
 * - an example the player hasn't changed is replaced by the new version;
 * - an example the player changed is left alone, and the new version is put next to it as `<name>.kts.new`;
 * - an example the player deleted stays deleted.
 * Without `.examples` (a folder from before Minekt kept one), an example that differs from the shipped one can't be
 * told apart from an edited one, so it's treated as edited.
 */
class BundledExamples(private val dir: Path, private val shipped: Map<String, ByteArray>) {
    /** What changed, in words, for the log: one line per example that was added or updated, or got a `.new` file. */
    fun sync(): List<String> {
        Files.createDirectories(dir)
        val manifestFile = dir.resolve(MANIFEST)
        val recorded = readManifest(manifestFile)
        val result = LinkedHashMap(recorded)
        val changes = mutableListOf<String>()
        for ((name, bytes) in shipped) {
            val file = dir.resolve("$name.kts")
            val newHash = hash(bytes)
            val newFile = dir.resolve("$name.kts.new")
            when {
                !file.exists() -> if (name !in recorded) {
                    Files.write(file, bytes)
                    result[name] = newHash
                    changes += "added $name.kts"
                }
                hash(file.readBytes()) == newHash -> {
                    result[name] = newHash
                    Files.deleteIfExists(newFile)
                }
                recorded[name] == hash(file.readBytes()) -> {
                    Files.write(file, bytes)
                    result[name] = newHash
                    Files.deleteIfExists(newFile)
                    changes += "updated $name.kts"
                }
                !newFile.exists() || hash(newFile.readBytes()) != newHash -> {
                    Files.write(newFile, bytes)
                    changes += "$name.kts was changed, so the new version is in $name.kts.new"
                }
            }
        }
        if (result != recorded) writeManifest(manifestFile, result)
        return changes
    }

    private fun readManifest(file: Path): Map<String, String> =
        if (!file.exists()) emptyMap()
        else Files.readAllLines(file).mapNotNull { line -> line.trim().split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    private fun writeManifest(file: Path, entries: Map<String, String>) {
        val text = "# The version of each bundled example Minekt last put here, so it only updates ones you haven't changed.\n" +
            entries.entries.joinToString("") { (name, hash) -> "$name $hash\n" }
        Files.writeString(file, text)
    }

    companion object {
        const val MANIFEST = ".examples"

        /** Ignores line endings, so a checkout or editor switching them doesn't count as a change. */
        fun hash(bytes: ByteArray): String {
            val text = String(bytes, Charsets.UTF_8).replace("\r\n", "\n")
            return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }
}