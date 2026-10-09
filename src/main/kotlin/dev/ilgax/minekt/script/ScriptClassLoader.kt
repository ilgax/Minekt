package dev.ilgax.minekt.script

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler

/** Loads one run of a script from its (instrumented) compiled classes. One loader per run, so each run starts fresh. */
internal class ScriptClassLoader(private val files: Map<String, ByteArray>, parent: ClassLoader) : ClassLoader(parent) {
    override fun findClass(name: String): Class<*> {
        val bytes = files[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun findResource(name: String): URL? {
        val bytes = files[name] ?: return null
        return URL(null, "minekt-script:$name", object : URLStreamHandler() {
            override fun openConnection(url: URL) = object : URLConnection(url) {
                override fun connect() = Unit
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
            }
        })
    }

    companion object {
        init {
            registerAsParallelCapable()
        }
    }
}
