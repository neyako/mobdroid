package com.opirl.app.widgets.scoreboard

enum class ControlKind { COUNTER, SELECT, CYCLE, TOGGLE_TEAM }

enum class ControlKey { PRIMARY, SECONDARY, GAME, STAT1, STAT2, STAT3, STAT4, POSSESSION }

class ModularControl(
    val key: ControlKey,
    val kind: ControlKind,
    val label: String,
    val options: List<String> = emptyList(),
    val periodReset: Boolean = false,
)

class ModularTemplate(
    val layout: ScoreboardLayout,
    val periodLabel: String,
    val period: String,
    val timer: String,
    val timerDown: Boolean,
    val primaryResetOnPeriod: Boolean = false,
    val changePossessionOnScore: Boolean = false,
    val tennis: Boolean = false,
    val secondaryLabel: String = "",
    val statLabels: List<String> = List(MODULAR_STATS) { "" },
    val home: ModularTeam,
    val away: ModularTeam,
    val controls: List<ModularControl>,
) {
    fun control(key: ControlKey) = controls.firstOrNull { it.key == key }
}

private fun initial(possession: Boolean = false, secondary: String = "", vararg stats: String) = ModularTeam(
    name = "",
    backgroundColor = 0,
    possession = possession,
    secondary = secondary,
    stats = List(MODULAR_STATS) { stats.getOrElse(it) { "" } },
)

private val POINTS = ModularControl(ControlKey.PRIMARY, ControlKind.COUNTER, "Pt")

private val BASKETBALL = ModularTemplate(
    layout = ScoreboardLayout.SIDE_BY_SIDE,
    periodLabel = "QTR",
    period = "1",
    timer = "10:00",
    timerDown = true,
    statLabels = listOf("TO", "FOUL", "", ""),
    home = initial(true, "", "5", "0", "NO BONUS"),
    away = initial(false, "", "5", "0", "NO BONUS"),
    controls = listOf(
        POINTS,
        ModularControl(ControlKey.STAT1, ControlKind.SELECT, "TO", (0..5).map { "$it" }, true),
        ModularControl(ControlKey.STAT2, ControlKind.CYCLE, "FOUL", (0..10).map { "$it" }, true),
        ModularControl(ControlKey.STAT3, ControlKind.CYCLE, "", listOf("NO BONUS", "BONUS", "DOUBLE"), true),
        ModularControl(ControlKey.POSSESSION, ControlKind.TOGGLE_TEAM, "POSS"),
    ),
)

private val GENERIC_2 = ModularTemplate(
    layout = ScoreboardLayout.STACKED,
    periodLabel = "",
    period = "",
    timer = "",
    timerDown = true,
    home = initial(),
    away = initial(),
    controls = listOf(POINTS),
)

private val GENERIC_SETS = ModularTemplate(
    layout = ScoreboardLayout.STACKED,
    periodLabel = "SET",
    period = "1",
    timer = "0:00",
    timerDown = false,
    primaryResetOnPeriod = true,
    home = initial(false, "0"),
    away = initial(false, "0"),
    controls = listOf(
        ModularControl(ControlKey.PRIMARY, ControlKind.COUNTER, "Pt", periodReset = true),
        ModularControl(ControlKey.SECONDARY, ControlKind.COUNTER, "Set"),
        ModularControl(ControlKey.POSSESSION, ControlKind.TOGGLE_TEAM, "POSS"),
    ),
)

private val HOCKEY = ModularTemplate(
    layout = ScoreboardLayout.SIDE_BY_SIDE,
    periodLabel = "PER",
    period = "1",
    timer = "15:00",
    timerDown = true,
    secondaryLabel = "SOG",
    home = initial(false, "0", "NO PP", "NO EN", "NO DP"),
    away = initial(false, "0", "NO PP", "NO EN", "NO DP"),
    controls = listOf(
        ModularControl(ControlKey.PRIMARY, ControlKind.COUNTER, "Goal"),
        ModularControl(ControlKey.SECONDARY, ControlKind.COUNTER, "SOG"),
        ModularControl(ControlKey.STAT1, ControlKind.CYCLE, "", listOf("NO PP", "PP"), true),
        ModularControl(ControlKey.STAT2, ControlKind.CYCLE, "", listOf("NO EN", "EN"), true),
        ModularControl(ControlKey.STAT3, ControlKind.CYCLE, "", listOf("NO DP", "DP"), true),
    ),
)

private val FOOTBALL = ModularTemplate(
    layout = ScoreboardLayout.STACKED,
    periodLabel = "HALF",
    period = "1",
    timer = "30:00",
    timerDown = true,
    home = initial(),
    away = initial(),
    controls = listOf(ModularControl(ControlKey.PRIMARY, ControlKind.COUNTER, "Goal")),
)

private val TENNIS = ModularTemplate(
    layout = ScoreboardLayout.STACK_HISTORY,
    periodLabel = "SET",
    period = "1",
    timer = "0:00",
    timerDown = false,
    primaryResetOnPeriod = true,
    tennis = true,
    home = initial(true),
    away = initial(),
    controls = listOf(
        POINTS,
        ModularControl(ControlKey.GAME, ControlKind.COUNTER, "Game", periodReset = true),
        ModularControl(ControlKey.POSSESSION, ControlKind.TOGGLE_TEAM, "SERVE"),
    ),
)

private val VOLLEYBALL = ModularTemplate(
    layout = ScoreboardLayout.STACKED,
    periodLabel = "SET",
    period = "1",
    timer = "0:00",
    timerDown = false,
    primaryResetOnPeriod = true,
    changePossessionOnScore = true,
    statLabels = listOf("TO", "", "", ""),
    home = initial(true, "0", "0"),
    away = initial(false, "0", "0"),
    controls = listOf(
        ModularControl(ControlKey.PRIMARY, ControlKind.COUNTER, "Pt", periodReset = true),
        ModularControl(ControlKey.SECONDARY, ControlKind.COUNTER, "Set"),
        ModularControl(ControlKey.STAT1, ControlKind.SELECT, "TO", (0..3).map { "$it" }, true),
        ModularControl(ControlKey.POSSESSION, ControlKind.TOGGLE_TEAM, "SERVE"),
    ),
)

fun modularTemplate(sport: ScoreboardSport): ModularTemplate = when (sport) {
    ScoreboardSport.BASKETBALL -> BASKETBALL
    ScoreboardSport.GENERIC_SETS -> GENERIC_SETS
    ScoreboardSport.HOCKEY -> HOCKEY
    ScoreboardSport.FOOTBALL -> FOOTBALL
    ScoreboardSport.TENNIS -> TENNIS
    ScoreboardSport.VOLLEYBALL -> VOLLEYBALL
    else -> GENERIC_2
}

fun ModularScoreboard.shownPeriod(template: ModularTemplate) = period.ifEmpty { template.period }

fun ModularScoreboard.periodFull(template: ModularTemplate, sport: ScoreboardSport): String =
    if (sport == ScoreboardSport.FOOTBALL) "" else "${template.periodLabel} ${shownPeriod(template)}".trim()

fun ModularScoreboard.infoLines(template: ModularTemplate, sport: ScoreboardSport, now: Long): List<String> =
    listOf(if (showClock) clock.format(now) else "", periodFull(template, sport), infoBoxText).filter { it.isNotEmpty() }

fun ModularScoreboard.team(side: Side) = if (side == Side.HOME) home else away

private fun ModularScoreboard.withTeam(side: Side, team: ModularTeam) =
    if (side == Side.HOME) copy(home = team) else copy(away = team)

private fun ModularScoreboard.mapTeams(change: (ModularTeam) -> ModularTeam) = copy(home = change(home), away = change(away))

private fun Side.other() = if (this == Side.HOME) Side.AWAY else Side.HOME

private fun String.count() = trim().toIntOrNull() ?: 0

fun ModularTeam.stat(key: ControlKey): String = when (key) {
    ControlKey.PRIMARY -> primary
    ControlKey.SECONDARY -> secondary
    ControlKey.STAT1 -> stats[0]
    ControlKey.STAT2 -> stats[1]
    ControlKey.STAT3 -> stats[2]
    ControlKey.STAT4 -> stats[3]
    else -> ""
}

private fun ModularTeam.withStat(key: ControlKey, value: String): ModularTeam = when (key) {
    ControlKey.PRIMARY -> copy(primary = value)
    ControlKey.SECONDARY -> copy(secondary = value)
    ControlKey.STAT1 -> copy(stats = stats.mapIndexed { i, old -> if (i == 0) value else old })
    ControlKey.STAT2 -> copy(stats = stats.mapIndexed { i, old -> if (i == 1) value else old })
    ControlKey.STAT3 -> copy(stats = stats.mapIndexed { i, old -> if (i == 2) value else old })
    ControlKey.STAT4 -> copy(stats = stats.mapIndexed { i, old -> if (i == 3) value else old })
    else -> this
}

fun ModularScoreboard.withPossession(side: Side) =
    copy(home = home.copy(possession = side == Side.HOME), away = away.copy(possession = side == Side.AWAY))

fun ModularScoreboard.adjusted(template: ModularTemplate, side: Side, key: ControlKey, delta: Int): ModularScoreboard {
    if (template.tennis && key == ControlKey.PRIMARY) return adjustedTennis(side, delta)
    if (key == ControlKey.GAME) return adjustedGame(side, delta)
    if (key != ControlKey.PRIMARY && key != ControlKey.SECONDARY) return this
    val next = maxOf(0, team(side).stat(key).count() + delta).toString()
    val result = withTeam(side, team(side).withStat(key, next))
    return if (key == ControlKey.PRIMARY && delta > 0 && template.changePossessionOnScore) result.withPossession(side) else result
}

private fun ModularScoreboard.adjustedGame(side: Side, delta: Int): ModularScoreboard {
    val set = period.count().let { if (it == 0) 1 else it }
    if (set !in 1..HISTORY_SETS) return this
    val mine = maxOf(0, team(side).history[set - 1].count() + delta).toString()
    val other = team(side.other())
    return withHistory(side, set, mine).withHistory(side.other(), set, other.history[set - 1].ifEmpty { "0" })
}

private fun ModularScoreboard.withHistory(side: Side, set: Int, value: String) =
    withTeam(side, team(side).let { it.copy(history = it.history.mapIndexed { i, old -> if (i == set - 1) value else old }) })

fun ModularScoreboard.withHistoryScore(side: Side, set: Int, value: String): ModularScoreboard {
    val result = withHistory(side, set, value)
    val other = team(side.other()).history[set - 1]
    return if (value.isNotEmpty() && other.isEmpty()) result.withHistory(side.other(), set, "0") else result
}

private fun ModularScoreboard.adjustedTennis(side: Side, delta: Int): ModularScoreboard {
    var mine = team(side).primary
    var theirs = team(side.other()).primary
    if (delta > 0) {
        when (mine) {
            "0" -> mine = "15"
            "15" -> mine = "30"
            "30" -> if (theirs == "40") {
                mine = "D"
                theirs = "D"
            } else {
                mine = "40"
            }
            "40" -> when (theirs) {
                "Ad" -> {
                    mine = "D"
                    theirs = "D"
                }
                "40", "D" -> mine = "Ad"
                else -> return wonGame(side)
            }
            "D" -> if (theirs == "Ad") theirs = "D" else mine = "Ad"
            "Ad" -> return wonGame(side)
        }
    } else {
        mine = when (mine) {
            "Ad" -> "D"
            "D" -> "40"
            "40" -> "30"
            "30" -> "15"
            "15" -> "0"
            else -> mine
        }
    }
    return withTeam(side, team(side).copy(primary = mine)).withTeam(side.other(), team(side.other()).copy(primary = theirs))
}

private fun ModularScoreboard.wonGame(side: Side): ModularScoreboard {
    val server = if (home.possession) Side.AWAY else Side.HOME
    return mapTeams { it.copy(primary = "0") }.withPossession(server).adjustedGame(side, 1)
}

fun ModularScoreboard.cycled(template: ModularTemplate, side: Side, key: ControlKey): ModularScoreboard {
    val options = template.control(key)?.options?.takeIf { it.isNotEmpty() } ?: return this
    val next = options[(options.indexOf(team(side).stat(key)) + 1) % options.size]
    return withTeam(side, team(side).withStat(key, next))
}

fun ModularScoreboard.selected(side: Side, key: ControlKey, value: String) = withTeam(side, team(side).withStat(key, value))

private fun ModularScoreboard.controlReset(control: ModularControl, possessionOff: Boolean): ModularScoreboard {
    if (control.kind == ControlKind.TOGGLE_TEAM) {
        return if (possessionOff) mapTeams { it.copy(possession = false) } else withPossession(Side.HOME)
    }
    val value = control.options.firstOrNull() ?: "0"
    return mapTeams { it.withStat(control.key, value) }
}

fun ModularScoreboard.nextSet(template: ModularTemplate): ModularScoreboard {
    val next = (period.count() + 1).toString()
    if (template.tennis) return copy(period = next).mapTeams { it.copy(primary = "0") }
    var result = this
    val slot = (0 until HISTORY_SETS).firstOrNull { home.history[it].isEmpty() && away.history[it].isEmpty() }
    if (slot != null) {
        result = result.withHistory(Side.HOME, slot + 1, home.primary).withHistory(Side.AWAY, slot + 1, away.primary)
    }
    result = result.copy(period = next)
    for (control in template.controls.filter { it.periodReset }) result = result.controlReset(control, true)
    return if (template.primaryResetOnPeriod) result.mapTeams { it.copy(primary = "0") } else result
}

fun ModularScoreboard.newMatch(template: ModularTemplate, now: Long): ModularScoreboard {
    var result = copy(period = "1", clock = clock.reset(now))
    for (control in template.controls) result = result.controlReset(control, false)
    val secondary = if (home.secondary.isNotEmpty() || template.control(ControlKey.SECONDARY) != null) "0" else ""
    return result.mapTeams { it.copy(primary = "0", secondary = secondary, history = List(HISTORY_SETS) { "" }) }
}

fun ModularScoreboard.maxHistory(): Int =
    (HISTORY_SETS downTo 1).firstOrNull { home.history[it - 1].isNotEmpty() || away.history[it - 1].isNotEmpty() } ?: 0

fun ScoreboardSettings.withSport(sport: ScoreboardSport): ScoreboardSettings {
    if (!sport.isModular) return copy(sport = sport)
    val template = modularTemplate(sport)
    val seconds = parseClock(template.timer) ?: 0
    val clock = if (template.timer.isEmpty()) {
        modular.clock.copy(seconds = 0, startedAt = 0)
    } else {
        ScoreboardClock(
            maximum = (seconds / 60 + if (seconds % 60 > 0) 1 else 0).coerceIn(1, 180),
            direction = if (template.timerDown) ClockDirection.DOWN else ClockDirection.UP,
            seconds = seconds,
        )
    }
    return copy(
        sport = sport,
        modular = modular.copy(
            layout = template.layout,
            period = template.period,
            clock = clock,
            home = modular.home.copy(
                possession = template.home.possession,
                primary = "0",
                secondary = template.home.secondary,
                history = template.home.history,
                stats = template.home.stats,
            ),
            away = modular.away.copy(
                possession = template.away.possession,
                primary = "0",
                secondary = template.away.secondary,
                history = template.away.history,
                stats = template.away.stats,
            ),
        ),
    )
}
