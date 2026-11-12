package com.opirl.app.engine.abr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RistAdaptiveBitrateTest {
    private fun stats(rttMs: Double) = StreamStats(rttMs = rttMs, packetsInFlight = 0.0)

    @Test
    fun aSteadyRoundTripTimeClimbsToTheTarget() {
        val abr = RistAdaptiveBitrate(6_000_000, 500_000)
        assertEquals(1_000_000, abr.bitrate)
        repeat(200) { abr.update(stats(30.0)) }
        assertEquals(6_000_000, abr.bitrate)
    }

    @Test
    fun aRisingRoundTripTimeLowersTheBitrateDownToTheMinimum() {
        val abr = RistAdaptiveBitrate(6_000_000, 500_000)
        repeat(200) { abr.update(stats(30.0)) }
        abr.update(stats(200.0))
        assertTrue(abr.bitrate < 6_000_000)
        repeat(400) { abr.update(stats(600.0)) }
        assertEquals(500_000, abr.bitrate)
    }

    @Test
    fun theMeasuredTransportBitrateCapsTheClimb() {
        val abr = RistAdaptiveBitrate(6_000_000, 500_000)
        repeat(200) { abr.update(StreamStats(rttMs = 30.0, packetsInFlight = 0.0, transportBitrate = 1_000_000)) }
        assertEquals(2_000_000, abr.bitrate)
    }

    @Test
    fun aLowerTargetCapsTheBitrate() {
        val abr = RistAdaptiveBitrate(6_000_000, 500_000)
        repeat(200) { abr.update(stats(30.0)) }
        abr.setTargetBitrate(2_000_000)
        abr.update(stats(30.0))
        assertEquals(2_000_000, abr.bitrate)
    }
}
