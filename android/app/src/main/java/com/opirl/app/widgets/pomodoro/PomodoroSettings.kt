package com.opirl.app.widgets.pomodoro

import kotlinx.serialization.Serializable

@Serializable
enum class PomodoroPhase { FOCUS, BREAK }

@Serializable
enum class PomodoroFocusIcon { SUN, BOLT, CAP, BOOK, PLAY, BRAIN, PENCIL, FLAME, TIMER, TARGET }

@Serializable
enum class PomodoroBreakIcon { CUP, DANCE, WALK, MUSIC, LEAF, GAME, FORK }

@Serializable
data class PomodoroSettings(
    val focusMinutes: Int = 30,
    val breakMinutes: Int = 5,
    val widthPercent: Int = 160,
    val focusName: String = "Focus",
    val breakName: String = "Break",
    val focusIcon: PomodoroFocusIcon = PomodoroFocusIcon.SUN,
    val breakIcon: PomodoroBreakIcon = PomodoroBreakIcon.CUP,
    val background: Int = 0xBF000000.toInt(),
    val foreground: Int = 0xFFFFFFFF.toInt(),
    val focusColor: Int = 0xFF7AB5FF.toInt(),
    val breakColor: Int = 0xFF67D045.toInt(),
    val phase: PomodoroPhase = PomodoroPhase.FOCUS,
    val endsAt: Long = 0,
    val secondsLeft: Int? = null,
)

val FOCUS_MINUTES = listOf(1, 2, 3, 5, 10, 15, 20, 25, 30, 45, 60, 90, 120)

val BREAK_MINUTES = listOf(1, 2, 3, 5, 7, 10, 15, 20, 25, 30)
