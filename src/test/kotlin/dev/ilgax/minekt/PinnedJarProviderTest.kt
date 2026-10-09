package dev.ilgax.minekt

import com.sun.net.httpserver.HttpServer
import dev.ilgax.minekt.script.PinnedJarProvider
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Finding and downloading the pinned compiler jar, against a local web server. */
class PinnedJarProviderTest {
    private val content = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val pin = PinnedJarProvider.Pin(
        "org.jetbrains.kotlin", "kotlin-compiler-embeddable", "9.9.9",
        MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }, content.size.toLong(),
    )
    private val dir = Files.createTempDirectory("minekt-compiler")
    private val lib = dir.resolve("lib")
    private val requests = mutableListOf<String>()

    /** Mirror "good" serves the real file, "bad" a corrupted one, "down" fails. */
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { exchange ->
            val path = exchange.requestURI.path
            synchronized(requests) { requests += path }
            val body = when {
                path.startsWith("/good/") -> content
                path.startsWith("/bad/") -> content.copyOf().also { it[10] = (it[10] + 1).toByte() }
                else -> null
            }
            if (body == null) {
                exchange.sendResponseHeaders(503, -1)
            } else {
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        start()
    }
    private fun mirror(name: String) = "http://127.0.0.1:${server.address.port}/$name"

    @AfterTest
    fun stop() = server.stop(0)

    private fun provider(vararg mirrors: String, caches: List<Path> = emptyList(), embedded: ByteArray? = null) =
        PinnedJarProvider(pin, lib, caches, mirrors.map(::mirror), embedded = { embedded?.inputStream() })

    @Test
    fun `a copy shipped in the mod is used without downloading, if its hash matches`() {
        assertEquals(lib.resolve(pin.fileName), provider("down", embedded = content).obtain())
        assertTrue(requests.isEmpty())
        Files.delete(lib.resolve(pin.fileName))
        // A broken embedded copy is skipped in favour of a download.
        assertEquals(lib.resolve(pin.fileName), provider("good", embedded = content.copyOf(10)).obtain())
        assertEquals(listOf("/good/${pin.mavenPath}"), requests)
    }

    @Test
    fun `downloads from the next mirror when one is down, and keeps the file`() {
        var progress = 0L
        val jar = provider("down", "good").obtain { progress = it }
        assertEquals(lib.resolve(pin.fileName), jar)
        assertTrue(Files.readAllBytes(jar).contentEquals(content))
        assertEquals(content.size.toLong(), progress)
        assertEquals(listOf("/down/${pin.mavenPath}", "/good/${pin.mavenPath}"), requests)

        // Next launch: found locally, no network.
        requests.clear()
        assertEquals(jar, provider("good").obtain())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `a download with the wrong checksum is rejected and not kept`() {
        val error = assertFailsWith<IOException> { provider("bad", "down").obtain() }
        assertTrue("checksum" in error.message!! && "Connect to the internet once" in error.message!!, error.message)
        assertFalse(Files.exists(lib.resolve(pin.fileName)))
        assertFalse(Files.exists(lib.resolve("${pin.fileName}.part")))
    }

    @Test
    fun `existing copies are used only if their hash matches`() {
        val wrong = dir.resolve("gradle/a/${pin.fileName}").also { Files.createDirectories(it.parent); Files.write(it, content.copyOf(content.size - 1) + byteArrayOf(0)) }
        val right = dir.resolve("gradle/b/${pin.fileName}").also { Files.createDirectories(it.parent); Files.write(it, content) }
        assertEquals(right, provider("down", caches = listOf(wrong, right)).obtain())
        assertTrue(requests.isEmpty())
        assertEquals(null, provider("down", caches = listOf(wrong)).locate())
    }
}
