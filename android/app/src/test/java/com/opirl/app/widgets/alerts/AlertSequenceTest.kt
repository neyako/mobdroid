package com.opirl.app.widgets.alerts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AlertTimelineTest {
    private val timeline = AlertTimeline(intArrayOf(100, 250, 50), loops = 2)

    @Test
    fun showsEachFrameForItsOwnDelayAndRepeatsTheLoop() {
        val expected = mapOf(0L to 0, 99L to 0, 100L to 1, 349L to 1, 350L to 2, 399L to 2, 400L to 0, 500L to 1, 799L to 2)
        for ((elapsed, frame) in expected) assertEquals(frame, timeline.frameAt(elapsed), "at $elapsed")
        assertEquals(800L, timeline.totalMs)
    }

    @Test
    fun endsWhenTheLastLoopIsDone() {
        assertNull(timeline.frameAt(800))
        assertNull(timeline.frameAt(5000))
        assertEquals(0, timeline.frameAt(-20))
    }

    @Test
    fun saysWhenTheNextFrameStarts() {
        val expected = mapOf(0L to 100L, 99L to 100L, 100L to 350L, 350L to 400L, 400L to 500L, 750L to 800L, 799L to 800L, 800L to 800L)
        for ((elapsed, change) in expected) assertEquals(change, timeline.nextChangeMs(elapsed), "at $elapsed")
    }

    @Test
    fun neverAsksForMoreThanThirtyDrawsASecond() {
        val quick = AlertTimeline(IntArray(10) { 20 }, loops = 1)
        assertEquals(1033L, quick.nextDrawAt(1000, 1000))
        assertEquals(1053L, quick.nextDrawAt(1000, 1020))
        assertEquals(1100L, timeline.nextDrawAt(1000, 1000))
        assertEquals(1350L, timeline.nextDrawAt(1000, 1100))
        assertEquals(1823L, timeline.nextDrawAt(1000, 1790))
    }

    @Test
    fun anImageWithNoFramesNeverShows() {
        val empty = AlertTimeline(IntArray(0), loops = 3)
        assertNull(empty.frameAt(0))
        assertEquals(0L, empty.totalMs)
    }
}

class AlertSequenceTest {
    private fun sequence() = AlertSequence<String, String, String>(limit = 3)

    private fun resolve(event: String): String? = if (event.startsWith("skip")) null else event.uppercase()

    @Test
    fun playsAlertsOneAfterAnotherWithThreeSecondsBetween() {
        val sequence = sequence()
        sequence.enqueue("first")
        sequence.enqueue("second")
        assertEquals("FIRST", sequence.begin(1000, ::resolve))
        assertNull(sequence.begin(1000, ::resolve))
        sequence.start("first", 1100, 2000)
        assertNull(sequence.begin(1500, ::resolve))
        assertEquals("first", sequence.active(3099)?.content)
        assertNull(sequence.active(3100))
        assertNull(sequence.begin(3100, ::resolve))
        assertNull(sequence.begin(6099, ::resolve))
        assertEquals(6100L, sequence.wakeAt())
        assertEquals("SECOND", sequence.begin(6100, ::resolve))
    }

    @Test
    fun skipsAlertsThatCannotPlayWithoutWaiting() {
        val sequence = sequence()
        sequence.enqueue("skip one")
        sequence.enqueue("skip two")
        sequence.enqueue("third")
        assertEquals("THIRD", sequence.begin(0, ::resolve))
        sequence.start("third", 0, 10)
        assertNull(sequence.active(10))
        sequence.enqueue("skip again")
        assertNull(sequence.begin(3010, ::resolve))
        assertNull(sequence.wakeAt())
    }

    @Test
    fun anAlertWithNothingToShowStillHoldsTheQueueForThreeSeconds() {
        val sequence = sequence()
        sequence.enqueue("a")
        sequence.enqueue("b")
        sequence.begin(500, ::resolve)
        sequence.start("a", 500, 0)
        assertNull(sequence.active(500))
        assertNull(sequence.begin(3499, ::resolve))
        assertEquals("B", sequence.begin(3500, ::resolve))
    }

    @Test
    fun keepsTheNewestAlertsWhenTheQueueIsFull() {
        val sequence = sequence()
        for (name in listOf("a", "b", "c", "d", "e")) sequence.enqueue(name)
        val played = ArrayList<String>()
        var now = 0L
        while (true) {
            val plan = sequence.begin(now, ::resolve) ?: break
            played += plan
            sequence.start(plan, now, 0)
            sequence.active(now)
            now += 3000
        }
        assertEquals(listOf("C", "D", "E"), played)
    }

    @Test
    fun clearingDropsEverythingAndTheCurrentAlert() {
        val sequence = sequence()
        sequence.enqueue("a")
        sequence.enqueue("b")
        sequence.begin(0, ::resolve)
        sequence.start("a", 0, 5000)
        sequence.clear()
        assertNull(sequence.active(1))
        assertNull(sequence.begin(1, ::resolve))
        assertTrue(sequence.wakeAt() == null)
    }
}
