package com.opirl.app.engine.abr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun at(milliseconds: Long): Long = milliseconds * 1_000_000

private fun queue(bytes: Long, transport: Long? = null) =
    StreamStats(rttMs = 0.0, packetsInFlight = 0.0, transportBitrate = transport, queuedBytes = bytes)

class QueueAdaptiveBitrateTest {
    @Test
    fun startsAtTargetAndStaysWhileTheQueueIsEmpty() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(6_000_000, abr.bitrate)
        repeat(300) { assertEquals(6_000_000, abr.update(queue(0), at(it * 200L))) }
    }

    @Test
    fun backsOffByAQuarterWhenTheQueueHoldsFourHundredMilliseconds() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(4_500_000, abr.update(queue(300_000), at(1000)))
    }

    @Test
    fun backsOffToNinetyPercentOfWhatTheLinkActuallyDrained() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(2_700_000, abr.update(queue(300_000, transport = 3_000_000), at(1000)))
    }

    @Test
    fun aGrowingQueueIsEnoughOnceItHoldsOneHundredFiftyMilliseconds() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(6_000_000, abr.update(queue(60_000), at(1000)))
        assertEquals(4_500_000, abr.update(queue(120_000), at(1200)))
        assertEquals(4_500_000, abr.update(queue(120_000), at(1400)))
    }

    @Test
    fun aSteadyQueueBelowFourHundredMillisecondsIsTolerated() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        abr.update(queue(100_000), at(1000))
        repeat(20) { assertEquals(6_000_000, abr.update(queue(100_000), at(1200L + it * 200))) }
    }

    @Test
    fun decreasesAreSpacedByHalfASecond() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(4_500_000, abr.update(queue(300_000), at(1000)))
        assertEquals(4_500_000, abr.update(queue(300_000), at(1100)))
        assertEquals(4_500_000, abr.update(queue(300_000), at(1499)))
        assertEquals(3_375_000, abr.update(queue(300_000), at(1500)))
    }

    @Test
    fun recoversOnlyAfterAHoldOffAndThenByFivePercentPerSecond() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        abr.update(queue(300_000), at(1000))
        assertEquals(4_500_000, abr.update(queue(0), at(4900)))
        assertEquals(4_725_000, abr.update(queue(0), at(5000)))
        assertEquals(4_725_000, abr.update(queue(0), at(5500)))
        assertEquals(4_961_250, abr.update(queue(0), at(6000)))
    }

    @Test
    fun holdsWhileTheQueueIsNeitherDrainedNorGrowing() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        abr.update(queue(300_000), at(1000))
        repeat(40) { assertEquals(4_500_000, abr.update(queue(100_000), at(2000L + it * 1000))) }
    }

    @Test
    fun staysWithinUserLimits() {
        val abr = QueueAdaptiveBitrate(2_000_000, 500_000)
        repeat(100) { abr.update(queue(2_000_000), at(it * 1000L)) }
        assertEquals(500_000, abr.bitrate)
        repeat(200) { abr.update(queue(0), at(200_000L + it * 1000)) }
        assertEquals(2_000_000, abr.bitrate)
        abr.setTargetBitrate(1_000_000)
        assertEquals(1_000_000, abr.update(queue(0), at(500_000)))
        abr.setMinimumBitrate(1_200_000)
        assertEquals(1_200_000, abr.update(queue(0), at(501_000)))
    }

    @Test
    fun staticSceneCannotPushTheTargetFarAboveWhatWasActuallySent() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        assertEquals(1_500_000, abr.update(queue(0, transport = 500_000), at(1000)))
    }

    @Test
    fun convergesBelowALinkThatCannotCarryTheTarget() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        val capacity = 3_000_000L
        var queued = 0.0
        var worstQueuedMs = 0.0
        var transport: Long? = null
        val seen = ArrayList<Long>()
        for (step in 0 until 1200) {
            val bitrate = abr.bitrate
            queued += bitrate / 8.0 * 0.2
            val sent = minOf(queued, capacity / 8.0 * 0.2)
            queued -= sent
            transport = (sent * 8 / 0.2).toLong()
            worstQueuedMs = maxOf(worstQueuedMs, queued * 8000 / bitrate)
            val updated = abr.update(queue(queued.toLong(), transport), at(step * 200L))
            if (step >= 600) {
                seen.add(updated)
            }
        }
        assertTrue(worstQueuedMs < 2000, "worst queue was $worstQueuedMs ms")
        assertTrue(seen.max() <= 3_500_000, "rises to ${seen.max()}")
        assertTrue(seen.min() >= 2_000_000, "falls to ${seen.min()}")
    }

    @Test
    fun recordsOnlyDecreaseActions() {
        val abr = QueueAdaptiveBitrate(6_000_000)
        abr.recordActions = true
        abr.update(queue(300_000), at(1000))
        abr.update(queue(0), at(5000))
        val actions = abr.actionsTaken(at(5000))
        assertEquals(1, actions.size)
        assertTrue(actions.single().startsWith("Decr: queue 400 ms"), actions.single())
    }
}
