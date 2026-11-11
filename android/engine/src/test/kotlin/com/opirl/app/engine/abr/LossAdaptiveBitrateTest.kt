package com.opirl.app.engine.abr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LossAdaptiveBitrateTest {
    private val second = 1_000_000_000L

    private fun stats(lostPercent: Double) = StreamStats(rttMs = 50.0, packetsInFlight = 0.0, lostPercent = lostPercent)

    @Test
    fun heavyLossLowersTheBitrateOncePerSecond() {
        val abr = LossAdaptiveBitrate(6_000_000, 500_000)
        assertEquals(5_400_000, abr.update(stats(20.0), 0))
        assertEquals(5_400_000, abr.update(stats(20.0), second / 2))
        assertEquals(4_860_000, abr.update(stats(20.0), second))
    }

    @Test
    fun itNeverGoesUnderTheMinimum() {
        val abr = LossAdaptiveBitrate(6_000_000, 500_000)
        for (i in 0 until 100) abr.update(stats(100.0), i * second)
        assertEquals(500_000, abr.bitrate)
    }

    @Test
    fun itClimbsBackOnlyAfterTheLossHasBeenGoneForAWhile() {
        val abr = LossAdaptiveBitrate(6_000_000, 500_000)
        val lowered = abr.update(stats(50.0), 0)
        assertEquals(lowered, abr.update(stats(0.0), 3 * second))
        assertTrue(abr.update(stats(0.0), 4 * second) > lowered)
        for (i in 5 until 200) abr.update(stats(0.0), i * second)
        assertEquals(6_000_000, abr.bitrate)
    }

    @Test
    fun moderateLossHoldsTheBitrate() {
        val abr = LossAdaptiveBitrate(6_000_000, 500_000)
        val lowered = abr.update(stats(50.0), 0)
        for (i in 1 until 30) assertEquals(lowered, abr.update(stats(5.0), i * second))
    }
}
