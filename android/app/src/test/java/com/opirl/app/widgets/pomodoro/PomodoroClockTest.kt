package com.opirl.app.widgets.pomodoro

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PomodoroClockTest {
    private val start = 1_000_000L
    private val cycle = 2102_000L

    private fun PomodoroSettings.at(now: Long) = reading(now).let { Triple(it.phase, it.seconds, it.nextChange != null) }

    @Test
    fun `an idle timer shows the whole focus time and never asks for a redraw`() {
        val reading = PomodoroSettings().reading(start)
        assertEquals(PomodoroPhase.FOCUS, reading.phase)
        assertEquals(1800, reading.seconds)
        assertEquals(1f, reading.progress)
        assertNull(reading.nextChange)
    }

    @Test
    fun `a running timer shows each value for a whole second and keeps 00-00 for a second as well`() {
        val timer = PomodoroSettings().started(start)
        assertEquals(1800, timer.reading(start).seconds)
        assertEquals(1800, timer.reading(start + 999).seconds)
        assertEquals(1799, timer.reading(start + 1000).seconds)
        assertEquals(0, timer.reading(start + 1_800_000).seconds)
        assertEquals(0, timer.reading(start + 1_800_999).seconds)
        assertEquals(PomodoroPhase.FOCUS, timer.reading(start + 1_800_999).phase)
    }

    @Test
    fun `the next redraw is the moment the shown second changes`() {
        val timer = PomodoroSettings().started(start)
        assertEquals(start + 1000, timer.reading(start).nextChange)
        assertEquals(start + 1000, timer.reading(start + 400).nextChange)
        assertEquals(start + 2000, timer.reading(start + 1000).nextChange)
        assertEquals(start + 1_801_000, timer.reading(start + 1_800_500).nextChange)
    }

    @Test
    fun `phases change on the clock alone, however long nobody looked`() {
        val timer = PomodoroSettings().started(start)
        assertEquals(Triple(PomodoroPhase.BREAK, 300, true), timer.at(start + 1_801_000))
        assertEquals(Triple(PomodoroPhase.BREAK, 293, true), timer.at(start + 1_808_000))
        assertEquals(Triple(PomodoroPhase.FOCUS, 1800, true), timer.at(start + cycle))
        assertEquals(Triple(PomodoroPhase.FOCUS, 1795, true), timer.at(start + 10 * cycle + 5000))
        assertEquals(Triple(PomodoroPhase.BREAK, 293, true), timer.at(start + 10 * cycle + 1_808_000))
        assertEquals(Triple(PomodoroPhase.FOCUS, 274, true), timer.at(start + 7 * 24 * 3600 * 1000L))
    }

    @Test
    fun `pausing keeps the shown time and resuming counts on from it`() {
        val paused = PomodoroSettings().started(start).paused(start + 5400)
        assertFalse(paused.running)
        assertEquals(Triple(PomodoroPhase.FOCUS, 1795, false), paused.at(start + 999_999_999))
        val resumed = paused.started(start + 100_000)
        assertEquals(1795, resumed.reading(start + 100_999).seconds)
        assertEquals(1794, resumed.reading(start + 101_000).seconds)
    }

    @Test
    fun `pausing during the first second keeps the timer fresh`() {
        val paused = PomodoroSettings().started(start).paused(start + 300)
        assertNull(paused.secondsLeft)
        assertEquals(PomodoroPhase.FOCUS, paused.phase)
    }

    @Test
    fun `pausing after the phase ran out silently keeps the new phase`() {
        val paused = PomodoroSettings().started(start).paused(start + 1_801_000 + 60_000)
        assertEquals(PomodoroPhase.BREAK, paused.phase)
        assertEquals(240, paused.secondsLeft)
        assertEquals(Triple(PomodoroPhase.BREAK, 240, false), paused.at(start + 5_000_000))
    }

    @Test
    fun `skipping goes to the other phase at its full time and keeps the timer running or stopped`() {
        val running = PomodoroSettings().started(start).skipped(start + 10_000)
        assertEquals(Triple(PomodoroPhase.BREAK, 300, true), running.at(start + 10_000))
        assertEquals(299, running.reading(start + 11_000).seconds)
        val idle = PomodoroSettings().skipped(start)
        assertEquals(Triple(PomodoroPhase.BREAK, 300, false), idle.at(start))
        assertEquals(Triple(PomodoroPhase.FOCUS, 1800, false), idle.skipped(start).at(start))
        val late = PomodoroSettings().started(start).skipped(start + 1_801_000 + 60_000)
        assertEquals(Triple(PomodoroPhase.FOCUS, 1800, true), late.at(start + 1_801_000 + 60_000))
    }

    @Test
    fun `reset stops the timer and goes back to a whole focus time`() {
        val reset = PomodoroSettings().started(start).skipped(start + 5000).reset()
        assertEquals(Triple(PomodoroPhase.FOCUS, 1800, false), reset.at(start + 6000))
        assertNull(reset.secondsLeft)
    }

    @Test
    fun `a new duration applies at once to a stopped timer in that phase and later to a running one`() {
        val paused = PomodoroSettings().started(start).paused(start + 20_000)
        assertEquals(1780, paused.reading(start).seconds)
        assertEquals(2700, paused.withMinutes(PomodoroPhase.FOCUS, 45, start).reading(start).seconds)
        assertEquals(1780, paused.withMinutes(PomodoroPhase.BREAK, 10, start).reading(start).seconds)
        val running = PomodoroSettings().started(start).withMinutes(PomodoroPhase.FOCUS, 10, start + 5000)
        assertEquals(Triple(PomodoroPhase.FOCUS, 1795, true), running.at(start + 5000))
        assertEquals(Triple(PomodoroPhase.BREAK, 300, true), running.at(start + 1_801_000))
        assertEquals(Triple(PomodoroPhase.FOCUS, 600, true), running.at(start + 1_801_000 + 301_000))
    }

    @Test
    fun `changing a duration does not rewrite phases that already passed unseen`() {
        val timer = PomodoroSettings().started(start)
        val later = start + cycle + 60_000
        val changed = timer.withMinutes(PomodoroPhase.BREAK, 10, later)
        assertEquals(Triple(PomodoroPhase.FOCUS, 1740, true), changed.at(later))
        assertEquals(Triple(PomodoroPhase.BREAK, 600, true), changed.at(start + cycle + 1_801_000))
    }

    @Test
    fun `progress is the share of the phase that is left and stays inside the bar`() {
        assertEquals(0.25f, PomodoroSettings().started(start).reading(start + 1_350_000).progress)
        assertEquals(0f, PomodoroSettings().started(start).reading(start + 1_800_000).progress)
        val shortened = PomodoroSettings().started(start).copy(focusMinutes = 10)
        assertEquals(1f, shortened.reading(start + 1000).progress)
        assertEquals(1799, shortened.reading(start + 1000).seconds)
    }

    @Test
    fun `the time is minutes and seconds with two digits`() {
        assertEquals("30:00", formatTimer(1800))
        assertEquals("00:59", formatTimer(59))
        assertEquals("120:00", formatTimer(7200))
        assertTrue(PomodoroSettings(focusMinutes = 0, breakMinutes = -3).reading(start).total == 60)
    }
}
