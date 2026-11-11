package app.mobdroid.engine.rtmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendStatsTest {
    private var now = 1_000_000_000L
    private val acks = AckTracker { now }
    private val rate = RateWindow { now }

    @Test
    fun writtenBytesCountAsPacketsInFlightUntilAcknowledged() {
        acks.onWritten(1400)
        assertEquals(1, acks.packetsInFlight)
        acks.onWritten(2800)
        acks.onWritten(4200)
        assertEquals(3, acks.packetsInFlight)
        acks.onAck(2800)
        assertEquals(1, acks.packetsInFlight)
        acks.onAck(4201)
        assertEquals(0, acks.packetsInFlight)
    }

    @Test
    fun roundTripTimeIsTheAgeOfTheNewestWriteTheServerHasAcknowledged() {
        acks.onWritten(1400)
        now += 200_000_000L
        acks.onWritten(2800)
        now += 50_000_000L
        acks.onAck(1401)
        assertEquals(250.0, acks.rttMs)
    }

    @Test
    fun acknowledgementsBeforeAnyWriteLeaveTheStatsUntouched() {
        acks.onAck(5000)
        acks.onWritten(1400)
        assertEquals(0, acks.packetsInFlight)
        assertEquals(0.0, acks.rttMs)
    }

    @Test
    fun sequenceNumbersThatRollOverAtInt32MaxAreUnwrapped() {
        acks.onWritten(Int.MAX_VALUE - 500L)
        acks.onWritten(Int.MAX_VALUE + 5000L)
        acks.onAck(Int.MAX_VALUE - 600L)
        acks.onAck(1000)
        assertEquals(2, acks.packetsInFlight)
    }

    @Test
    fun sequenceNumbersThatRollOverAtUInt32MaxAreUnwrapped() {
        val aboveInt32Max = Int.MAX_VALUE + 1000L
        acks.onWritten(aboveInt32Max - 500)
        acks.onWritten(0xFFFFFFFFL + 5000)
        acks.onAck(aboveInt32Max - 600)
        acks.onAck(1000)
        assertEquals(2, acks.packetsInFlight)
    }

    @Test
    fun tooManyUnacknowledgedWritesDoNotGrowTheTimingHistory() {
        repeat(2000) { acks.onWritten(it * 1400L + 1400) }
        assertEquals(2000, acks.packetsInFlight)
        acks.onAck(2000 * 1400L)
        assertTrue(acks.rttMs >= 0.0)
    }

    @Test
    fun sendRateIsMeasuredOverTheLastSecond() {
        repeat(10) {
            rate.add(12_500)
            now += 100_000_000L
        }
        val steady = rate.bitsPerSecond()
        assertTrue(steady in 900_000..1_100_000, "steady $steady")
        now += 500_000_000L
        val decayed = rate.bitsPerSecond()
        assertTrue(decayed in 400_000..600_000, "decayed $decayed")
        now += 2_000_000_000L
        assertEquals(0L, rate.bitsPerSecond())
    }

    @Test
    fun sendRateIsNotUnderestimatedInTheFirstSecond() {
        rate.add(12_500)
        now += 100_000_000L
        rate.add(12_500)
        now += 100_000_000L
        val early = rate.bitsPerSecond()
        assertTrue(early in 900_000..1_100_000, "early $early")
    }
}
