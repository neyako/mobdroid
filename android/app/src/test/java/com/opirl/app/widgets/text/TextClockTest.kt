package com.opirl.app.widgets.text

import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextClockTest {
    @Test
    fun aTimerCountsDownAndAnExpiredOneRestartsFromNow() {
        val timer = TextTimer(5, endTime = 10_000)
        assertEquals(4_000, timer.timeLeft(6_000))
        assertEquals(0, timer.timeLeft(12_000))
        assertEquals(310_000, timer.add(300_000, 10_000).endTime)
        assertEquals(312_000, timer.add(300_000, 12_000).endTime)
        assertEquals(100_000, TextTimer(5, 400_000).add(-300_000, 10_000).endTime)
        assertEquals(65_000, timer.set(60, 5_000).endTime)
    }

    @Test
    fun aTimerIsDrawnAgainWhenItsSecondsChange() {
        val timer = TextTimer(5, endTime = 10_000)
        assertEquals(6_001, timer.nextChange(5_500))
        assertEquals(9_001, timer.nextChange(9_000))
        assertNull(timer.nextChange(9_001))
        assertNull(timer.nextChange(11_000))
    }

    @Test
    fun aStopwatchKeepsItsTimeWhenStoppedAndCountsWhileRunning() {
        val running = TextStopwatch().toggled(1_000)
        assertEquals(2_500, running.elapsed(3_500))
        assertEquals(4_000, running.nextChange(3_500))
        val paused = running.toggled(6_000)
        assertEquals(5_000, paused.elapsed(60_000))
        assertNull(paused.nextChange(60_000))
        assertEquals(10_000, paused.set(10, 70_000).elapsed(80_000))
        assertEquals(20_000, paused.toggled(70_000).set(10, 70_000).elapsed(80_000))
    }

    @Test
    fun lapsAreClosedByTheNextLapAndTheFinishEndsTheRace() {
        val first = TextLaps().lap(1_000)
        assertEquals(listOf(0L), first.laps)
        val second = first.lap(62_000)
        assertEquals(listOf(61_000L, 0L), second.laps)
        assertEquals(listOf("Lap 1 1m 1s", "Lap 2 8s"), second.lines(70_500))
        val finished = second.finish(100_000)
        assertEquals(listOf(61_000L, 38_000L, LAP_FINISHED), finished.laps)
        assertEquals(0L, finished.start)
        assertEquals(finished, finished.finish(120_000))
        assertEquals(listOf("Lap 1 1m 1s", "Lap 2 38s", "🏁 Finished 🏁"), finished.lines(130_000))
        assertNull(finished.nextChange(130_000))
        val again = finished.lap(200_000)
        assertEquals(listOf("Lap 1 1m 1s", "Lap 2 38s", "🏁 Finished 🏁", "Lap 1 3s"), again.lines(203_000))
        assertEquals(204_000, again.nextChange(203_000))
    }

    @Test
    fun theClockFieldsAreDrawnAgainOnTheirBoundaries() {
        assertEquals(1_000, nextSecond(0))
        assertEquals(6_000, nextSecond(5_999))
        assertEquals(120_000, nextMinute(60_000))
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val evening = 1_794_000_000_000L
        val midnight = nextMidnight(evening, zone)
        assertEquals(0, Instant.ofEpochMilli(midnight).atZone(zone).toLocalTime().toSecondOfDay())
        assertTrue(midnight > evening && midnight - evening <= 86_400_000)
    }

    @Test
    fun aWidgetIsDrawnAgainOnlyWhenAShownFieldChanges() {
        val zone = ZoneId.of("Asia/Ho_Chi_Minh")
        val now = 1_794_000_123_456L
        val redraw = { text: String, options: TextSettings -> nextRedraw(parseTextFormat(text), options.synced(parseTextFormat(text)), now, zone) }
        assertEquals(Long.MAX_VALUE, redraw("static {checkbox} {rating} {bitrate} {city} {speed}", TextSettings()))
        assertEquals(nextSecond(now), redraw("{time} {shortTime} {date}", TextSettings()))
        assertEquals(nextMinute(now), redraw("{shortTime} {date}", TextSettings()))
        assertEquals(nextMidnight(now, zone), redraw("{fullDate}", TextSettings()))
        assertEquals(nextSecond(now), redraw("{gForce}", TextSettings()))
        assertEquals(Long.MAX_VALUE, redraw("{timer} {stopwatch} {lapTimes}", TextSettings()))
        val busy = TextSettings(
            timers = listOf(TextTimer(5, now + 90_500)),
            stopwatches = listOf(TextStopwatch(0, now - 700, true)),
            lapTimes = listOf(TextLaps(now - 400, listOf(0))),
        )
        assertEquals(now + 300, redraw("{timer} {stopwatch} {lapTimes}", busy))
        assertEquals(now + 501, redraw("{timer} {lapTimes}", busy))
        assertEquals(now + 600, redraw("{lapTimes}", busy))
        assertEquals(nextSecond(now), redraw("{city}", TextSettings(delay = 2f)))
        assertEquals(Long.MAX_VALUE, redraw("plain", TextSettings(delay = 2f)))
    }
}
