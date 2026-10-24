package com.alican.devicescope.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Cross-checks the Kotlin scoring arithmetic against numbers produced by
 * independent implementations (tools/verify_scoring.py and its PowerShell twin
 * tools/verify_scoring.ps1). If this test fails, the app and the verifier no
 * longer agree and the published score cannot be trusted.
 *
 * The expected values below were computed from the spec formula, not read back
 * from the app.
 */
class ScoringVerificationTest {

    private val config = ScoreConfig(cpuWeight = 0.40, gpuWeight = 0.30, memoryWeight = 0.15, uxWeight = 0.15)

    @Test
    fun referenceDeviceScoresExactlyOneThousand() {
        val b = config.breakdown(
            ScoreConfig.Inputs(
                single = 1000.0, multi = 1000.0, memory = 1000.0,
                storage = 1000.0, gpu = 1000.0, ux = 1000.0
            )
        )
        assertEquals(1000.0, b.combined, 1e-9)
    }

    @Test
    fun geometricMeanMatchesIndependentImplementation() {
        val b = config.breakdown(ScoreConfig.Inputs(single = 400.0, multi = 1600.0))
        assertEquals(800.0, b.cpu!!, 1e-9)
    }

    @Test
    fun hypotheticalDevicesMatchPythonAndPowerShell() {
        // Values from tools/verify_scoring.ps1 / verify_scoring.py.
        val cases = listOf(
            Triple(
                ScoreConfig.Inputs(1400.0, 5200.0, 900.0, 1100.0, 1600.0, 950.0),
                doubleArrayOf(1851.0071, 2698.1475, 1600.0, 994.9874, 950.0),
                "A"
            ),
            Triple(
                ScoreConfig.Inputs(900.0, 2600.0, 700.0, 800.0, 850.0, 780.0),
                doubleArrayOf(1096.1321, 1529.7059, 850.0, 748.3315, 780.0),
                "B"
            ),
            Triple(
                ScoreConfig.Inputs(600.0, 1500.0, 500.0, 600.0, 1400.0, 620.0),
                doubleArrayOf(974.6317, 948.6833, 1400.0, 547.7226, 620.0),
                "C"
            )
        )

        for ((inputs, expected, name) in cases) {
            val b = config.breakdown(inputs)
            assertEquals("$name combined", expected[0], b.combined, 1e-3)
            assertEquals("$name cpu", expected[1], b.cpu!!, 1e-3)
            assertEquals("$name gpu", expected[2], b.gpu!!, 1e-3)
            assertEquals("$name memory", expected[3], b.memory!!, 1e-3)
            assertEquals("$name ux", expected[4], b.ux!!, 1e-3)
        }
    }

    @Test
    fun twentyPercentSlowdownOfOneWorkloadHasExpectedEffect() {
        val base = ScoreConfig.Inputs(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0)
        val slow = base.copy(single = 800.0)

        val baseScore = config.breakdown(base).combined
        val slowScore = config.breakdown(slow).combined
        val observedDrop = (baseScore - slowScore) / baseScore * 100.0

        // CPU group = sqrt(0.8 * 1.0) -> -10.5573% of the group, 40% weight.
        val expected = config.cpuWeight * (1.0 - sqrt(0.8)) * 100.0
        assertEquals(expected, observedDrop, 1e-9)
        assertEquals(4.222912, observedDrop, 1e-5)
    }

    @Test
    fun thirtyPercentSlowdownOfOneCategoryHasExpectedEffect() {
        val base = ScoreConfig.Inputs(1000.0, 1000.0, 1000.0, 1000.0, 1000.0, 1000.0)
        val slow = base.copy(gpu = 700.0)

        val baseScore = config.breakdown(base).combined
        val slowScore = config.breakdown(slow).combined
        val observedDrop = (baseScore - slowScore) / baseScore * 100.0

        assertEquals(config.gpuWeight * 30.0, observedDrop, 1e-9)
    }

    @Test
    fun missingGroupRenormalisesInsteadOfScoringZero() {
        val b = config.breakdown(
            ScoreConfig.Inputs(single = 1000.0, multi = 1000.0, memory = 1000.0, storage = 1000.0, ux = 1000.0)
        )
        assertEquals(1000.0, b.combined, 1e-9)
    }

    @Test
    fun weightShiftMovesScoreInTheExpectedDirection() {
        val cpuHeavy = ScoreConfig.Inputs(2000.0, 2000.0, 500.0, 500.0, 500.0, 500.0)
        val normal = config.breakdown(cpuHeavy).combined
        val shifted = config.shifted("cpu", 0.05).breakdown(cpuHeavy).combined
        assertTrue("CPU ağırlığı artınca CPU-ağır cihaz yükselmeli", shifted > normal)
    }

    @Test
    fun fasterDeviceAlwaysRanksHigher() {
        val fast = ScoreConfig.Inputs(2000.0, 2000.0, 2000.0, 2000.0, 2000.0, 2000.0)
        val slow = ScoreConfig.Inputs(500.0, 600.0, 400.0, 400.0, 500.0, 500.0)
        assertTrue(config.breakdown(fast).combined > config.breakdown(slow).combined)
    }

    @Test
    fun spearmanIsOneForMonotonicRanks() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val ys = listOf(10.0, 20.0, 30.0, 40.0, 50.0)
        assertEquals(1.0, StatsExt.spearman(xs, ys), 1e-12)
    }

    @Test
    fun spearmanIsMinusOneForReversedRanks() {
        val xs = listOf(1.0, 2.0, 3.0, 4.0)
        val ys = listOf(40.0, 30.0, 20.0, 10.0)
        assertEquals(-1.0, StatsExt.spearman(xs, ys), 1e-12)
    }

    @Test
    fun studentTConfidenceIntervalGrowsAsNShrinks() {
        val small = StatsExt.describe("small", listOf(100.0, 101.0, 99.0), "x")
        val large = StatsExt.describe("large", (1..30).map { 100.0 + (it % 3) - 1.0 }, "x")
        assertTrue(
            "küçük örneklemde GA daha geniş olmalı",
            small.ciHalfWidth > large.ciHalfWidth
        )
        assertTrue("GA ortalamayı içermeli", small.ciLow < small.mean && small.ciHigh > small.mean)
    }

    @Test
    fun iqrOutlierIsCounted() {
        val values = listOf(100.0, 101.0, 99.0, 100.5, 99.5, 100.2, 500.0)
        val stat = StatsExt.describe("with outlier", values, "x")
        assertEquals(1, stat.outliers)
        assertTrue(abs(stat.median - 100.2) < 1.0)
    }
}
