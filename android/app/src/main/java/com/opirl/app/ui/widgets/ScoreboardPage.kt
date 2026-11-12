package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.ChoiceRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.GroupBuilder
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.RemovableRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.scoreboard.ClockDirection
import com.opirl.app.widgets.scoreboard.GOLF_MAX_PLAYERS
import com.opirl.app.widgets.scoreboard.GenericScoreboard
import com.opirl.app.widgets.scoreboard.GolfScoreboard
import com.opirl.app.widgets.scoreboard.HISTORY_SETS
import com.opirl.app.widgets.scoreboard.ModularScoreboard
import com.opirl.app.widgets.scoreboard.ModularTeam
import com.opirl.app.widgets.scoreboard.PadelScoreboard
import com.opirl.app.widgets.scoreboard.PadelType
import com.opirl.app.widgets.scoreboard.ScoreboardClock
import com.opirl.app.widgets.scoreboard.ScoreboardLayout
import com.opirl.app.widgets.scoreboard.ScoreboardSettings
import com.opirl.app.widgets.scoreboard.ScoreboardSport
import com.opirl.app.widgets.scoreboard.Side
import com.opirl.app.widgets.scoreboard.modularTemplate
import com.opirl.app.widgets.scoreboard.playerName
import com.opirl.app.widgets.scoreboard.team
import com.opirl.app.widgets.scoreboard.withDirection
import com.opirl.app.widgets.scoreboard.withHistoryScore
import com.opirl.app.widgets.scoreboard.withHoles
import com.opirl.app.widgets.scoreboard.withMaximum
import com.opirl.app.widgets.scoreboard.withNewPlayer
import com.opirl.app.widgets.scoreboard.withPar
import com.opirl.app.widgets.scoreboard.withPlayer
import com.opirl.app.widgets.scoreboard.withPlayerName
import com.opirl.app.widgets.scoreboard.withSport
import com.opirl.app.widgets.scoreboard.withoutPlayer

internal fun WidgetScope.scoreboard(change: (ScoreboardSettings) -> ScoreboardSettings) =
    update { it.copy(scoreboard = change(it.scoreboard)) }

internal fun WidgetScope.generic(change: (GenericScoreboard) -> GenericScoreboard) =
    scoreboard { it.copy(generic = change(it.generic)) }

internal fun WidgetScope.padel(change: (PadelScoreboard) -> PadelScoreboard) =
    scoreboard { it.copy(padel = change(it.padel)) }

internal fun WidgetScope.golf(change: (GolfScoreboard) -> GolfScoreboard) =
    scoreboard { it.copy(golf = change(it.golf)) }

internal fun WidgetScope.modular(change: (ModularScoreboard) -> ModularScoreboard) =
    scoreboard { it.copy(modular = change(it.modular)) }

internal fun WidgetScope.team(side: Side, change: (ModularTeam) -> ModularTeam) = modular {
    if (side == Side.HOME) it.copy(home = change(it.home)) else it.copy(away = change(it.away))
}

internal fun GroupBuilder.textRow(title: Int, value: String, placeholder: String? = null, onChange: (String) -> Unit) {
    row { TextRow(it, stringResource(title), value, placeholder, onChange = onChange) }
}

private fun GroupBuilder.colorRow(title: Int, color: Int, onChange: (Int) -> Unit) {
    row { ColorRow(it, stringResource(title), color, onChange) }
}

private fun GroupBuilder.switchRow(title: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    row { SwitchRow(it, stringResource(title), checked, enabled, onChange) }
}

private fun GroupBuilder.navRow(scope: WidgetScope, title: Int, page: String, value: String? = null, pageTitle: String? = null) {
    row { NavRow(it, stringResource(title), value) { scope.push(page, pageTitle ?: scope.widget.name) } }
}

private fun GroupBuilder.clockSetupRows(clock: ScoreboardClock, change: ((ScoreboardClock) -> ScoreboardClock) -> Unit) {
    row { shapes ->
        val label = stringResource(R.string.scoreboard_minutes, clock.maximum)
        SliderRow(shapes, stringResource(R.string.scoreboard_maximum), clock.maximum, 1..180, label) { minutes ->
            change { it.withMaximum(minutes, System.currentTimeMillis()) }
        }
    }
    row { shapes ->
        val directions = ClockDirection.entries
        val title = stringResource(R.string.scoreboard_direction)
        ChoiceRow(shapes, title, directions, clock.direction, { stringResource(it.label) }) { direction ->
            change { it.withDirection(direction, System.currentTimeMillis()) }
        }
    }
}

internal val ScoreboardSport.label: Int
    get() = when (this) {
        ScoreboardSport.GENERIC -> R.string.scoreboard_sport_generic
        ScoreboardSport.PADEL -> R.string.scoreboard_sport_padel
        ScoreboardSport.GOLF -> R.string.scoreboard_sport_golf
        ScoreboardSport.GOLF_FULL_SCORECARD -> R.string.scoreboard_sport_golf_card
        ScoreboardSport.BASKETBALL -> R.string.scoreboard_sport_basketball
        ScoreboardSport.GENERIC_2 -> R.string.scoreboard_sport_generic_2
        ScoreboardSport.GENERIC_SETS -> R.string.scoreboard_sport_generic_sets
        ScoreboardSport.HOCKEY -> R.string.scoreboard_sport_hockey
        ScoreboardSport.FOOTBALL -> R.string.scoreboard_sport_football
        ScoreboardSport.TENNIS -> R.string.scoreboard_sport_tennis
        ScoreboardSport.VOLLEYBALL -> R.string.scoreboard_sport_volleyball
    }

private val ScoreboardLayout.label: Int
    get() = when (this) {
        ScoreboardLayout.STACKED -> R.string.scoreboard_layout_stacked
        ScoreboardLayout.STACKED_INLINE -> R.string.scoreboard_layout_stacked_inline
        ScoreboardLayout.SIDE_BY_SIDE -> R.string.scoreboard_layout_side_by_side
        ScoreboardLayout.STACK_HISTORY -> R.string.scoreboard_layout_stack_history
    }

private val ClockDirection.label: Int
    get() = if (this == ClockDirection.UP) R.string.scoreboard_up else R.string.scoreboard_down

private val PadelType.label: Int
    get() = if (this == PadelType.DOUBLES) R.string.scoreboard_doubles else R.string.scoreboard_singles

@Composable
internal fun ScoreboardPage(scope: WidgetScope) {
    val settings = scope.widget.scoreboard
    val page = scope.page
    if (page != null) {
        ScoreboardSubPage(scope, settings, page)
        return
    }
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_sport)
            PickerRow(shapes, title, ScoreboardSport.entries, settings.sport, { stringResource(it.label) }) { sport ->
                scope.scoreboard { it.withSport(sport) }
            }
        }
    }
    when (settings.sport) {
        ScoreboardSport.GENERIC -> GenericSetup(scope, settings.generic)
        ScoreboardSport.PADEL -> PadelSetup(scope, settings.padel)
        ScoreboardSport.GOLF -> GolfSetup(scope, settings.golf)
        ScoreboardSport.GOLF_FULL_SCORECARD -> {
            Group {
                navRow(scope, R.string.scoreboard_colors, "colors")
                switchRow(R.string.scoreboard_show_pars, settings.showPars) { on -> scope.scoreboard { it.copy(showPars = on) } }
            }
            Note(stringResource(R.string.scoreboard_card_note))
        }
        else -> ModularSetup(scope, settings)
    }
}

@Composable
private fun GenericSetup(scope: WidgetScope, generic: GenericScoreboard) {
    Group {
        textRow(R.string.scoreboard_title, generic.title) { v -> scope.generic { it.copy(title = v) } }
        textRow(R.string.scoreboard_home, generic.home) { v -> scope.generic { it.copy(home = v) } }
        textRow(R.string.scoreboard_away, generic.away) { v -> scope.generic { it.copy(away = v) } }
    }
    Group { clockSetupRows(generic.clock) { change -> scope.generic { it.copy(clock = change(it.clock)) } } }
    Group { navRow(scope, R.string.scoreboard_colors, "colors") }
}

@Composable
private fun PadelSetup(scope: WidgetScope, padel: PadelScoreboard) {
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_game_type)
            ChoiceRow(shapes, title, PadelType.entries, padel.type, { stringResource(it.label) }) { type ->
                scope.padel { it.copy(type = type) }
            }
        }
        navRow(scope, R.string.scoreboard_colors, "colors")
    }
    PadelPlayerPickers(scope, padel)
    Group { navRow(scope, R.string.scoreboard_players, "players", "${padel.players.size}") }
}

@Composable
private fun GolfSetup(scope: WidgetScope, golf: GolfScoreboard) {
    Group {
        textRow(R.string.scoreboard_title, golf.title) { v -> scope.golf { it.copy(title = v) } }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.scoreboard_holes), listOf(9, 18), golf.numberOfHoles, { "$it" }) { holes ->
                scope.golf { it.withHoles(holes) }
            }
        }
        navRow(scope, R.string.scoreboard_pars, "pars", "${golf.pars.take(golf.numberOfHoles).sum()}")
        switchRow(R.string.scoreboard_player_colors, golf.playerColors) { on -> scope.golf { it.copy(playerColors = on) } }
        navRow(scope, R.string.scoreboard_colors, "colors")
    }
    Group {
        golf.players.forEachIndexed { index, player ->
            row { shapes ->
                RemovableRow(shapes, player.name, "", { scope.push("golfer:$index", player.name) }) {
                    scope.golf { it.withoutPlayer(index) }
                }
            }
        }
        if (golf.players.size < GOLF_MAX_PLAYERS) {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.scoreboard_add_player), Icons.Rounded.Add) { scope.golf { it.withNewPlayer() } }
            }
        }
    }
}

@Composable
private fun ModularSetup(scope: WidgetScope, settings: ScoreboardSettings) {
    val modular = settings.modular
    val layout = stringResource(modular.layout.label)
    Group {
        navRow(scope, R.string.scoreboard_layout, "layout", layout)
        navRow(scope, R.string.scoreboard_home, "team:home", modular.home.name, modular.home.name)
        navRow(scope, R.string.scoreboard_away, "team:away", modular.away.name, modular.away.name)
        navRow(scope, R.string.scoreboard_history, "history")
    }
    Group {
        textRow(R.string.scoreboard_title, modular.title) { v -> scope.modular { it.copy(title = v) } }
        textRow(R.string.scoreboard_period, modular.period, modularTemplate(settings.sport).period) { v ->
            scope.modular { it.copy(period = v) }
        }
        textRow(R.string.scoreboard_info_text, modular.infoBoxText) { v -> scope.modular { it.copy(infoBoxText = v) } }
    }
    Group { clockSetupRows(modular.clock) { change -> scope.modular { it.copy(clock = change(it.clock)) } } }
}

@Composable
private fun ScoreboardSubPage(scope: WidgetScope, settings: ScoreboardSettings, page: String) {
    when {
        page == "colors" -> ColorsPage(scope, settings)
        page == "players" -> PadelPlayersPage(scope, settings.padel)
        page.startsWith("player:") -> {
            val id = page.removePrefix("player:").toIntOrNull() ?: return
            Group { textRow(R.string.name, settings.padel.playerName(id)) { v -> scope.padel { it.withPlayerName(id, v) } } }
        }
        page == "pars" -> Group {
            for (hole in 0 until settings.golf.numberOfHoles) {
                val par = settings.golf.pars[hole]
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.scoreboard_hole_number, hole + 1), par, 1..9, "$par") { value ->
                        scope.golf { it.withPar(hole, value) }
                    }
                }
            }
        }
        page.startsWith("golfer:") -> {
            val index = page.removePrefix("golfer:").toIntOrNull() ?: return
            val player = settings.golf.players.getOrNull(index) ?: return
            Group {
                textRow(R.string.name, player.name) { v -> scope.golf { g -> g.withPlayer(index) { it.copy(name = v) } } }
                colorRow(R.string.scoreboard_player_color, player.color) { c ->
                    scope.golf { g -> g.withPlayer(index) { it.copy(color = c) } }
                }
            }
        }
        page == "layout" -> LayoutPage(scope, settings.modular)
        page == "team:home" -> TeamPage(scope, settings.modular.home, Side.HOME)
        page == "team:away" -> TeamPage(scope, settings.modular.away, Side.AWAY)
        page == "history" -> HistoryPage(scope, settings.modular)
    }
}

@Composable
private fun ColorsPage(scope: WidgetScope, settings: ScoreboardSettings) {
    Group {
        colorRow(R.string.scoreboard_text_color, settings.textColor) { c -> scope.scoreboard { it.copy(textColor = c) } }
        colorRow(R.string.scoreboard_primary_background, settings.primaryColor) { c -> scope.scoreboard { it.copy(primaryColor = c) } }
        colorRow(R.string.scoreboard_secondary_background, settings.secondaryColor) { c ->
            scope.scoreboard { it.copy(secondaryColor = c) }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.reset), Icons.Rounded.Refresh) {
                val base = ScoreboardSettings()
                scope.scoreboard {
                    it.copy(textColor = base.textColor, primaryColor = base.primaryColor, secondaryColor = base.secondaryColor)
                }
            }
        }
    }
}

@Composable
private fun PadelPlayersPage(scope: WidgetScope, padel: PadelScoreboard) {
    Group {
        padel.players.forEach { player ->
            row { shapes ->
                RemovableRow(shapes, player.name, "", { scope.push("player:${player.id}", player.name) }) {
                    scope.padel { it.withoutPlayer(player.id) }
                }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.scoreboard_add_player), Icons.Rounded.Add) { scope.padel { it.withNewPlayer() } }
        }
    }
}

@Composable
private fun LayoutPage(scope: WidgetScope, modular: ModularScoreboard) {
    Group {
        row { shapes ->
            val title = stringResource(R.string.scoreboard_layout)
            PickerRow(shapes, title, ScoreboardLayout.entries, modular.layout, { stringResource(it.label) }) { layout ->
                scope.modular { it.copy(layout = layout) }
            }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.width), modular.width, 100..1000, "${modular.width}") { v ->
                scope.modular { it.copy(width = v) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.scoreboard_row_height), modular.rowHeight, 10..150, "${modular.rowHeight}") { v ->
                scope.modular { it.copy(rowHeight = v) }
            }
        }
    }
    Group {
        switchRow(R.string.scoreboard_title, modular.showTitle) { on -> scope.modular { it.copy(showTitle = on) } }
        switchRow(R.string.scoreboard_more_stats, modular.showMoreStats) { on -> scope.modular { it.copy(showMoreStats = on) } }
        switchRow(R.string.scoreboard_info_box, modular.showInfoBox) { on -> scope.modular { it.copy(showInfoBox = on) } }
        switchRow(R.string.scoreboard_clock, modular.showClock, modular.showInfoBox) { on -> scope.modular { it.copy(showClock = on) } }
        switchRow(R.string.scoreboard_bold, modular.bold) { on -> scope.modular { it.copy(bold = on) } }
    }
}

@Composable
private fun TeamPage(scope: WidgetScope, team: ModularTeam, side: Side) {
    Group {
        textRow(R.string.name, team.name) { v -> scope.team(side) { it.copy(name = v) } }
        colorRow(R.string.scoreboard_text_color, team.textColor) { c -> scope.team(side) { it.copy(textColor = c) } }
        colorRow(R.string.scoreboard_background, team.backgroundColor) { c -> scope.team(side) { it.copy(backgroundColor = c) } }
    }
}

@Composable
private fun HistoryPage(scope: WidgetScope, modular: ModularScoreboard) {
    val values = listOf("") + (0..30).map { "$it" }
    for (set in 1..HISTORY_SETS) {
        Group {
            for (side in listOf(Side.HOME, Side.AWAY)) {
                val team = modular.team(side)
                row { shapes ->
                    val title = "${stringResource(R.string.scoreboard_set_number, set)}  ${team.name}"
                    PickerRow(shapes, title, values, team.history[set - 1], { it.ifEmpty { "-" } }) { value ->
                        scope.modular { it.withHistoryScore(side, set, value) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PadelPlayerPickers(scope: WidgetScope, padel: PadelScoreboard) {
    val ids = padel.players.map { it.id }
    val doubles = padel.type == PadelType.DOUBLES
    val slots = listOf(
        Triple(0, R.string.scoreboard_home, padel.home1),
        Triple(1, R.string.scoreboard_home, padel.home2),
        Triple(2, R.string.scoreboard_away, padel.away1),
        Triple(3, R.string.scoreboard_away, padel.away2),
    ).filter { doubles || it.first % 2 == 0 }
    Group {
        slots.forEach { (slot, title, id) ->
            row { shapes ->
                val name = stringResource(title) + if (doubles) " ${slot % 2 + 1}" else ""
                PickerRow(shapes, name, ids, id, { padel.playerName(it) }) { picked -> scope.padel { it.withPlayer(slot, picked) } }
            }
        }
    }
}
