package app.mobdroid.engine.abr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun ms(value: Long): Long = value * 1_000_000

private fun stats(
    rtt: Double = 30.0,
    inFlight: Double = 15.0,
    transport: Long? = null,
    sendRate: Double? = 100.0,
    relaxed: Boolean = false,
    latency: Int? = 3000,
) = StreamStats(rtt, inFlight, transport, latency, sendRate, relaxed)

private class Clock(var now: Long = ms(1000)) {
    fun tick(milliseconds: Long = 20): Long {
        now += ms(milliseconds)
        return now
    }
}

private fun BelaboxAdaptiveBitrate.warmUp(clock: Clock, milliseconds: Long, stats: StreamStats = stats()) {
    repeat((milliseconds / 20).toInt()) { update(stats, clock.tick()) }
}

class BelaboxAdaptiveBitrateTest {
    @Test
    fun startsBelowTargetAndFirstCleanUpdateRaisesIt() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        assertEquals(1_000_000, abr.bitrate)
        val clock = Clock()
        val bitrate = abr.update(
            StreamStats(30.0, 15.0, 5_000_000, 3000, 5_000_000.0, false),
            clock.tick(),
        )
        assertEquals(1_133_333, bitrate)
        assertEquals(1_133_333, abr.bitrate)
    }

    @Test
    fun transportBitrateLimitsHowHighASilentEncoderCanPush() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        val fiveMbps = StreamStats(30.0, 15.0, 5_000_000, 3000, 5_000_000.0, false)
        var rounds = 0
        while (abr.bitrate != 5_000_000L) {
            abr.update(fiveMbps, clock.tick())
            assertTrue(rounds++ < 2000, "reaches the target")
        }
        abr.update(StreamStats(30.0, 15.0, 1_000_000, 3000, 5_000_000.0, false), clock.tick())
        assertEquals(2_000_000, abr.bitrate)
    }

    @Test
    fun increasesAtMostOnceEveryFourHundredMilliseconds() {
        val abr = BelaboxAdaptiveBitrate(10_000_000)
        val clock = Clock()
        val seen = ArrayList<Long>()
        repeat(50) {
            seen.add(abr.update(stats(), clock.tick()))
        }
        assertEquals(1_133_333, seen.first())
        assertEquals(listOf(0, 21, 42), seen.indices.filter { it == 0 || seen[it] != seen[it - 1] })
    }

    @Test
    fun highRttDropsStraightToTheMinimum() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 5000)
        assertTrue(abr.bitrate > 1_500_000)
        abr.update(stats(rtt = 1000.0), clock.tick())
        assertEquals(250_000, abr.bitrate)
    }

    @Test
    fun sustainedPacketsInFlightGrowthDropsStraightToTheMinimum() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 5000)
        assertTrue(abr.bitrate > 1_500_000)
        repeat(40) { abr.update(stats(inFlight = 15.0 + 5.0 * (it + 1)), clock.tick()) }
        assertEquals(250_000, abr.bitrate)
    }

    @Test
    fun aSingleSpikeIsAbsorbedAsJitter() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 5000)
        val before = abr.bitrate
        assertTrue(abr.update(stats(inFlight = 600.0), clock.tick()) >= before)
    }

    @Test
    fun moderateRttDecreasesFastButNoMoreOftenThanEveryQuarterSecond() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 6000)
        val before = abr.bitrate
        val bad = stats(rtt = 700.0)
        assertEquals(before - (100_000 + before / 10), abr.update(bad, clock.tick()))
        val afterFirst = abr.bitrate
        repeat(12) { assertEquals(afterFirst, abr.update(bad, clock.tick())) }
        assertEquals(afterFirst - (100_000 + afterFirst / 10), abr.update(bad, clock.tick()))
    }

    @Test
    fun gradualRttGrowthDecreasesInSlowSteps() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 6000)
        var previous = abr.bitrate
        val decreases = ArrayList<Long>()
        for (step in 0 until 100) {
            val bitrate = abr.update(stats(rtt = 30.0 + step), clock.tick())
            if (bitrate < previous) {
                decreases.add(previous - bitrate)
            }
            previous = bitrate
        }
        assertTrue(decreases.size >= 5, "decreased ${decreases.size} times")
        assertEquals(setOf(100_000L), decreases.toSet())
        assertTrue(abr.bitrate > 250_000)
    }

    @Test
    fun relaxedModeTurnsTheFastDecreaseIntoASlowOne() {
        val strict = BelaboxAdaptiveBitrate(5_000_000)
        val relaxed = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        strict.warmUp(Clock(clock.now), 4000)
        relaxed.warmUp(Clock(clock.now), 4000)
        clock.now += ms(4000)
        var strictBefore = strict.bitrate
        var relaxedBefore = relaxed.bitrate
        assertEquals(strictBefore, relaxedBefore)
        for (step in 1..60) {
            val inFlight = 15.0 + 3.0 * step
            val now = clock.tick()
            val strictAfter = strict.update(stats(inFlight = inFlight), now)
            val relaxedAfter = relaxed.update(stats(inFlight = inFlight, relaxed = true), now)
            if (strictAfter < strictBefore) {
                assertEquals(100_000 + strictBefore / 10, strictBefore - strictAfter)
                assertEquals(100_000, relaxedBefore - relaxedAfter)
                return
            }
            strictBefore = strictAfter
            relaxedBefore = relaxedAfter
        }
        throw AssertionError("strict never decreased")
    }

    @Test
    fun ignoresUpdatesWithoutRttOrSendRate() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        assertEquals(1_000_000, abr.update(stats(rtt = 0.0), clock.tick()))
        assertEquals(1_000_000, abr.update(stats(sendRate = null), clock.tick()))
    }

    @Test
    fun staysWithinUserLimitsAndFollowsChangedLimits() {
        val abr = BelaboxAdaptiveBitrate(2_000_000, 500_000)
        val clock = Clock()
        abr.warmUp(clock, 20_000)
        assertEquals(2_000_000, abr.bitrate)
        abr.update(stats(rtt = 2000.0), clock.tick())
        assertEquals(500_000, abr.bitrate)
        abr.setMinimumBitrate(800_000)
        abr.update(stats(rtt = 2000.0), clock.tick())
        assertEquals(800_000, abr.bitrate)
        abr.setTargetBitrate(900_000)
        abr.warmUp(clock, 20_000)
        assertEquals(900_000, abr.bitrate)
    }

    @Test
    fun targetBelowStartBitrateStartsAtTarget() {
        assertEquals(600_000, BelaboxAdaptiveBitrate(600_000).bitrate)
        assertEquals(250_000, BelaboxAdaptiveBitrate(100_000).bitrate)
    }

    @Test
    fun recordsActionsOnlyWhenAskedAndForgetsOldOnes() {
        val abr = BelaboxAdaptiveBitrate(5_000_000)
        val clock = Clock()
        abr.warmUp(clock, 3000)
        abr.update(stats(rtt = 1500.0), clock.tick())
        assertTrue(abr.actionsTaken(clock.now).isEmpty())
        abr.recordActions = true
        clock.tick(500)
        abr.warmUp(clock, 3000)
        abr.update(stats(rtt = 1500.0), clock.tick())
        val actions = abr.actionsTaken(clock.now)
        assertEquals(1, actions.size)
        assertTrue(actions.single().startsWith("Set min: 250"), actions.single())
        assertTrue(abr.actionsTaken(clock.now + ms(16_000)).isEmpty())
    }
}

class StreamStatsTest {
    @Test
    fun limitsByTransportBitrateWithMinimumHeadroom() {
        val stats = StreamStats(30.0, 10.0, transportBitrate = 1_000_000)
        assertEquals(2_000_000, stats.limitByTransportBitrate(9_000_000))
        assertEquals(1_500_000, stats.limitByTransportBitrate(1_500_000))
        val fast = StreamStats(30.0, 10.0, transportBitrate = 10_000_000)
        assertEquals(17_000_000, fast.limitByTransportBitrate(20_000_000))
        assertEquals(20_000_000, StreamStats(30.0, 10.0).limitByTransportBitrate(20_000_000))
    }

    @Test
    fun transportBitrateMeterSmoothsMeasuredThroughput() {
        val meter = TransportBitrateMeter(4_000_000)
        assertEquals(4_000_000, meter.update(0, 0))
        assertEquals(3_100_000, meter.update(125_000, ms(1000)))
        assertEquals(2_470_000, meter.update(375_000, ms(3000)))
        assertEquals(2_470_000, meter.bitrate)
    }

    @Test
    fun transportBitrateMeterSkipsTheGapAcrossAPauseAndACounterRestart() {
        val meter = TransportBitrateMeter(4_000_000)
        meter.update(500_000, 0)
        meter.pause()
        assertEquals(4_000_000, meter.update(900_000, ms(10_000)))
        assertEquals(4_000_000, meter.update(0, ms(11_000)))
        assertEquals(3_100_000, meter.update(125_000, ms(12_000)))
    }
}
