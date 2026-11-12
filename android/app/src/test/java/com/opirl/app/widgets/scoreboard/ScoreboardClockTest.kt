package com.opirl.app.widgets.scoreboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreboardClockTest {
    private val start = 1_000_000L

    @Test
    fun aRunningUpClockCountsFromItsStoredValueAndStopsAtTheMaximum() {
        val clock = ScoreboardClock(maximum = 2, seconds = 90, startedAt = start)
        assertEquals("1:30", clock.format(start))
        assertEquals("1:45", clock.format(start + 15_900))
        assertEquals("2:00", clock.format(start + 30_000))
        assertEquals("2:00", clock.format(start + 3_600_000))
    }

    @Test
    fun aRunningDownClockStopsAtZero() {
        val clock = ScoreboardClock(maximum = 10, direction = ClockDirection.DOWN, seconds = 65, startedAt = start)
        assertEquals("0:05", clock.format(start + 60_000))
        assertEquals("0:00", clock.format(start + 99_000))
    }

    @Test
    fun stoppingKeepsTheValueAndStartingContinuesFromIt() {
        val running = ScoreboardClock(seconds = 10, startedAt = start)
        val stopped = running.toggled(start + 5_900)
        assertFalse(stopped.running)
        assertEquals("0:15", stopped.format(start + 600_000))
        val again = stopped.toggled(start + 700_000)
        assertEquals("0:20", again.format(start + 705_000))
    }

    @Test
    fun settingTheTimeKeepsTheClockRunningFromTheNewValue() {
        val clock = ScoreboardClock(maximum = 45, seconds = 0, startedAt = start).withTime(600, start + 20_000)
        assertEquals("10:03", clock.format(start + 23_000))
        assertEquals("45:00", clock.withTime(99 * 60, start).format(start))
    }

    @Test
    fun resetGoesToZeroWhenCountingUpAndToTheMaximumWhenCountingDown() {
        assertEquals("0:00", ScoreboardClock(maximum = 30, seconds = 500).reset(start).format(start))
        assertEquals("30:00", ScoreboardClock(maximum = 30, direction = ClockDirection.DOWN).reset(start).format(start))
    }

    @Test
    fun theNextRedrawIsTheNextWholeSecondOnlyWhileTheDisplayChanges() {
        val clock = ScoreboardClock(maximum = 1, seconds = 58, startedAt = start)
        assertEquals(start + 1000, clock.nextChangeAt(start + 200))
        assertEquals(start + 2000, clock.nextChangeAt(start + 1200))
        assertNull(clock.nextChangeAt(start + 2000))
        assertNull(clock.toggled(start + 100).nextChangeAt(start + 500))
    }

    @Test
    fun clockTextNeedsTwoDigitSeconds() {
        assertEquals(754, parseClock("12:34"))
        assertEquals(0, parseClock(" 0:00 "))
        assertNull(parseClock("12:3"))
        assertNull(parseClock("1:60"))
        assertNull(parseClock("1234"))
        assertTrue(parseClock("180:00") == 10800)
    }
}
