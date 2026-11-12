package com.opirl.app.widgets.pomodoro

class PomodoroReading(val phase: PomodoroPhase, val seconds: Int, val total: Int, val nextChange: Long?) {
    val progress: Float get() = (seconds.toFloat() / total).coerceIn(0f, 1f)
}

private fun PomodoroPhase.other() = if (this == PomodoroPhase.FOCUS) PomodoroPhase.BREAK else PomodoroPhase.FOCUS

val PomodoroSettings.running: Boolean get() = endsAt != 0L

private fun PomodoroSettings.totalSeconds(phase: PomodoroPhase): Int =
    (if (phase == PomodoroPhase.FOCUS) focusMinutes else breakMinutes).coerceIn(1, 999) * 60

private fun PomodoroSettings.length(phase: PomodoroPhase): Long = (totalSeconds(phase) + 1) * 1000L

private fun PomodoroSettings.resolved(now: Long): PomodoroSettings {
    if (!running || now < endsAt) return this
    val cycle = length(PomodoroPhase.FOCUS) + length(PomodoroPhase.BREAK)
    var end = endsAt + (now - endsAt) / cycle * cycle
    var current = phase
    while (now >= end) {
        current = current.other()
        end += length(current)
    }
    return copy(phase = current, endsAt = end)
}

fun PomodoroSettings.reading(now: Long): PomodoroReading {
    val state = resolved(now)
    val total = totalSeconds(state.phase)
    if (!state.running) return PomodoroReading(state.phase, (state.secondsLeft ?: total).coerceIn(0, total), total, null)
    val seconds = ((state.endsAt - now + 999) / 1000 - 1).toInt().coerceAtLeast(0)
    return PomodoroReading(state.phase, seconds, total, state.endsAt - seconds * 1000L)
}

fun PomodoroSettings.started(now: Long): PomodoroSettings {
    if (running) return this
    return copy(endsAt = now + (reading(now).seconds + 1) * 1000L, secondsLeft = null)
}

fun PomodoroSettings.paused(now: Long): PomodoroSettings {
    if (!running) return this
    val reading = reading(now)
    return copy(phase = reading.phase, endsAt = 0, secondsLeft = reading.seconds.takeIf { it != reading.total })
}

fun PomodoroSettings.skipped(now: Long): PomodoroSettings {
    val current = resolved(now)
    val next = current.phase.other()
    return if (current.running) current.copy(phase = next, endsAt = now + length(next)) else current.copy(phase = next, secondsLeft = null)
}

fun PomodoroSettings.reset(): PomodoroSettings = copy(phase = PomodoroPhase.FOCUS, endsAt = 0, secondsLeft = null)

fun PomodoroSettings.withMinutes(phase: PomodoroPhase, minutes: Int, now: Long): PomodoroSettings {
    val current = resolved(now)
    val changed = if (phase == PomodoroPhase.FOCUS) current.copy(focusMinutes = minutes) else current.copy(breakMinutes = minutes)
    return if (current.phase == phase && !current.running) changed.copy(secondsLeft = null) else changed
}

fun formatTimer(seconds: Int): String =
    "${(seconds / 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
