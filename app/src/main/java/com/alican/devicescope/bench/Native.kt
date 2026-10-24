package com.alican.devicescope.bench

/**
 * JNI bridge to the C++ measurement core (app/src/main/cpp/bench.cpp).
 * The native side owns the workload math; Kotlin owns scheduling and statistics.
 */
object Native {

    private var loaded = false
    private var loadError: String? = null

    fun available(): Boolean = ensureLoaded()

    fun error(): String? = loadError

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            System.loadLibrary("dsbench")
            loaded = true
            true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    /** Returns [units, checksum, elapsedNanos]. */
    fun run(id: Int, iterations: Int): LongArray {
        if (!ensureLoaded()) return longArrayOf(0, 0, 0)
        return try {
            nRun(id, iterations) ?: longArrayOf(0, 0, 0)
        } catch (t: Throwable) {
            longArrayOf(0, 0, 0)
        }
    }

    fun verify(id: Int): Boolean {
        if (!ensureLoaded()) return false
        return try {
            nVerify(id)
        } catch (t: Throwable) {
            false
        }
    }

    fun deviceCores(): Int {
        if (!ensureLoaded()) return Runtime.getRuntime().availableProcessors()
        return try {
            nCores()
        } catch (t: Throwable) {
            Runtime.getRuntime().availableProcessors()
        }
    }

    private external fun nRun(id: Int, iterations: Int): LongArray?
    private external fun nVerify(id: Int): Boolean
    private external fun nCores(): Int
}
