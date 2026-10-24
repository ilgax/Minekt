package dev.ilgax.minekt

import dev.ilgax.minekt.script.ScriptClasspath
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.Enumeration
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Finding the jars a class loader reads, which scripts then compile against. */
class ScriptClasspathTest {
    private val dir = Files.createTempDirectory("minekt-classpath").toFile()

    private fun jar(name: String, withManifest: Boolean): File = dir.resolve(name).also { file ->
        val out = file.outputStream()
        (if (withManifest) JarOutputStream(out, Manifest().apply { mainAttributes.putValue("Manifest-Version", "1.0") }) else JarOutputStream(out)).use {
            it.putNextEntry(ZipEntry("lib/Thing.class"))
            it.write(byteArrayOf(0))
            it.closeEntry()
        }
    }

    private val errors = mutableListOf<String>()
    private fun files(loader: ClassLoader) = ScriptClasspath.filesOf(loader) { what, _ -> errors += what }.map { it.canonicalFile }

    @Test
    fun `a manifest-less jar is found through the loader's URLs`() {
        val plain = jar("plain.jar", withManifest = false)
        URLClassLoader(arrayOf(plain.toURI().toURL()), null).use { loader ->
            assertTrue(plain.canonicalFile in files(loader))
        }
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `URLs kept in a field are used, like Fabric's Knot class loader`() {
        val plain = jar("plain.jar", withManifest = false)
        val knotLike = object : ClassLoader(null) {
            @Suppress("unused") private val urlLoader = URLClassLoader(arrayOf(plain.toURI().toURL()), null)
        }
        assertTrue(plain.canonicalFile in files(knotLike))
    }

    @Test
    fun `without URLs, jars are found through their manifests`() {
        val withManifest = jar("manifest.jar", withManifest = true)
        val plain = jar("plain.jar", withManifest = false)
        val delegate: ClassLoader = URLClassLoader(arrayOf(withManifest.toURI().toURL(), plain.toURI().toURL()), null)
        val opaque = object : ClassLoader(null) {
            override fun getResources(name: String): Enumeration<URL> = delegate.getResources(name)
        }
        val found = files(opaque)
        assertTrue(withManifest.canonicalFile in found)
        assertFalse(plain.canonicalFile in found) // the documented limit
    }

    @Test
    fun `failures are reported, not swallowed`() {
        val broken = object : ClassLoader(null) {
            override fun getResources(name: String): Enumeration<URL> = throw java.io.IOException("unreadable")
        }
        assertEquals(emptyList(), files(broken))
        assertEquals(listOf("the class loader's manifests"), errors)
    }

    @Test
    fun `folders on the URL path are kept`() {
        val classes = dir.resolve("classes").apply { mkdirs() }
        URLClassLoader(arrayOf(classes.toURI().toURL()), null).use { loader ->
            assertTrue(classes.canonicalFile in files(loader))
        }
    }
}
