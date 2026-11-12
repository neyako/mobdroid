package com.opirl.app.widgets.scoreboard

import kotlinx.serialization.Serializable

@Serializable
enum class ScoreboardSport {
    GENERIC,
    PADEL,
    GOLF,
    GOLF_FULL_SCORECARD,
    BASKETBALL,
    GENERIC_2,
    GENERIC_SETS,
    HOCKEY,
    FOOTBALL,
    TENNIS,
    VOLLEYBALL,
}

val ScoreboardSport.isModular: Boolean
    get() = this != ScoreboardSport.GENERIC && this != ScoreboardSport.PADEL &&
        this != ScoreboardSport.GOLF && this != ScoreboardSport.GOLF_FULL_SCORECARD

@Serializable
enum class Side { HOME, AWAY }

@Serializable
enum class ClockDirection { UP, DOWN }

@Serializable
enum class ScoreboardLayout { STACKED, STACKED_INLINE, SIDE_BY_SIDE, STACK_HISTORY }

@Serializable
enum class PadelType { DOUBLES, SINGLES }

@Serializable
data class Score(val home: Int = 0, val away: Int = 0)

@Serializable
data class ScoreboardClock(
    val maximum: Int = 45,
    val direction: ClockDirection = ClockDirection.UP,
    val seconds: Int = 0,
    val startedAt: Long = 0,
)

@Serializable
data class GenericScoreboard(
    val home: String = "Home",
    val away: String = "Away",
    val title: String = "⚽️",
    val clock: ScoreboardClock = ScoreboardClock(),
    val score: Score = Score(),
    val changes: List<Side> = emptyList(),
)

@Serializable
data class PadelPlayer(val id: Int = 0, val name: String = "Player")

@Serializable
data class PadelScoreboard(
    val type: PadelType = PadelType.DOUBLES,
    val players: List<PadelPlayer> = (1..4).map { PadelPlayer(it, "Player $it") },
    val home1: Int = 1,
    val home2: Int = 2,
    val away1: Int = 3,
    val away2: Int = 4,
    val sets: List<Score> = listOf(Score()),
    val changes: List<Side> = emptyList(),
)

@Serializable
data class GolfPlayer(
    val name: String = "Player",
    val scores: List<Int> = List(GOLF_HOLES) { -1 },
    val color: Int = WHITE,
)

@Serializable
data class GolfScoreboard(
    val title: String = "⛳ Golf",
    val numberOfHoles: Int = 18,
    val currentHole: Int = 0,
    val pars: List<Int> = DEFAULT_PARS,
    val players: List<GolfPlayer> = listOf(GolfPlayer("Player 1"), GolfPlayer("Player 2")),
    val playerColors: Boolean = false,
)

@Serializable
data class ModularTeam(
    val name: String = "",
    val textColor: Int = WHITE,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val possession: Boolean = false,
    val primary: String = "0",
    val secondary: String = "",
    val history: List<String> = List(HISTORY_SETS) { "" },
    val stats: List<String> = List(MODULAR_STATS) { "" },
)

@Serializable
data class ModularScoreboard(
    val home: ModularTeam = ModularTeam("Home", WHITE, 0xFF0B10AC.toInt()),
    val away: ModularTeam = ModularTeam("Away", WHITE, 0xFFDC2626.toInt()),
    val title: String = "⚽️",
    val period: String = "1",
    val infoBoxText: String = "",
    val clock: ScoreboardClock = ScoreboardClock(),
    val layout: ScoreboardLayout = ScoreboardLayout.STACKED,
    val width: Int = 350,
    val rowHeight: Int = 45,
    val bold: Boolean = true,
    val showTitle: Boolean = false,
    val showMoreStats: Boolean = false,
    val showInfoBox: Boolean = false,
    val showClock: Boolean = true,
)

@Serializable
data class ScoreboardSettings(
    val sport: ScoreboardSport = ScoreboardSport.GENERIC,
    val textColor: Int = WHITE,
    val primaryColor: Int = 0xFF0B10AC.toInt(),
    val secondaryColor: Int = 0xFF00035B.toInt(),
    val showPars: Boolean = true,
    val generic: GenericScoreboard = GenericScoreboard(),
    val padel: PadelScoreboard = PadelScoreboard(),
    val golf: GolfScoreboard = GolfScoreboard(),
    val modular: ModularScoreboard = ModularScoreboard(),
)

const val WHITE = -1
const val GOLF_HOLES = 18
const val HISTORY_SETS = 5
const val MODULAR_STATS = 4
val DEFAULT_PARS = listOf(4, 4, 3, 4, 5, 4, 3, 4, 4, 4, 4, 3, 5, 4, 4, 3, 4, 5)
