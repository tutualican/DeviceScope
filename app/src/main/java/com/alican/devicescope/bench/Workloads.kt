package com.alican.devicescope.bench

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.sqrt

/** Top-level grouping used by both suites. */
enum class BenchCat(val label: String) {
    CRYPTO("Kriptografi"),
    COMPRESS("Sıkıştırma"),
    PARSING("Ayrıştırma"),
    MATH("Matematik"),
    IMAGE("Görüntü"),
    RENDER("Işın izleme"),
    INTEGER("Tam sayı"),
    MEMORY("Bellek"),
    STORAGE("Depolama"),
    GPU("Grafik"),
    UX("Arayüz"),
    SUSTAINED("Isıl dayanım")
}

/**
 * One native workload.
 *
 * [refUnitsPerSec] is the normalisation baseline: a run producing exactly that
 * throughput scores 1000 points for this workload. Reference constants are
 * provisional until a calibration run is done on the reference devices, and the
 * UI always shows the raw metric next to the index so a score is never the only
 * evidence.
 */
enum class Workload(
    val id: Int,
    val title: String,
    val cat: BenchCat,
    val refUnitsPerSec: Double,
    val unit: String,
    val divisor: Double,
    val description: String
) {
    AES(0, "AES-128", BenchCat.CRYPTO, 62_000_000.0, "MB/s", 1_000_000.0,
        "FIPS-197 uyumlu AES-128 blok şifreleme, 1 MiB tampon"),
    LZ(1, "Sıkıştırma", BenchCat.COMPRESS, 46_000_000.0, "MB/s", 1_000_000.0,
        "LZ77 tarzı sıkıştırma, yarı yapılandırılmış 1 MiB veri"),
    JSON(2, "JSON ayrıştırma", BenchCat.PARSING, 41_000_000.0, "MB/s", 1_000_000.0,
        "Özyinelemeli JSON ayrıştırıcı, 4.000 kayıtlık belge"),
    MATRIX(3, "Matris çarpımı", BenchCat.MATH, 1_150_000_000.0, "GFLOP/s", 1_000_000_000.0,
        "160×160 float32 matris çarpımı"),
    IMAGE(4, "Görüntü filtresi", BenchCat.IMAGE, 26_000_000.0, "Mpix/s", 1_000_000.0,
        "768×768 Gauss bulanıklık + Sobel kenar, 3 geçiş"),
    RAY(5, "Işın izleme", BenchCat.RENDER, 620_000.0, "Mray/s", 1_000_000.0,
        "200×150, 4 örnek/piksel, 2 sekme, 9 küre"),
    INT(6, "Tam sayı işlemleri", BenchCat.INTEGER, 21_000_000.0, "Mop/s", 1_000_000.0,
        "4 Mi 64-bit eleman: xorshift, döndürme, popcount"),
    MEMORY(7, "Bellek bant genişliği", BenchCat.MEMORY, 9_000_000_000.0, "GB/s", 1_000_000_000.0,
        "32 MiB kopyala + topla + doldur (LPDDR ölçümü)");

    /** Workloads used for the CPU score (memory has its own category). */
    companion object {
        val CPU = listOf(AES, LZ, JSON, MATRIX, IMAGE, RAY, INT)
    }
}

/** Single measured workload result. */
data class Measurement(
    val workload: Workload,
    val threads: Int,
    val iterations: Int,
    val medianSeconds: Double,
    val throughput: Double,
    val unit: String,
    val index: Double,
    /** Coefficient of variation across repeats, in percent. */
    val cvPercent: Double,
    val checksumStable: Boolean,
    val repeats: Int,
    val warmupRepeats: Int
) {
    val stable: Boolean get() = cvPercent <= 3.0
    val raw: String
        get() = when (workload) {
            Workload.MATRIX -> String.format(java.util.Locale.US, "%.2f GFLOP/s", throughput / 1e9)
            Workload.MEMORY -> String.format(java.util.Locale.US, "%.2f GB/s", throughput / 1e9)
            Workload.IMAGE -> String.format(java.util.Locale.US, "%.1f Mpix/s", throughput / 1e6)
            Workload.RAY -> String.format(java.util.Locale.US, "%.2f Mray/s", throughput / 1e6)
            Workload.INT -> String.format(java.util.Locale.US, "%.1f Mop/s", throughput / 1e6)
            else -> String.format(java.util.Locale.US, "%.1f MB/s", throughput / 1e6)
        }
}

data class SysSample(
    val timestamp: Long,
    val cpuTemp: Float?,
    val batteryTemp: Float?,
    val maxCoreMhz: Long,
    val batteryPercent: Int
)

object Stats {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    fun mean(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    fun cvPercent(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val m = mean(values)
        if (m <= 0.0) return 0.0
        val variance = values.sumOf {(it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance) / m * 100.0
    }

    /** Geometric mean; used for category and total scores. */
    fun geoMean(values: List<Double>): Double {
        val valid = values.filter { it > 0.0 && !it.isNaN() }
        if (valid.isEmpty()) return 0.0
        return exp(valid.sumOf { ln(it) } / valid.size)
    }

    fun spreadPercent(a: Double, b: Double): Double {
        if (a <= 0.0) return 0.0
        return abs(a - b) / a * 100.0
    }
}
