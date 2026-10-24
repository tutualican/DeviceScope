package com.alican.devicescope

import android.os.Process
import android.os.SystemClock

/**
 * Cold-start measurement for DeviceScope itself.
 *
 * This is a real value, not an estimate: Android exposes the uptime at which the
 * process was created ([Process.getStartUptimeMillis]), and the first
 * Choreographer frame callback tells us when the first frame was ready to draw.
 * The difference is the launch latency the user actually experiences.
 *
 * It is recorded once per process, so re-opening the activity from recents does
 * not overwrite it with a warm start.
 */
object LaunchMetrics {

    @Volatile
    var coldStartMs: Long? = null
        private set

    @Volatile
    var processStartUptimeMs: Long? = null
        private set

    @Volatile
    var recordedAtWallClock: Long? = null
        private set

    private val recorded = java.util.concurrent.atomic.AtomicBoolean(false)

    fun record() {
        if (!recorded.compareAndSet(false, true)) return
        val start = try {
            Process.getStartUptimeMillis()
        } catch (t: Throwable) {
            return
        }
        processStartUptimeMs = start
        recordedAtWallClock = System.currentTimeMillis() - (SystemClock.uptimeMillis() - start)
    }

    /** Called from the first frame callback. */
    fun markFirstFrame() {
        if (coldStartMs != null) return
        val start = processStartUptimeMs ?: return
        val elapsed = SystemClock.uptimeMillis() - start
        if (elapsed in 1..60_000) coldStartMs = elapsed
    }

    fun reset() {
        coldStartMs = null
        processStartUptimeMs = null
        recordedAtWallClock = null
        recorded.set(false)
    }
}
