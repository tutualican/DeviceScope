package com.alican.devicescope.bench

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * Repeatability: is the number stable enough to trust?
 *
 * Short suites are single-shot. The marathon can be run 1–3 times in a row; the
 * controller then reports the median and the half-range, and flags a spread above
 * 5 % as "unreliable". Even with a single run the new total is compared with the
 * newest saved marathon that used the same workload version, so a version or
 * condition change cannot look like a device change.
 *
 * Missing or unreadable previous files are skipped silently — the comparison is a
 * convenience, never a fabrication.
 */
data class RepeatStats(
    val totals: List<Double>,
    val median: Double,
    val halfRange: Double,
    val spreadPercent: Double,
    val unstable: Boolean
)

object Repeatability {

    /** Above this relative spread the runs are not considered repeatable. */
    const val UNSTABLE_SPREAD_PERCENT = 5.0

    fun summarise(totals: List<Double>): RepeatStats? {
        val valid = totals.filter { it > 0.0 && !it.isNaN() }
        if (valid.isEmpty()) return null
        val median = Stats.median(valid)
        val halfRange = (valid.max() - valid.min()) / 2.0
        val spread = if (median > 0) (valid.max() - valid.min()) / median * 100.0 else 0.0
        return RepeatStats(
            totals = valid,
            median = median,
            halfRange = halfRange,
            spreadPercent = spread,
            unstable = valid.size >= 2 && spread > UNSTABLE_SPREAD_PERCENT
        )
    }

    /**
     * Compares [result] with the newest saved marathon file that used the same
     * workload version. Returns a warning string, or null when there is nothing
     * comparable (no previous run, different workload version, unreadable file).
     */
    fun compareWithPrevious(context: Context, result: MarathonResult): String? {
        val file = ResultStore.list(context)
            .firstOrNull { it.name.startsWith("maraton_") && it.name != "maraton_${ResultStore.stamp(result.timestamp)}.json" }
            ?: return null
        val json = ResultStore.read(file) ?: return null
        val previous = try {
            JSONObject(json)
        } catch (_: Throwable) {
            return null
        }
        val previousVersion = previous.optInt("workloadVersion", -1)
        if (previousVersion != result.workloadVersion) {
            return if (previousVersion < 0) {
                "Önceki koşu iş yükü sürümü taşımıyor (eski dosya); kıyaslanmadı"
            } else {
                null
            }
        }
        val previousTotal = previous.optJSONObject("scores")?.optDouble("total", 0.0) ?: 0.0
        if (previousTotal <= 0.0 || result.total <= 0.0) return null
        val diff = kotlin.math.abs(result.total - previousTotal) /
            kotlin.math.max(result.total, previousTotal) * 100.0
        if (diff <= 3.0) return null
        val direction = if (result.total > previousTotal) "yüksek" else "düşük"
        return String.format(
            Locale.US,
            "Önceki koşuya göre %.1f%% %s (%.0f → %.0f) — koşullar aynı mı?",
            diff,
            direction,
            previousTotal,
            result.total
        )
    }
}
