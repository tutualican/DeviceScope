package com.alican.devicescope.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marathon promises "never shorter than 15 minutes". That promise lives in
 * one place — [MarathonRunner.Config.effectiveMinutes] and
 * [MarathonRunner.weightedTotal] — so it is cheap to test and impossible to
 * change silently.
 */
class MarathonConfigTest {

    @Test
    fun `duration floor is fifteen minutes`() {
        intArrayOf(1, 5, 14, -30).forEach { requested ->
            val config = MarathonRunner.Config(minutes = requested)
            assertEquals(15, config.effectiveMinutes)
            assertEquals(15 * 60_000L, config.totalMs)
        }
    }

    @Test
    fun `longer runs keep their target`() {
        listOf(15, 30, 45, 60).forEach { minutes ->
            val config = MarathonRunner.Config(minutes = minutes)
            assertEquals(minutes, config.effectiveMinutes)
            assertEquals(minutes * 60_000L, config.totalMs)
        }
    }

    @Test
    fun `weights sum to one`() {
        assertEquals(1.0, MarathonRunner.WEIGHTS.values.sum(), 1e-9)
    }

    @Test
    fun `weighted total of a perfect device is one thousand`() {
        val perfect = MarathonRunner.WEIGHTS.keys.associateWith { 1000.0 }
        assertEquals(1000.0, MarathonRunner.weightedTotal(perfect), 1e-9)
    }

    @Test
    fun `missing components renormalise instead of scoring zero`() {
        // Only the two CPU components were measured; their weights are 0.18+0.22.
        val partial = mapOf("single" to 800.0, "multi" to 1200.0)
        val expected = (0.18 * 800.0 + 0.22 * 1200.0) / 0.40
        assertEquals(expected, MarathonRunner.weightedTotal(partial), 1e-9)
    }

    @Test
    fun `zero and NaN components are treated as missing`() {
        val withJunk = mapOf(
            "single" to 900.0,
            "multi" to 900.0,
            "gpu" to 0.0,
            "stability" to Double.NaN
        )
        assertEquals(900.0, MarathonRunner.weightedTotal(withJunk), 1e-9)
    }

    @Test
    fun `an empty breakdown cannot produce a score`() {
        assertEquals(0.0, MarathonRunner.weightedTotal(emptyMap()), 1e-9)
        assertTrue(MarathonRunner.WEIGHTS.isNotEmpty())
    }
}
