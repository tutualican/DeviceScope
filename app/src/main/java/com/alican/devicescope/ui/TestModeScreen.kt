package com.alican.devicescope.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alican.devicescope.TestMode
import com.alican.devicescope.bench.BenchController
import com.alican.devicescope.bench.VisualStage
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Full-bleed test mode.
 *
 * While a marathon runs the normal app UI is replaced by this screen: a live
 * progress report around an animated stage. During the visual phase the stage is
 * the actual measured workload ([VisualStage] records the Choreographer frames
 * this canvas produces), so the fps number on screen and the UX score in the
 * report come from the same frames.
 *
 * During CPU phases the stage falls back to a calm field and the canvas requests
 * no new frames, so the visual test cannot inflate or distort the CPU numbers.
 */
@Composable
fun TestModeScreen(bench: BenchController) {
    val p = LocalSoft.current
    val live = bench.live()

    // A one-second tick keeps the elapsed/remaining clocks honest without
    // recomposing the whole screen on every frame.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val elapsed = ((now - TestMode.startedAt) / 1000L).coerceAtLeast(0L)
    val total = TestMode.minutes * 60L
    val remaining = (total - elapsed).coerceAtLeast(0L)

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF07080B), Color(0xFF0C1017), Color(0xFF080A0E))
                )
            )
    ) {
        if (VisualStage.active) {
            StageCanvas(
                variant = VisualStage.variant,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AmbientField(Modifier.fillMaxSize())
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 22.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SoftChip("TEST MODU", p.warm)
                Column(horizontalAlignment = Alignment.End) {
                    SoftText(
                        clock(elapsed) + "  /  " + clock(total),
                        size = 14,
                        color = Color.White,
                        mono = true,
                        weight = FontWeight.SemiBold
                    )
                    SoftText(
                        if (remaining > 0) "kalan ${clock(remaining)}" else "tamamlanıyor",
                        size = 11,
                        color = Color(0xFF8A94A6)
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Column(Modifier.fillMaxWidth()) {
                SoftText(
                    (VisualStage.title.takeIf { it.isNotBlank() } ?: TestMode.phase).uppercase(),
                    size = 11,
                    color = p.accent,
                    weight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                SoftText(
                    "${(TestMode.progress.coerceIn(0f, 1f) * 100).toInt()}",
                    size = 76,
                    color = Color.White,
                    weight = FontWeight.Bold
                )
                SoftText(
                    VisualStage.detail.takeIf { it.isNotBlank() } ?: TestMode.detail,
                    size = 13,
                    color = Color(0xFF9BA6B7),
                    maxLines = 3
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Metric("CPU", live.cpuTemp?.let { "%.1f°".format(it) } ?: "—")
                Metric("BATARYA", live.batteryTemp?.let { "%.1f°".format(it) } ?: "—")
                Metric("SAAT", if (live.freqMhz > 0) "${live.freqMhz} MHz" else "—")
                FpsMetric()
            }

            Spacer(Modifier.height(16.dp))
            SoftBar(TestMode.progress.coerceIn(0f, 1f), color = p.accent, height = 6.dp)

            Spacer(Modifier.height(10.dp))
            SoftText(
                "Ekran açık · parlaklık %100 · bildirimden durdurulabilir",
                size = 10,
                color = Color(0xFF68717F)
            )

            Spacer(Modifier.height(14.dp))
            SoftButton(
                label = "Testi durdur",
                onClick = { bench.cancelMarathon() },
                modifier = Modifier.fillMaxWidth(),
                danger = true
            )
        }
    }
}

@Composable
private fun FpsMetric() {
    // Reading fps here (not in the parent) scopes recomposition to this label:
    // the visual stage must measure the drawing, not our own recomposition.
    val fps = VisualStage.fps
    val active = VisualStage.active
    Metric("FPS", if (active && fps > 0) "%.0f".format(fps) else "—")
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        SoftText(label, size = 9, color = Color(0xFF6F7A8B), weight = FontWeight.SemiBold)
        Spacer(Modifier.height(3.dp))
        SoftText(value, size = 14, color = Color.White, mono = true)
    }
}

private fun clock(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/**
 * The animated stage. Reads [VisualStage.tick] inside the draw lambda, so a new
 * frame is invalidated exactly when the recorder posts one — the canvas cannot
 * run ahead of the measurement.
 */
@Composable
private fun StageCanvas(variant: Int, modifier: Modifier = Modifier) {
    val tick = VisualStage.tick
    Canvas(modifier) {
        drawStage(variant, tick)
    }
}

private fun DrawScope.drawStage(variant: Int, tick: Long) {
    val t = tick * 0.045f
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h * 0.46f
    val radius = min(w, h) * 0.34f

    // Soft backlight that follows the rotation.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0x5538E1B0),
                Color(0x221E6BFF),
                Color(0x00000000)
            ),
            center = Offset(cx + cos(t * 0.5f) * radius * 0.25f, cy),
            radius = radius * 1.5f
        ),
        radius = radius * 1.5f,
        center = Offset(cx, cy)
    )

    when (variant) {
        2 -> drawWaveGrid(t, cx, cy, w, h)
        else -> drawParticleTorus(t, cx, cy, radius, variant)
    }
}

private fun DrawScope.drawParticleTorus(t: Float, cx: Float, cy: Float, radius: Float, variant: Int) {
    val count = if (variant == 0) 420 else 640
    val points = ArrayList<Offset>(count)
    val colors = ArrayList<Color>(count)
    val widths = ArrayList<Float>(count)

    for (i in 0 until count) {
        val golden = 2.399963f
        val a = i * golden + t * 0.7f
        val band = (i % 9) / 9f
        val ringRadius = radius * (0.30f + band * 0.82f)
        val depth = sin(a * 1.3f + t * 0.9f)
        val flatten = 0.52f + 0.22f * cos(t * 0.4f)
        val x = cx + cos(a) * ringRadius * (0.82f + 0.18f * depth)
        val y = cy + sin(a) * ringRadius * flatten
        val z = (depth + 1f) / 2f

        points.add(Offset(x, y))
        colors.add(
            when {
                z > 0.72f -> Color(0xCC7DF9E0)
                z > 0.46f -> Color(0xAA4FD1FF)
                else -> Color(0x66255C8A)
            }
        )
        widths.add(1.5f + z * 4.5f)
    }

    points.forEachIndexed { i, point ->
        drawCircle(colors[i], radius = widths[i], center = point)
    }

    // Two intersecting orbit rings give the scene a readable 3D axis.
    for (ring in 0..1) {
        val tilt = if (ring == 0) 0.28f else -0.30f
        var previous: Offset? = null
        for (step in 0..120) {
            val a = step / 120f * 2f * PI.toFloat() + t * (if (ring == 0) 0.5f else -0.35f)
            val rr = radius * (1.02f + ring * 0.16f)
            val x = cx + cos(a) * rr
            val y = cy + sin(a) * rr * (0.34f + tilt * 0.1f)
            val point = Offset(x, y)
            previous?.let {
                drawLine(
                    color = if (ring == 0) Color(0x5538E1B0) else Color(0x442F7CFF),
                    start = it,
                    end = point,
                    strokeWidth = 1.4f
                )
            }
            previous = point
        }
    }

    // Scanning sweep line: makes dropped frames obvious to the eye.
    val phase = (t * 0.11f) % 1f
    val sweepFraction = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
    val sweep = sweepFraction * size.height
    drawLine(
        color = Color(0x33FFFFFF),
        start = Offset(0f, sweep),
        end = Offset(size.width, sweep),
        strokeWidth = 1f
    )
}

private fun DrawScope.drawWaveGrid(t: Float, cx: Float, cy: Float, w: Float, h: Float) {
    val cols = 26
    val rows = 18
    val spanX = w * 0.94f
    val spanY = h * 0.44f
    val points = ArrayList<Offset>(cols * rows)
    val colors = ArrayList<Color>(cols * rows)
    val widths = ArrayList<Float>(cols * rows)

    for (row in 0 until rows) {
        val fy = row / (rows - 1f)
        val depth = 0.35f + fy * 0.65f
        val y = cy - spanY / 2f + fy * spanY
        for (col in 0 until cols) {
            val fx = col / (cols - 1f)
            val x = cx - spanX / 2f + fx * spanX
            val wave = sin(fx * 9f + t * 1.4f + row * 0.22f) *
                cos(fy * 6f - t * 1.1f) * 16f * depth
            points.add(Offset(x, y + wave))
            colors.add(
                if (wave > 6f) Color(0xBB7DF9E0)
                else if (wave > -4f) Color(0x884FD1FF)
                else Color(0x55255C8A)
            )
            widths.add(1.4f + (wave + 16f) / 32f * 3.4f)
        }
    }

    // Connect rows: a single pass of short lines, still cheap enough for 60 fps.
    for (row in 0 until rows) {
        for (col in 0 until cols - 1) {
            val i = row * cols + col
            drawLine(
                color = Color(0x2238E1B0),
                start = points[i],
                end = points[i + 1],
                strokeWidth = 1f
            )
        }
    }
    points.forEachIndexed { i, point ->
        drawCircle(colors[i], radius = widths[i], center = point)
    }
}

/** Calm, static field shown between measured phases (no frame requests). */
@Composable
private fun AmbientField(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.46f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2A1E6BFF), Color(0x001E6BFF)),
                center = Offset(cx, cy),
                radius = size.minDimension * 0.7f
            ),
            radius = size.minDimension * 0.7f,
            center = Offset(cx, cy)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x2238E1B0), Color(0x0038E1B0)),
                center = Offset(cx * 0.8f, cy * 1.15f),
                radius = size.minDimension * 0.5f
            ),
            radius = size.minDimension * 0.5f,
            center = Offset(cx * 0.8f, cy * 1.15f)
        )
    }
}
