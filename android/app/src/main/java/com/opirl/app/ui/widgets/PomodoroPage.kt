package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.pomodoro.BREAK_MINUTES
import com.opirl.app.widgets.pomodoro.FOCUS_MINUTES
import com.opirl.app.widgets.pomodoro.PomodoroBreakIcon
import com.opirl.app.widgets.pomodoro.PomodoroFocusIcon
import com.opirl.app.widgets.pomodoro.PomodoroPhase
import com.opirl.app.widgets.pomodoro.PomodoroSettings
import com.opirl.app.widgets.pomodoro.paused
import com.opirl.app.widgets.pomodoro.reset
import com.opirl.app.widgets.pomodoro.running
import com.opirl.app.widgets.pomodoro.skipped
import com.opirl.app.widgets.pomodoro.started
import com.opirl.app.widgets.pomodoro.withMinutes

private val PomodoroFocusIcon.label: Int
    get() = when (this) {
        PomodoroFocusIcon.SUN -> R.string.pomodoro_icon_sun
        PomodoroFocusIcon.BOLT -> R.string.pomodoro_icon_bolt
        PomodoroFocusIcon.CAP -> R.string.pomodoro_icon_cap
        PomodoroFocusIcon.BOOK -> R.string.pomodoro_icon_book
        PomodoroFocusIcon.PLAY -> R.string.pomodoro_icon_play
        PomodoroFocusIcon.BRAIN -> R.string.pomodoro_icon_brain
        PomodoroFocusIcon.PENCIL -> R.string.pomodoro_icon_pencil
        PomodoroFocusIcon.FLAME -> R.string.pomodoro_icon_flame
        PomodoroFocusIcon.TIMER -> R.string.pomodoro_icon_timer
        PomodoroFocusIcon.TARGET -> R.string.pomodoro_icon_target
    }

private val PomodoroBreakIcon.label: Int
    get() = when (this) {
        PomodoroBreakIcon.CUP -> R.string.pomodoro_icon_cup
        PomodoroBreakIcon.DANCE -> R.string.pomodoro_icon_dance
        PomodoroBreakIcon.WALK -> R.string.pomodoro_icon_walk
        PomodoroBreakIcon.MUSIC -> R.string.pomodoro_icon_music
        PomodoroBreakIcon.LEAF -> R.string.pomodoro_icon_leaf
        PomodoroBreakIcon.GAME -> R.string.pomodoro_icon_game
        PomodoroBreakIcon.FORK -> R.string.pomodoro_icon_fork
    }

@Composable
internal fun PomodoroPage(scope: WidgetScope) {
    val pomodoro = scope.widget.pomodoro
    val update = { transform: (PomodoroSettings) -> PomodoroSettings -> scope.update { it.copy(pomodoro = transform(it.pomodoro)) } }
    PomodoroControls(scope)
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.pomodoro_focus_duration),
                FOCUS_MINUTES,
                pomodoro.focusMinutes,
                { stringResource(R.string.pomodoro_minutes, it) },
            ) { minutes ->
                val now = System.currentTimeMillis()
                update { it.withMinutes(PomodoroPhase.FOCUS, minutes, now) }
            }
        }
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.pomodoro_break_duration),
                BREAK_MINUTES,
                pomodoro.breakMinutes,
                { stringResource(R.string.pomodoro_minutes, it) },
            ) { minutes ->
                val now = System.currentTimeMillis()
                update { it.withMinutes(PomodoroPhase.BREAK, minutes, now) }
            }
        }
    }
    Group {
        row { shapes ->
            TextRow(shapes, stringResource(R.string.pomodoro_focus_name), pomodoro.focusName) { name ->
                update { it.copy(focusName = name) }
            }
        }
        row { shapes ->
            TextRow(shapes, stringResource(R.string.pomodoro_break_name), pomodoro.breakName) { name ->
                update { it.copy(breakName = name) }
            }
        }
    }
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.pomodoro_focus_icon),
                PomodoroFocusIcon.entries,
                pomodoro.focusIcon,
                { stringResource(it.label) },
            ) { icon -> update { it.copy(focusIcon = icon) } }
        }
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.pomodoro_break_icon),
                PomodoroBreakIcon.entries,
                pomodoro.breakIcon,
                { stringResource(it.label) },
            ) { icon -> update { it.copy(breakIcon = icon) } }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.width), pomodoro.widthPercent, 100..500, "${pomodoro.widthPercent}%", 5) { width ->
                update { it.copy(widthPercent = width) }
            }
        }
    }
    Group {
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.pomodoro_background), pomodoro.background) { color ->
                update { it.copy(background = color) }
            }
        }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.pomodoro_text_color), pomodoro.foreground) { color ->
                update { it.copy(foreground = color) }
            }
        }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.pomodoro_focus_color), pomodoro.focusColor) { color ->
                update { it.copy(focusColor = color) }
            }
        }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.pomodoro_break_color), pomodoro.breakColor) { color ->
                update { it.copy(breakColor = color) }
            }
        }
    }
}

@Composable
internal fun PomodoroControls(scope: WidgetScope) {
    val pomodoro = scope.widget.pomodoro
    val change = { transform: PomodoroSettings.(Long) -> PomodoroSettings ->
        val now = System.currentTimeMillis()
        scope.update { it.copy(pomodoro = it.pomodoro.transform(now)) }
    }
    Group {
        row { shapes ->
            if (pomodoro.running) {
                ActionRow(shapes, stringResource(R.string.pomodoro_pause), Icons.Rounded.Pause) { change { paused(it) } }
            } else {
                val fresh = pomodoro.secondsLeft == null && pomodoro.phase == PomodoroPhase.FOCUS
                val title = stringResource(if (fresh) R.string.pomodoro_start else R.string.pomodoro_resume)
                ActionRow(shapes, title, Icons.Rounded.PlayArrow) { change { started(it) } }
            }
        }
        row { shapes -> ActionRow(shapes, stringResource(R.string.skip), Icons.Rounded.SkipNext) { change { skipped(it) } } }
        row { shapes -> ActionRow(shapes, stringResource(R.string.reset), Icons.Rounded.RestartAlt) { change { reset() } } }
    }
}
