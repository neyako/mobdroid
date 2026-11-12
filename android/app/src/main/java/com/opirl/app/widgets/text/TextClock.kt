package com.opirl.app.widgets.text

import java.time.Instant
import java.time.ZoneId

const val LAP_FINISHED = -1L

val TIMER_STEPS = listOf(1, 2, 5, 15, 60)

fun TextTimer.timeLeft(now: Long): Long = maxOf(endTime - now, 0)

fun TextTimer.add(milliseconds: Long, now: Long): TextTimer =
    copy(endTime = (if (endTime < now) now else endTime) + milliseconds)

fun TextTimer.set(seconds: Long, now: Long): TextTimer = copy(endTime = now + seconds * 1000)

fun TextStopwatch.elapsed(now: Long): Long = if (running) total + maxOf(now - startedAt, 0) else total

fun TextStopwatch.toggled(now: Long): TextStopwatch =
    if (running) copy(total = elapsed(now), running = false) else copy(startedAt = now, running = true)

fun TextStopwatch.set(seconds: Long, now: Long): TextStopwatch = copy(total = seconds * 1000, startedAt = now)

fun TextLaps.lap(now: Long): TextLaps {
    val closed = if (start != 0L && laps.isNotEmpty()) laps.dropLast(1) + (now - start) else laps
    return TextLaps(now, closed + 0L)
}

fun TextLaps.finish(now: Long): TextLaps {
    if (start == 0L) return this
    val closed = if (laps.isNotEmpty()) laps.dropLast(1) + (now - start) else laps
    return TextLaps(0, closed + LAP_FINISHED)
}

fun TextLaps.lines(now: Long): List<String> {
    var lap = 1
    return laps.mapIndexed { index, time ->
        val value = if (index == laps.lastIndex && start != 0L) now - start else time
        if (value == LAP_FINISHED) {
            lap = 1
            "🏁 Finished 🏁"
        } else {
            "Lap ${lap++} ${formatDuration(value / 1000)}"
        }
    }
}

fun TextLaps.running(): Boolean = start != 0L && laps.isNotEmpty()

fun nextSecond(now: Long): Long = now - now % 1000 + 1000

fun nextMinute(now: Long): Long = now - now % 60_000 + 60_000

fun nextMidnight(now: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

private fun nextWhole(elapsed: Long, now: Long): Long = now + 1000 - elapsed % 1000

fun TextTimer.nextChange(now: Long): Long? {
    val left = endTime - now
    return if (left < 1000) null else endTime - left / 1000 * 1000 + 1
}

fun TextStopwatch.nextChange(now: Long): Long? = if (running) nextWhole(elapsed(now), now) else null

fun TextLaps.nextChange(now: Long): Long? = if (running()) nextWhole(now - start, now) else null

fun nextRedraw(parts: List<TextPart>, options: TextSettings, now: Long, zone: ZoneId): Long {
    var next = Long.MAX_VALUE
    var timer = 0
    var stopwatch = 0
    var laps = 0
    for (part in parts) {
        if (part !is TextPart.Simple) continue
        val time = when (part.field) {
            SimpleField.CLOCK, SimpleField.G_FORCE, SimpleField.G_FORCE_RECENT_MAX, SimpleField.G_FORCE_MAX -> nextSecond(now)
            SimpleField.SHORT_CLOCK -> nextMinute(now)
            SimpleField.DATE, SimpleField.FULL_DATE -> nextMidnight(now, zone)
            SimpleField.TIMER -> options.timers.getOrNull(timer++)?.nextChange(now)
            SimpleField.STOPWATCH -> options.stopwatches.getOrNull(stopwatch++)?.nextChange(now)
            SimpleField.LAP_TIMES -> options.lapTimes.getOrNull(laps++)?.nextChange(now)
            else -> null
        }
        if (time != null) next = minOf(next, time)
    }
    if (options.delay > 0f && parts.live) next = minOf(next, nextSecond(now))
    return next
}
