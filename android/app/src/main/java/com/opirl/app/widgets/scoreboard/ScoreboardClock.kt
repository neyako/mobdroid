package com.opirl.app.widgets.scoreboard

private val CLOCK_TEXT = Regex("(\\d{1,3}):([0-5]\\d)")

private val ScoreboardClock.limit: Int get() = maximum.coerceIn(1, 180) * 60

val ScoreboardClock.running: Boolean get() = startedAt != 0L

fun ScoreboardClock.valueAt(now: Long): Int {
    if (!running) return seconds.coerceIn(0, limit)
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    val value = if (direction == ClockDirection.UP) seconds + elapsed else seconds - elapsed
    return value.coerceIn(0, limit.toLong()).toInt()
}

fun formatClock(total: Int): String = "${total / 60}:${(total % 60).toString().padStart(2, '0')}"

fun ScoreboardClock.format(now: Long): String = formatClock(valueAt(now))

fun parseClock(text: String): Int? = CLOCK_TEXT.matchEntire(text.trim())?.destructured?.let { (minutes, seconds) ->
    minutes.toInt() * 60 + seconds.toInt()
}

fun ScoreboardClock.nextChangeAt(now: Long): Long? {
    if (!running) return null
    val value = valueAt(now)
    if (if (direction == ClockDirection.UP) value >= limit else value <= 0) return null
    val elapsed = ((now - startedAt) / 1000).coerceAtLeast(0)
    return minOf(startedAt + (elapsed + 1) * 1000, now + 1000)
}

fun ScoreboardClock.toggled(now: Long): ScoreboardClock =
    if (running) copy(seconds = valueAt(now), startedAt = 0) else copy(startedAt = now)

fun ScoreboardClock.withTime(total: Int, now: Long): ScoreboardClock =
    copy(seconds = total.coerceIn(0, limit), startedAt = if (running) now else 0)

fun ScoreboardClock.reset(now: Long): ScoreboardClock =
    withTime(if (direction == ClockDirection.UP) 0 else limit, now)

fun ScoreboardClock.withMaximum(minutes: Int, now: Long): ScoreboardClock =
    copy(maximum = minutes.coerceIn(1, 180)).reset(now)

fun ScoreboardClock.withDirection(direction: ClockDirection, now: Long): ScoreboardClock =
    copy(direction = direction).reset(now)
