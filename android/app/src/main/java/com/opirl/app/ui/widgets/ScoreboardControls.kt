package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.ChoiceRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.GroupBuilder
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.scoreboard.ControlKey
import com.opirl.app.widgets.scoreboard.ControlKind
import com.opirl.app.widgets.scoreboard.GOLF_NO_SCORE
import com.opirl.app.widgets.scoreboard.GenericScoreboard
import com.opirl.app.widgets.scoreboard.GolfScoreboard
import com.opirl.app.widgets.scoreboard.PadelScoreboard
import com.opirl.app.widgets.scoreboard.ScoreboardClock
import com.opirl.app.widgets.scoreboard.ScoreboardSettings
import com.opirl.app.widgets.scoreboard.ScoreboardSport
import com.opirl.app.widgets.scoreboard.Side
import com.opirl.app.widgets.scoreboard.adjusted
import com.opirl.app.widgets.scoreboard.currentSet
import com.opirl.app.widgets.scoreboard.cycled
import com.opirl.app.widgets.scoreboard.formatClock
import com.opirl.app.widgets.scoreboard.golfHolder
import com.opirl.app.widgets.scoreboard.hole
import com.opirl.app.widgets.scoreboard.increment
import com.opirl.app.widgets.scoreboard.isSetCompleted
import com.opirl.app.widgets.scoreboard.modularTemplate
import com.opirl.app.widgets.scoreboard.newMatch
import com.opirl.app.widgets.scoreboard.newRound
import com.opirl.app.widgets.scoreboard.nextSet
import com.opirl.app.widgets.scoreboard.parAt
import com.opirl.app.widgets.scoreboard.parseClock
import com.opirl.app.widgets.scoreboard.resetScore
import com.opirl.app.widgets.scoreboard.running
import com.opirl.app.widgets.scoreboard.selected
import com.opirl.app.widgets.scoreboard.stat
import com.opirl.app.widgets.scoreboard.team
import com.opirl.app.widgets.scoreboard.toggled
import com.opirl.app.widgets.scoreboard.undo
import com.opirl.app.widgets.scoreboard.valueAt
import com.opirl.app.widgets.scoreboard.withPossession
import com.opirl.app.widgets.scoreboard.withStrokes
import com.opirl.app.widgets.scoreboard.withTime
import kotlinx.coroutines.delay

@Composable
internal fun ScoreboardControls(scope: WidgetScope) {
    val settings = scope.widget.scoreboard
    when (settings.sport) {
        ScoreboardSport.GENERIC -> GenericControls(scope, settings.generic)
        ScoreboardSport.PADEL -> PadelControls(scope, settings.padel)
        ScoreboardSport.GOLF, ScoreboardSport.GOLF_FULL_SCORECARD -> GolfControls(scope)
        else -> ModularControls(scope, settings)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ButtonsRow(shapes: ListItemShapes, title: String, labels: List<String>, onTap: (Int) -> Unit) {
    ChoiceRow<Int?>(shapes, title, labels.indices.toList(), null, { labels[it ?: 0] }) { index -> if (index != null) onTap(index) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConfirmRow(shapes: ListItemShapes, title: String, icon: ImageVector, onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    val shown = if (armed) stringResource(R.string.scoreboard_tap_again) else title
    ActionRow(shapes, shown, icon, MaterialTheme.colorScheme.error) {
        if (armed) onConfirm()
        armed = !armed
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun GroupBuilder.clockRows(clock: ScoreboardClock, change: ((ScoreboardClock) -> ScoreboardClock) -> Unit) {
    row { shapes ->
        val running = clock.running
        val title = stringResource(if (running) R.string.scoreboard_stop_clock else R.string.scoreboard_start_clock)
        ActionRow(shapes, title, if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow) {
            change { it.toggled(System.currentTimeMillis()) }
        }
    }
    row { shapes ->
        var text by remember(clock) { mutableStateOf(formatClock(clock.valueAt(System.currentTimeMillis()))) }
        TextRow(shapes, stringResource(R.string.scoreboard_set_clock), text, "0:00") { typed ->
            text = typed
            parseClock(typed)?.let { total -> change { it.withTime(total, System.currentTimeMillis()) } }
        }
    }
}

private fun String.short() = if (length > 14) take(13) + "…" else this

@Composable
private fun GenericControls(scope: WidgetScope, generic: GenericScoreboard) {
    val labels = listOf(
        stringResource(R.string.scoreboard_plus_home),
        stringResource(R.string.scoreboard_plus_away),
        stringResource(R.string.scoreboard_undo),
    )
    Group {
        row { shapes ->
            val title = "${stringResource(R.string.scoreboard_score)}  ${generic.score.home} : ${generic.score.away}"
            ButtonsRow(shapes, title, labels) { tap ->
                scope.generic { if (tap == 0) it.increment(Side.HOME) else if (tap == 1) it.increment(Side.AWAY) else it.undo() }
            }
        }
        row { shapes ->
            val title = stringResource(R.string.scoreboard_reset_score)
            ConfirmRow(shapes, title, Icons.Rounded.RestartAlt) { scope.generic { it.resetScore() } }
        }
    }
    Group {
        clockRows(generic.clock) { change -> scope.generic { it.copy(clock = change(it.clock)) } }
        textRow(R.string.scoreboard_title, generic.title) { v -> scope.generic { it.copy(title = v) } }
    }
}

@Composable
private fun PadelControls(scope: WidgetScope, padel: PadelScoreboard) {
    val completed = isSetCompleted(padel.currentSet)
    val labels = listOf(
        stringResource(if (completed) R.string.scoreboard_next_set else R.string.scoreboard_plus_home),
        stringResource(if (completed) R.string.scoreboard_next_set else R.string.scoreboard_plus_away),
        stringResource(R.string.scoreboard_undo),
    )
    Group {
        row { shapes ->
            ButtonsRow(shapes, padel.sets.joinToString("  ") { "${it.home}-${it.away}" }, labels) { tap ->
                scope.padel { if (tap == 0) it.increment(Side.HOME) else if (tap == 1) it.increment(Side.AWAY) else it.undo() }
            }
        }
        row { shapes ->
            val title = stringResource(R.string.scoreboard_reset_score)
            ConfirmRow(shapes, title, Icons.Rounded.RestartAlt) { scope.padel { it.resetScore() } }
        }
    }
    PadelPlayerPickers(scope, padel)
}

@Composable
private fun GolfControls(scope: WidgetScope) {
    val holder = golfHolder(scope.widget, scope.settings.widgets)
    val golf = holder.scoreboard.golf
    val update = { change: (GolfScoreboard) -> GolfScoreboard ->
        scope.runtime.update(holder.id) { it.copy(scoreboard = it.scoreboard.copy(golf = change(it.scoreboard.golf))) }
    }
    val hole = golf.hole
    val par = stringResource(R.string.scoreboard_par)
    val labels = listOf("-1", par, "+1", stringResource(R.string.scoreboard_clear))
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_hole_number, hole + 1)
            SliderRow(shapes, title, hole + 1, 1..golf.numberOfHoles, "$par ${golf.parAt(hole)}") { v ->
                update { it.copy(currentHole = v - 1) }
            }
        }
    }
    golf.players.forEachIndexed { index, player ->
        Group {
            row { shapes ->
                val strokes = player.scores.getOrNull(hole)?.takeIf { it >= 0 }?.toString() ?: "-"
                ButtonsRow(shapes, "${player.name.short()}  $strokes", labels) { tap ->
                    update {
                        when (tap) {
                            0 -> it.adjusted(index, hole, -1)
                            1 -> it.withStrokes(index, hole, it.parAt(hole))
                            2 -> it.adjusted(index, hole, 1)
                            else -> it.withStrokes(index, hole, GOLF_NO_SCORE)
                        }
                    }
                }
            }
        }
    }
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_new_round)
            ConfirmRow(shapes, title, Icons.Rounded.RestartAlt) { update { it.newRound() } }
        }
    }
}

@Composable
private fun ModularControls(scope: WidgetScope, settings: ScoreboardSettings) {
    val modular = settings.modular
    val template = modularTemplate(settings.sport)
    for (side in listOf(Side.HOME, Side.AWAY)) {
        val team = modular.team(side)
        val name = team.name.short()
        Group {
            for (control in template.controls) {
                row { shapes ->
                    when (control.kind) {
                        ControlKind.COUNTER -> {
                            val set = modular.period.trim().toIntOrNull()?.takeIf { it > 0 } ?: 1
                            val value = if (control.key == ControlKey.GAME) {
                                team.history.getOrNull(set - 1).orEmpty().ifEmpty { "0" }
                            } else {
                                team.stat(control.key)
                            }
                            ButtonsRow(shapes, "$name  $value", listOf("+${control.label}", "-${control.label}")) { tap ->
                                scope.modular { it.adjusted(template, side, control.key, if (tap == 0) 1 else -1) }
                            }
                        }
                        ControlKind.SELECT -> {
                            val title = "$name  ${control.label}"
                            PickerRow(shapes, title, control.options, team.stat(control.key), { it }) { v ->
                                scope.modular { it.selected(side, control.key, v) }
                            }
                        }
                        ControlKind.CYCLE -> {
                            val value = team.stat(control.key)
                            val title = "$name  " + if (control.label.isEmpty()) value else "${control.label}: $value"
                            ActionRow(shapes, title, Icons.Rounded.Sync) { scope.modular { it.cycled(template, side, control.key) } }
                        }
                        ControlKind.TOGGLE_TEAM -> {
                            val icon = if (team.possession) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked
                            ActionRow(shapes, "$name  ${control.label}", icon) { scope.modular { it.withPossession(side) } }
                        }
                    }
                }
            }
        }
    }
    if (modular.showInfoBox && modular.showClock) {
        Group { clockRows(modular.clock) { change -> scope.modular { it.copy(clock = change(it.clock)) } } }
    }
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_next_period)
            ConfirmRow(shapes, title, Icons.Rounded.SkipNext) { scope.modular { it.nextSet(template) } }
        }
        row { shapes ->
            ConfirmRow(shapes, stringResource(R.string.scoreboard_new_match), Icons.Rounded.RestartAlt) {
                scope.modular { it.newMatch(template, System.currentTimeMillis()) }
            }
        }
    }
}
