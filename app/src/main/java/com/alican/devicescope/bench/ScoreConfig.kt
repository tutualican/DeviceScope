package com.alican.devicescope.bench

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/**
 * Score weights, read from assets/scoring.json.
 *
 * The combined score is a weighted mean of group scores; each group is the
 * geometric mean of its measured parts. Groups whose data is missing are dropped
 * and the remaining weights renormalise, so a run without a GPU score is not
 * silently penalised.
 *
 * Because the weights are configuration, the app can also answer the question
 * "how fragile is the ranking?" by shifting each weight and recomputing.
 */
class ScoreConfig(
    val cpuWeight: Double,
    val gpuWeight: Double,
    val memoryWeight: Double,
    val uxWeight: Double
) {
    val totalWeight: Double get() = cpuWeight + gpuWeight + memoryWeight + uxWeight

    data class Inputs(
        val single: Double? = null,
        val multi: Double? = null,
        val memory: Double? = null,
        val storage: Double? = null,
        val gpu: Double? = null,
        val ux: Double? = null
    )

    data class Breakdown(
        val cpu: Double?,
        val gpu: Double?,
        val memory: Double?,
        val ux: Double?,
        val combined: Double
    )

    fun breakdown(i: Inputs): Breakdown {
        val cpu = geo(i.single, i.multi)
        val gpu = i.gpu?.takeIf { it > 0 }
        val memory = geo(i.memory, i.storage)
        val ux = i.ux?.takeIf { it > 0 }

        val parts = ArrayList<Pair<Double, Double>>(4)
        if (cpu != null) parts.add(cpuWeight to cpu)
        if (gpu != null) parts.add(gpuWeight to gpu)
        if (memory != null) parts.add(memoryWeight to memory)
        if (ux != null) parts.add(uxWeight to ux)

        val wsum = parts.sumOf { it.first }
        val combined = if (wsum > 0) parts.sumOf { it.first * it.second } / wsum else 0.0
        return Breakdown(cpu, gpu, memory, ux, combined)
    }

    /** Shifts one weight by [deltaPoints] (0.05 = five percentage points). */
    fun shifted(group: String, deltaPoints: Double): ScoreConfig = when (group) {
        "cpu" -> ScoreConfig((cpuWeight + deltaPoints).coerceAtLeast(0.01), gpuWeight, memoryWeight, uxWeight)
        "gpu" -> ScoreConfig(cpuWeight, (gpuWeight + deltaPoints).coerceAtLeast(0.01), memoryWeight, uxWeight)
        "memory" -> ScoreConfig(cpuWeight, gpuWeight, (memoryWeight + deltaPoints).coerceAtLeast(0.01), uxWeight)
        else -> ScoreConfig(cpuWeight, gpuWeight, memoryWeight, (uxWeight + deltaPoints).coerceAtLeast(0.01))
    }

    data class SensitivityRow(
        val group: String,
        val deltaPoints: Double,
        val scoreChangePercent: Double,
        val rankChanges: Int,
        val note: String
    )

    companion object {

        private fun geo(a: Double?, b: Double?): Double? {
            val v = listOfNotNull(a, b).filter { it > 0.0 }
            return if (v.isEmpty()) null else Stats.geoMean(v)
        }

        fun load(context: Context): ScoreConfig {
            val defaults = ScoreConfig(0.40, 0.30, 0.15, 0.15)
            return try {
                val text = context.assets.open("scoring.json").bufferedReader().use { it.readText() }
                val j = JSONObject(text).optJSONObject("weights") ?: return defaults
                ScoreConfig(
                    cpuWeight = j.optDouble("cpu", 0.40),
                    gpuWeight = j.optDouble("gpu", 0.30),
                    memoryWeight = j.optDouble("memory", 0.15),
                    uxWeight = j.optDouble("ux", 0.15)
                )
            } catch (t: Throwable) {
                defaults
            }
        }

        /** True when assets/scoring.json was parsed instead of the built-in defaults. */
        fun loadedFromConfig(context: Context): Boolean = try {
            context.assets.open("scoring.json").use { it.read() > 0 }
        } catch (t: Throwable) {
            false
        }

        /**
         * Recomputes every run under shifted weights and reports how much the
         * combined score moves and how many rank pairs flip.
         */
        fun sensitivity(config: ScoreConfig, runs: List<Pair<String, Inputs>>): List<SensitivityRow> {
            if (runs.isEmpty()) return emptyList()
            val base = runs.map { it.second }.map { config.breakdown(it).combined }
            val baseRank = rankOf(base)
            val out = ArrayList<SensitivityRow>()
            for (group in listOf("cpu", "gpu", "memory", "ux")) {
                for (delta in listOf(-0.05, 0.05)) {
                    val alt = config.shifted(group, delta)
                    val altScores = runs.map { alt.breakdown(it.second).combined }
                    val altRank = rankOf(altScores)
                    var flips = 0
                    for (i in baseRank.indices) {
                        for (j in i + 1 until baseRank.size) {
                            val before = baseRank[i] - baseRank[j]
                            val after = altRank[i] - altRank[j]
                            if (before != 0.0 && after != 0.0 && (before > 0) != (after > 0)) flips++
                        }
                    }
                    val meanBase = base.average()
                    val meanAlt = altScores.average()
                    val change = if (meanBase > 0) abs(meanAlt - meanBase) / meanBase * 100.0 else 0.0
                    out.add(
                        SensitivityRow(
                            group = group,
                            deltaPoints = delta,
                            scoreChangePercent = change,
                            rankChanges = flips,
                            note = when {
                                runs.size < 2 -> "Tek kayıt: sıralama testi anlamsız"
                                flips > 0 -> "Sıralama değişti: puanlama bu ağırlığa duyarlı"
                                change > 2.0 -> "Sıralama sabit ama puan %2'den fazla kaydı"
                                else -> "Kararlı"
                            }
                        )
                    )
                }
            }
            return out
        }

        private fun rankOf(scores: List<Double>): DoubleArray {
            val idx = scores.indices.sortedByDescending { scores[it] }
            val rank = DoubleArray(scores.size)
            var i = 0
            while (i < idx.size) {
                var j = i
                while (j + 1 < idx.size && scores[idx[j + 1]] == scores[idx[i]]) j++
                val avg = (i + j) / 2.0 + 1.0
                for (k in i..j) rank[idx[k]] = avg
                i = j + 1
            }
            return rank
        }
    }
}
