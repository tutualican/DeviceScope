package com.alican.devicescope.bench

import android.view.Choreographer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Drives the on-screen "AnTuTu style" visual stage.
 *
 * The runner asks for a scene; this object then posts Choreographer frame
 * callbacks for the requested duration, records the real frame intervals and
 * exposes a monotonically increasing [tick] that the Compose overlay uses to
 * animate its geometry. Because the tick is Compose state, every recorded frame
 * is also a frame the UI actually drew — the number cannot be produced by an
 * idle screen.
 *
 * This is a visual workload, not a rendering shortcut: the overlay draws
 * hundreds of projected points per frame, so a slow GPU or a throttled CPU
 * shows up in the frame distribution.
 */
object VisualStage {

    data class SceneSample(
        val title: String,
        val avgFps: Double,
        val medianFrameMs: Double,
        val p95FrameMs: Double,
        val worstFrameMs: Double,
        val jankPercent: Double,
        val frames: Int,
        val durationMs: Long
    )

    var active by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set
    var detail by mutableStateOf("")
        private set
    var progress by mutableStateOf(0f)
        private set
    var fps by mutableStateOf(0.0)
        private set
    var tick by mutableStateOf(0L)
        private set

    /** 0 = compute field, 1 = particle torus, 2 = wave grid. */
    var variant by mutableStateOf(0)
        private set

    suspend fun runScene(
        title: String,
        detail: String,
        durationMs: Long,
        variant: Int
    ): SceneSample = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val intervals = ArrayList<Double>(4096)
            var last = 0L
            var start = 0L
            var frameCount = 0

            this@VisualStage.title = title
            this@VisualStage.detail = detail
            this@VisualStage.variant = variant
            this@VisualStage.progress = 0f
            this@VisualStage.fps = 0.0
            active = true

            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (start == 0L) start = frameTimeNanos
                    if (last != 0L) intervals.add((frameTimeNanos - last) / 1e6)
                    last = frameTimeNanos
                    frameCount++

                    val elapsedMs = (frameTimeNanos - start) / 1_000_000L
                    val fraction = (elapsedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
                    tick++
                    this@VisualStage.progress = fraction.toFloat()
                    this@VisualStage.fps = if (intervals.size > 8) {
                        1000.0 / Stats.median(intervals)
                    } else 0.0

                    if (elapsedMs < durationMs) {
                        Choreographer.getInstance().postFrameCallback(this)
                    } else {
                        active = false
                        val sorted = intervals.sorted()
                        val duration = (frameTimeNanos - start) / 1e6
                        val avgFps = if (duration > 0) intervals.size / (duration / 1000.0) else 0.0
                        val median = Stats.median(intervals)
                        val p95 = if (sorted.isEmpty()) 0.0
                        else sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                        val jank = if (intervals.isEmpty()) 0.0
                        else intervals.count { it > 16.667 * 1.6 } * 100.0 / intervals.size
                        cont.resume(
                            SceneSample(
                                title = title,
                                avgFps = avgFps,
                                medianFrameMs = median,
                                p95FrameMs = p95,
                                worstFrameMs = sorted.lastOrNull() ?: 0.0,
                                jankPercent = jank,
                                frames = intervals.size,
                                durationMs = duration.toLong()
                            )
                        )
                    }
                }
            }

            cont.invokeOnCancellation {
                active = false
                Choreographer.getInstance().removeFrameCallback(callback)
            }
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    /** Clears the overlay when a run is cancelled or crashes. */
    fun reset() {
        active = false
        progress = 0f
        fps = 0.0
        title = ""
        detail = ""
    }
}
