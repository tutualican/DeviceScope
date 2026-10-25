package com.alican.devicescope.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stability rules that used to be untestable inside the runner.
 *
 * These tests pin the promises: warm-up excluded, contaminated excluded, CV per
 * workload type, retention measured against the best window (never above 100 %),
 * and a two-part score.
 */
class StabilityTest {

    private fun round(
        i: Int,
        single: Boolean,
        index: Double,
        contaminated: Boolean = false,
        elapsedMs: Long = i * 2_500L
    ) = RoundSample(
        timestamp = 1_000_000L + elapsedMs,
        elapsedMs = elapsedMs,
        singleCore = single,
        index = index,
        cpuTempC = 50f,
        cpuAvgC = 45f,
        batteryTempC = 30f,
        freqMhz = 2000,
        coreFreqMhz = listOf(2000L),
        thermalStatus = 0,
        headroom = 1f,
        otherBusyCores = if (contaminated) 2.0 else 0.1,
        contaminated = contaminated,
        contamination = if (contaminated) "test" else null,
        watts = 5.0,
        currentMa = 1000.0,
        roundSeconds = 2.5
    )

    @Test
    fun `warm-up and contaminated rounds are excluded and counted`() {
        val rounds = (0 until 20).map { i ->
            round(i, single = true, index = 100.0, contaminated = i == 5, elapsedMs = i * 2_500L)
        } + (20 until 40).map { i ->
            round(i, single = false, index = 100.0, elapsedMs = i * 2_500L)
        }
        val stats = analyseStability(rounds, warmupMs = 10_000L)
        // elapsed < 10 s => rounds 0..3 (4 rounds)
        assertEquals(4, stats.warmupExcludedRounds)
        assertEquals(1, stats.contaminatedRounds)
        assertEquals(15, stats.validSingleRounds) // 20 - 4 warmup - 1 contaminated
        assertEquals(20, stats.validMultiRounds)
    }

    @Test
    fun `retention uses the best window and never exceeds one hundred percent`() {
        // 30 single rounds: 10 x 100, 10 x 120, 10 x 90. Window size = 6.
        val single = (0 until 30).map { i ->
            round(i, single = true, index = when {
                i < 10 -> 100.0
                i < 20 -> 120.0
                else -> 90.0
            })
        }
        val multi = (0 until 30).map { i -> round(100 + i, single = false, index = 100.0) }
        val stats = analyseStability(single + multi, warmupMs = 0L)
        // single ratio = 90 / 120 = 75 %, multi ratio = 100 %; average = 87.5 %
        assertEquals(87.5, stats.retentionPercent ?: -1.0, 0.01)
        assertEquals(87.5, stats.retentionRawPercent ?: -1.0, 0.01)
        assertFalse(stats.slowStart)
        assertTrue((stats.retentionPercent ?: 0.0) <= 100.0)
    }

    @Test
    fun `a rising run is clamped and flagged when the ratio would exceed one hundred`() {
        // With a best-window reference the ratio cannot exceed 100 %, but the
        // clamp must still be in place: a perfect run reports exactly 100 %.
        val single = (0 until 20).map { i -> round(i, single = true, index = 90.0 + i) }
        val multi = (0 until 20).map { i -> round(100 + i, single = false, index = 90.0 + i) }
        val stats = analyseStability(single + multi, warmupMs = 0L)
        assertEquals(100.0, stats.retentionPercent ?: -1.0, 0.001)
        assertTrue(stats.retentionScore <= 1000.0)
    }

    @Test
    fun `cv is computed separately per workload type`() {
        val single = (0 until 20).map { i -> round(i, single = true, index = if (i % 2 == 0) 100.0 else 101.0) }
        val multi = (0 until 20).map { i -> round(100 + i, single = false, index = if (i % 2 == 0) 100.0 else 160.0) }
        val stats = analyseStability(single + multi, warmupMs = 0L)
        assertTrue(stats.singleCv < 1.0)
        assertTrue(stats.multiCv > 10.0)
        assertTrue(stats.combinedCv > stats.singleCv)
        assertTrue(stats.combinedCv < stats.multiCv)
    }

    @Test
    fun `score is the weighted sum of retention and consistency`() {
        val single = (0 until 20).map { i -> round(i, single = true, index = 100.0) }
        val multi = (0 until 20).map { i -> round(100 + i, single = false, index = 100.0) }
        val stats = analyseStability(single + multi, warmupMs = 0L)
        assertEquals(1000.0, stats.retentionScore, 1e-9)
        assertEquals(1000.0, stats.consistencyScore, 1e-9)
        assertEquals(1000.0, stats.score, 1e-9)
    }

    @Test
    fun `too few samples produces no retention instead of a fake score`() {
        val rounds = (0 until 5).map { i -> round(i, single = true, index = 100.0) }
        val stats = analyseStability(rounds, warmupMs = 0L)
        assertEquals(null, stats.retentionPercent)
        assertEquals(0.0, stats.score, 1e-9)
    }

    @Test
    fun `repeatability flags a spread above five percent`() {
        val stable = Repeatability.summarise(listOf(1000.0, 1010.0, 995.0))!!
        assertFalse(stable.unstable)
        assertTrue(stable.median in 995.0..1010.0)

        val unstable = Repeatability.summarise(listOf(1000.0, 1100.0, 900.0))!!
        assertTrue(unstable.unstable)
        assertEquals(1000.0, unstable.median, 1e-9)
        assertEquals(100.0, unstable.halfRange, 1e-9)
    }
}
