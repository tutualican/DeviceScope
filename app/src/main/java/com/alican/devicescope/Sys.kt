package com.alican.devicescope

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Raw access helpers. Everything here reads real device data; nothing is fabricated.
 * A null return always means "could not be read on this device / blocked by the platform".
 */
object Sys {

    private val propCache: Map<String, String> by lazy { loadProps() }

    private fun loadProps(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        try {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val m = PROPS.find(line)
                    if (m != null) out[m.groupValues[1]] = m.groupValues[2]
                }
            }
            p.waitFor()
        } catch (_: Throwable) {
        }
        return out
    }

    private val PROPS = Regex("^\\[(.+?)]: \\[(.*)]$")

    fun prop(key: String): String? = propCache[key]?.takeIf { it.isNotBlank() && it != "unknown" }

    fun props(): Map<String, String> = propCache

    fun read(path: String): String? = try {
        val f = File(path)
        if (!f.exists() || !f.canRead()) null else f.readText().trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    fun readBytes(path: String): ByteArray? = try {
        val f = File(path)
        if (!f.exists() || !f.canRead()) null else f.readBytes().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    fun isReadable(path: String): Boolean = try {
        val f = File(path)
        f.exists() && f.canRead()
    } catch (_: Throwable) {
        false
    }

    fun listDir(path: String): List<File> = try {
        File(path).listFiles()?.sortedBy { it.name } ?: emptyList()
    } catch (_: Throwable) {
        emptyList()
    }

    fun sh(vararg cmd: String): String? = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        text.trim().takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    private val fileCache = ConcurrentHashMap<String, String>()

    /** Cached read for values that never change during a session (cpuinfo, /proc/version). */
    fun readCached(path: String): String? {
        val cached = fileCache[path]
        if (cached != null) return cached.ifEmpty { null }
        val value = read(path) ?: ""
        fileCache[path] = value
        return value.ifEmpty { null }
    }

    fun clearCache() = fileCache.clear()
}
