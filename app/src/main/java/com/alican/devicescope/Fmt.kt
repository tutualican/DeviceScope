package com.alican.devicescope

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToLong

object Fmt {

    private val TR = Locale.forLanguageTag("tr-TR")

    private val MONTHS = arrayOf(
        "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"
    )

    fun date(ms: Long): String {
        if (ms <= 0L) return "—"
        val d = Date(ms)
        val cal = java.util.Calendar.getInstance(TR).apply { time = d }
        val sb = StringBuilder()
        sb.append(cal.get(java.util.Calendar.DAY_OF_MONTH)).append(' ')
        sb.append(MONTHS[cal.get(java.util.Calendar.MONTH)]).append(' ')
        sb.append(cal.get(java.util.Calendar.YEAR))
        sb.append(", ")
        sb.append(String.format(TR, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE)))
        return sb.toString()
    }

    fun dateShort(ms: Long): String {
        if (ms <= 0L) return "—"
        return SimpleDateFormat("dd.MM.yyyy", TR).format(Date(ms))
    }

    fun time(ms: Long): String = SimpleDateFormat("HH:mm:ss", TR).format(Date(ms))

    fun bytes(b: Long): String {
        if (b <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble()
        var i = 0
        while (v >= 1024.0 && i < units.size - 1) {
            v /= 1024.0
            i++
        }
        val pattern = if (v >= 100) "%.0f %s" else if (v >= 10) "%.1f %s" else "%.2f %s"
        return String.format(TR, pattern, v, units[i])
    }

    fun gb(b: Long): String = String.format(TR, "%.1f GB", b / 1024.0 / 1024.0 / 1024.0)

    fun duration(ms: Long): String {
        if (ms <= 0L) return "—"
        val s = ms / 1000
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "$d gün $h saat"
            h > 0 -> "$h saat $m dk"
            m > 0 -> "$m dk"
            else -> "$s sn"
        }
    }

    fun days(ms: Long): Long = ms / 86_400_000L

    fun num(v: Double, decimals: Int = 1): String =
        String.format(TR, "%.${decimals}f", v)

    fun mhzFromKhz(khz: Long?): String =
        if (khz == null || khz <= 0L) "—" else String.format(TR, "%.2f GHz", khz / 1_000_000.0)

    fun mhz(mhz: Long?): String =
        if (mhz == null || mhz <= 0L) "—" else "$mhz MHz"

    fun tempText(c: Float?, decimals: Int = 1): String =
        if (c == null || c < -40f || c > 200f) "—" else String.format(TR, "%.${decimals}f °C", c)

    fun tempValue(c: Float?): String =
        if (c == null || c < -40f || c > 200f) "—" else String.format(TR, "%.1f", c)

    fun mp(w: Int, h: Int): String =
        String.format(TR, "%.1f MP", w.toDouble() * h / 1_000_000.0)

    fun percent(v: Double): String = String.format(TR, "%.0f%%", v)

    fun round1(v: Double): String = String.format(TR, "%.1f", v)

    fun round2(v: Double): String = String.format(TR, "%.2f", v)

    fun pnp(code: String): String = when (code) {
        "BOE" -> "BOE Technology"
        "AUO" -> "AU Optronics"
        "SEC" -> "Samsung Display"
        "SAM" -> "Samsung"
        "LGD" -> "LG Display"
        "LPL" -> "LG Philips LCD"
        "CSO" -> "China Star Optoelectronics (CSOT)"
        "INX" -> "Innolux"
        "TMX" -> "Tianma Microelectronics"
        "TMA" -> "Tianma Microelectronics"
        "HKC" -> "HKC Display"
        "DSJ" -> "Displays Japan"
        "SHP" -> "Sharp"
        "HSD" -> "HannStar Display"
        "CPT" -> "Chunghwa Picture Tubes"
        "EDO" -> "EDO"
        "IVO" -> "InfoVision Optoelectronics"
        "JDI" -> "Japan Display"
        "NVT" -> "Novatek"
        "QCI" -> "Qualcomm"
        "VSD" -> "Vestel"
        else -> code
    }

    fun clampTemp(c: Double): Double = when {
        c < -40 -> -40.0
        c > 200 -> 200.0
        else -> c
    }

    fun roundTo(v: Double, step: Int): Long =
        (v / step).roundToLong() * step
}
