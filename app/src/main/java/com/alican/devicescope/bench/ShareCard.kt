package com.alican.devicescope.bench

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Renders a shareable result card as a real PNG.
 *
 * Drawn with the platform Canvas so the layout is deterministic and does not
 * depend on Compose capture APIs; the visual language matches the Soft UI theme
 * (rounded plates, opposing light/dark edges, single accent colour).
 */
object ShareCard {

    private const val W = 1080
    private const val H = 1350

    private val bg = Color.parseColor("#1B2027")
    private val plate = Color.parseColor("#222933")
    private val plateInset = Color.parseColor("#191E25")
    private val accent = Color.parseColor("#42C9A0")
    private val cool = Color.parseColor("#5AA9E6")
    private val text = Color.parseColor("#E6EDF5")
    private val text2 = Color.parseColor("#A7B4C2")
    private val text3 = Color.parseColor("#71808F")

    fun render(
        context: Context,
        suite: String,
        score: Double,
        subtitle: String,
        rows: List<Pair<String, String>>,
        footer: String
    ): File {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "devicescope-${suite.lowercase(Locale.US)}-${System.currentTimeMillis()}.png")

        val bitmap = android.graphics.Bitmap.createBitmap(W, H, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, W.toFloat(), H.toFloat(),
                intArrayOf(Color.parseColor("#1E242C"), bg, Color.parseColor("#151A20")),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bgPaint)

        // ---- header plate -------------------------------------------------
        plate(c, 60f, 60f, W - 60f, 470f)

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.18f
        }
        c.drawText(suite.uppercase(Locale.US), 108f, 130f, label)

        val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text
            textSize = 150f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val scoreText = String.format(Locale.US, "%.1f", score)
        c.drawText(scoreText, 108f, 300f, scorePaint)

        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text3
            textSize = 34f
        }
        c.drawText("puan", 108f + scorePaint.measureText(scoreText) + 20f, 300f, unitPaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text2
            textSize = 30f
        }
        c.drawText(subtitle, 108f, 355f, subPaint)

        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text3
            textSize = 26f
        }
        c.drawText(
            java.text.SimpleDateFormat("d MMMM yyyy · HH:mm", Locale("tr", "TR"))
                .format(java.util.Date()),
            108f, 415f, datePaint
        )

        // ---- metric plates ------------------------------------------------
        var y = 510f
        val rowH = 96f
        rows.take(7).forEachIndexed { index, row ->
            plate(c, 60f, y, W - 60f, y + rowH - 16f)
            val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text2
                textSize = 30f
            }
            val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (index == 0) accent else text
                textSize = 32f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            c.drawText(row.first, 108f, y + 50f, keyPaint)
            val v = row.second
            val width = valuePaint.measureText(v)
            c.drawText(v, W - 108f - width, y + 50f, valuePaint)
            y += rowH
        }

        // ---- footer -------------------------------------------------------
        val footPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = text3
            textSize = 25f
        }
        wrap(c, footer, 108f, (H - 130f), W - 216f, 34f, footPaint)

        val brand = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        c.drawText("DEVICESCOPE · MİKYAS + KİNETİK", 108f, H - 62f, brand)

        FileOutputStream(file).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return file
    }

    fun share(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Soft UI plate: body, dark inner edge top-left, light edge bottom-right. */
    private fun plate(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val rect = RectF(l, t, r, b)
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = plate
            shader = LinearGradient(
                l, t, r, b,
                intArrayOf(Color.parseColor("#262E39"), Color.parseColor("#1D232B")),
                null,
                Shader.TileMode.CLAMP
            )
        }
        c.drawRoundRect(rect, 44f, 44f, body)

        val light = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#333D49")
        }
        c.drawRoundRect(RectF(l + 1f, t + 1f, r - 1f, b - 1f), 44f, 44f, light)

        val inset = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = plateInset
        }
        c.drawRoundRect(RectF(l + 3f, t + 3f, r - 3f, b - 3f), 42f, 42f, inset)
    }

    private fun wrap(c: Canvas, s: String, x: Float, y: Float, maxWidth: Float, lineH: Float, paint: Paint) {
        var line = StringBuilder()
        var cy = y
        for (word in s.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth && line.isNotEmpty()) {
                c.drawText(line.toString(), x, cy, paint)
                line = StringBuilder(word)
                cy += lineH
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) c.drawText(line.toString(), x, cy, paint)
    }
}
