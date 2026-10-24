package com.alican.devicescope.bench

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Full descriptive statistics for a measured series.
 *
 * The 95% confidence interval uses the Student-t distribution (not 1.96) because
 * n is small; the t quantile is looked up for df = 1..30 and 1.96 is used above.
 * Outliers are counted with the IQR rule (outside Q1-1.5*IQR / Q3+1.5*IQR).
 */
data class SeriesStat(
    val label: String,
    val n: Int,
    val median: Double,
    val mean: Double,
    val sd: Double,
    val cvPercent: Double,
    val ciLow: Double,
    val ciHigh: Double,
    val outliers: Int,
    val unit: String
) {
    val ciHalfWidth: Double get() = (ciHigh - ciLow) / 2.0
}

object StatsExt {

    private val t975 = doubleArrayOf(
        0.0, 12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306, 2.262,
        2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120, 2.110, 2.101, 2.093,
        2.086, 2.080, 2.074, 2.069, 2.064, 2.060, 2.056, 2.052, 2.048, 2.045,
        2.042
    )

    fun tQuantile(df: Int): Double = when {
        df < 1 -> 12.706
        df < t975.size -> t975[df]
        else -> 1.96
    }

    fun median(v: List<Double>): Double = Stats.median(v)

    fun sd(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.sum() / v.size
        val variance = v.sumOf { (it - m) * (it - m) } / (v.size - 1)
        return sqrt(variance)
    }

    fun cvPercent(v: List<Double>): Double {
        if (v.size < 2) return 0.0
        val m = v.sum() / v.size
        if (m <= 0.0) return 0.0
        return sd(v) / m * 100.0
    }

    fun quantile(sorted: List<Double>, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val pos = (sorted.size - 1) * q.coerceIn(0.0, 1.0)
        val lo = pos.toInt()
        val hi = (lo + 1).coerceAtMost(sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] * (1 - frac) + sorted[hi] * frac
    }

    fun outlierCount(v: List<Double>): Int {
        if (v.size < 4) return 0
        val sorted = v.sorted()
        val q1 = quantile(sorted, 0.25)
        val q3 = quantile(sorted, 0.75)
        val iqr = q3 - q1
        if (iqr <= 0.0) return 0
        val low = q1 - 1.5 * iqr
        val high = q3 + 1.5 * iqr
        return v.count { it < low || it > high }
    }

    fun describe(label: String, values: List<Double>, unit: String): SeriesStat {
        if (values.isEmpty()) {
            return SeriesStat(label, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0, unit)
        }
        val m = values.sum() / values.size
        val s = sd(values)
        val df = (values.size - 1).coerceAtLeast(1)
        val half = if (values.size > 1) tQuantile(df) * s / sqrt(values.size.toDouble()) else 0.0
        return SeriesStat(
            label = label,
            n = values.size,
            median = median(values),
            mean = m,
            sd = s,
            cvPercent = cvPercent(values),
            ciLow = m - half,
            ciHigh = m + half,
            outliers = outlierCount(values),
            unit = unit
        )
    }

    /**
     * Linear fit of y against x. Returns (slope, intercept, r2). Used for the
     * size-scaling check: duration should grow linearly with problem size.
     */
    fun linearFit(x: List<Double>, y: List<Double>): Triple<Double, Double, Double> {
        val n = minOf(x.size, y.size)
        if (n < 2) return Triple(0.0, 0.0, 0.0)
        val mx = x.take(n).sum() / n
        val my = y.take(n).sum() / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = y[i] - my
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        if (sxx <= 0.0) return Triple(0.0, my, 0.0)
        val slope = sxy / sxx
        val intercept = my - slope * mx
        val r2 = if (syy > 0.0) (sxy * sxy) / (sxx * syy) else 0.0
        return Triple(slope, intercept, r2)
    }

    fun percentDiff(a: Double, b: Double): Double = if (a > 0.0) abs(b - a) / a * 100.0 else 0.0

    /** Spearman rank correlation with tie-aware average ranks. */
    fun spearman(xs: List<Double>, ys: List<Double>): Double {
        val n = minOf(xs.size, ys.size)
        if (n < 3) return Double.NaN
        val rx = ranks(xs.take(n))
        val ry = ranks(ys.take(n))
        val mx = rx.sum() / n
        val my = ry.sum() / n
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val dx = rx[i] - mx
            val dy = ry[i] - my
            sxy += dx * dy
            sxx += dx * dx
            syy += dy * dy
        }
        if (sxx <= 0.0 || syy <= 0.0) return Double.NaN
        return sxy / sqrt(sxx * syy)
    }

    /** Average ranks (1-based), ties share the mean of their positions. */
    fun ranks(v: List<Double>): DoubleArray {
        val idx = v.indices.sortedBy { v[it] }
        val out = DoubleArray(v.size)
        var i = 0
        while (i < idx.size) {
            var j = i
            while (j + 1 < idx.size && v[idx[j + 1]] == v[idx[i]]) j++
            val avg = (i + j) / 2.0 + 1.0
            for (k in i..j) out[idx[k]] = avg
            i = j + 1
        }
        return out
    }
}
