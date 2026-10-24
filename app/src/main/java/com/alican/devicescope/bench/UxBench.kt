package com.alican.devicescope.bench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Choreographer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Synthetic UI workload: draws a dense list of rounded cards every frame for a
 * few seconds and records real frame intervals from Choreographer. This measures
 * the same pipeline a scrolling app uses (layout-free canvas drawing, raster and
 * composition), not a theoretical fps number.
 */
class UxBench {

    data class UxResult(
        val avgFps: Double,
        val medianFrameMs: Double,
        val p95FrameMs: Double,
        val worstFrameMs: Double,
        val jankPercent: Double,
        val frames: Int,
        val durationMs: Long
    )

    suspend fun run(durationMs: Long = 4500): UxResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val bitmap = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#E6EDF5")
                textSize = 34f
            }
            val rect = RectF()
            val intervals = ArrayList<Double>(512)
            var lastFrame = 0L
            var start = 0L
            var counter = 0

            val callback = object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (bitmap.isRecycled) return
                    if (start == 0L) start = frameTimeNanos
                    if (lastFrame != 0L) {
                        intervals.add((frameTimeNanos - lastFrame) / 1e6)
                    }
                    lastFrame = frameTimeNanos

                    // ~420 rounded cards + text per frame
                    canvas.drawColor(Color.parseColor("#15191F"))
                    for (i in 0 until 420) {
                        val col = i % 3
                        val row = i / 3
                        val x = 24f + col * 344f
                        val y = ((row * 96 + (counter % 96)) % 1900).toFloat()
                        rect.set(x, y, x + 320f, y + 88f)
                        card.color = Color.parseColor(
                            when (i % 4) {
                                0 -> "#1B2027"
                                1 -> "#1E242C"
                                2 -> "#222933"
                                else -> "#181D24"
                            }
                        )
                        canvas.drawRoundRect(rect, 22f, 22f, card)
                        fill.color = Color.parseColor(if (i % 5 == 0) "#42C9A0" else "#2B333D")
                        canvas.drawCircle(x + 44f, y + 44f, 16f, fill)
                        canvas.drawText("Kayıt ${i + 1}", x + 78f, y + 54f, text)
                    }
                    counter++

                    if ((frameTimeNanos - start) / 1_000_000L < durationMs) {
                        Choreographer.getInstance().postFrameCallback(this)
                    } else {
                        bitmap.recycle()
                        val sorted = intervals.sorted()
                        val dur = (frameTimeNanos - start) / 1e6
                        val avgFps = if (dur > 0) intervals.size / (dur / 1000.0) else 0.0
                        val median = Stats.median(intervals)
                        val p95 = if (sorted.isEmpty()) 0.0 else sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
                        val refresh = 16.67
                        val jank = if (intervals.isEmpty()) 0.0
                        else intervals.count { it > refresh * 1.6 } * 100.0 / intervals.size
                        cont.resume(
                            UxResult(
                                avgFps = avgFps,
                                medianFrameMs = median,
                                p95FrameMs = p95,
                                worstFrameMs = sorted.lastOrNull() ?: 0.0,
                                jankPercent = jank,
                                frames = intervals.size,
                                durationMs = dur.toLong()
                            )
                        )
                    }
                }
            }
            Choreographer.getInstance().postFrameCallback(callback)
            cont.invokeOnCancellation { bitmap.recycle() }
        }
    }
}
