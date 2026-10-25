package com.alican.devicescope.bench

/**
 * Stability analysis, split out of the runner so it can be unit tested without
 * an Android device.
 *
 * Rules (all of them are visible in the result file):
 *  - warm-up rounds (the first [warmupMs]) are ignored;
 *  - contaminated rounds (screen off, other process busy) are ignored;
 *  - single-core and multi-core rounds are analysed separately, because they
 *    have different variance and mixing them invents fluctuation;
 *  - retention compares the last window with the *best* window, not the first
 *    third. A slow start therefore cannot inflate the number;
 *  - a raw ratio above 100 % is clamped and flagged as [StabilityStats.slowStart];
 *  - the score has two visible parts: retention (60 %) and consistency (40 %).
 */
internal fun analyseStability(rounds: List<RoundSample>, warmupMs: Long): StabilityStats {
    val warmupExcluded = rounds.count { it.elapsedMs < warmupMs }
    val contaminatedExcluded = rounds.count { it.contaminated }
    val usable = rounds.filter {
        !it.contaminated && it.index > 0 && it.elapsedMs >= warmupMs
    }

    fun typeSeries(single: Boolean): List<Double> =
        usable.filter { it.singleCore == single }.map { it.index }

    val singleSeries = typeSeries(true)
    val multiSeries = typeSeries(false)

    fun windowStats(series: List<Double>): WindowStats {
        if (series.size < 6) return WindowStats(null, null, null)
        val window = (series.size / 5).coerceIn(4, 40).coerceAtMost(series.size)
        val first = Stats.median(series.take(window))
        val last = Stats.median(series.takeLast(window))
        var best = Double.NEGATIVE_INFINITY
        var i = 0
        while (i + window <= series.size) {
            val m = Stats.median(series.subList(i, i + window))
            if (m > best) best = m
            i += (window / 2).coerceAtLeast(1)
        }
        return WindowStats(best, last, first)
    }

    val singleWindow = windowStats(singleSeries)
    val multiWindow = windowStats(multiSeries)

    fun windowRatio(w: WindowStats): Double? =
        if (w.best != null && w.last != null && w.best > 0) w.last / w.best * 100.0 else null

    val ratios = listOfNotNull(windowRatio(singleWindow), windowRatio(multiWindow))
    val rawRetention = if (ratios.isNotEmpty()) ratios.average() else null
    // The best-window reference caps the raw ratio at 100 % by construction.
    // "Slow start" therefore also covers the real slow-start shape: a first
    // window more than 5 % below the best window (the device was still settling
    // when measurement began, or the ratio guard above would have fired).
    val slowStart = (rawRetention != null && rawRetention > 105.0) ||
        listOfNotNull(
            singleWindow.first to singleWindow.best,
            multiWindow.first to multiWindow.best
        ).any { (first, best) ->
            first != null && best != null && best > 0 && first < best * 0.95
        }
    val retention = rawRetention?.coerceAtMost(100.0)

    val singleCv = if (singleSeries.size >= 3) Stats.cvPercent(singleSeries) else 0.0
    val multiCv = if (multiSeries.size >= 3) Stats.cvPercent(multiSeries) else 0.0
    val combinedCv = when {
        singleSeries.size >= 3 && multiSeries.size >= 3 ->
            (singleCv * singleSeries.size + multiCv * multiSeries.size) /
                (singleSeries.size + multiSeries.size)
        singleSeries.size >= 3 -> singleCv
        multiSeries.size >= 3 -> multiCv
        else -> 0.0
    }

    val retentionScore = if (retention != null) 1000.0 * (retention / 100.0) else 0.0
    val consistencyScore = 1000.0 * (1.0 - (combinedCv / 100.0).coerceIn(0.0, 0.35))
    val score = if (retention != null) {
        (0.6 * retentionScore + 0.4 * consistencyScore).coerceIn(0.0, 1100.0)
    } else {
        0.0
    }

    return StabilityStats(
        retentionPercent = retention,
        retentionRawPercent = rawRetention,
        bestWindowIndex = listOfNotNull(singleWindow.best, multiWindow.best)
            .takeIf { it.isNotEmpty() }?.average(),
        lastWindowIndex = listOfNotNull(singleWindow.last, multiWindow.last)
            .takeIf { it.isNotEmpty() }?.average(),
        retentionScore = retentionScore,
        consistencyScore = consistencyScore,
        score = score,
        singleCv = singleCv,
        multiCv = multiCv,
        combinedCv = combinedCv,
        slowStart = slowStart,
        warmupExcludedRounds = warmupExcluded,
        contaminatedRounds = contaminatedExcluded,
        validSingleRounds = singleSeries.size,
        validMultiRounds = multiSeries.size,
        bestWindowRetentionPercent = rawRetention
    )
}

private data class WindowStats(val best: Double?, val last: Double?, val first: Double?)
