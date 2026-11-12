package com.opirl.app.widgets.scoreboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModularRulesTest {
    private val now = 1_000_000L

    private fun board(sport: ScoreboardSport) = ScoreboardSettings().withSport(sport).modular

    private fun ModularScoreboard.points(side: Side, template: ModularTemplate, times: Int, delta: Int = 1) =
        (1..times).fold(this) { current, _ -> current.adjusted(template, side, ControlKey.PRIMARY, delta) }

    private val tennis = modularTemplate(ScoreboardSport.TENNIS)

    private fun ModularScoreboard.score() = home.primary to away.primary

    @Test
    fun aTennisGameGoesThroughFifteenThirtyFortyAndIsWon() {
        var game = board(ScoreboardSport.TENNIS)
        game = game.points(Side.HOME, tennis, 3)
        assertEquals("40" to "0", game.score())
        game = game.points(Side.HOME, tennis, 1)
        assertEquals("0" to "0", game.score())
        assertEquals("1", game.home.history[0])
        assertEquals("0", game.away.history[0])
    }

    @Test
    fun deuceAndAdvantageFollowTheTennisRules() {
        var game = board(ScoreboardSport.TENNIS).points(Side.AWAY, tennis, 3).points(Side.HOME, tennis, 3)
        assertEquals("D" to "D", game.score())
        game = game.points(Side.HOME, tennis, 1)
        assertEquals("Ad" to "D", game.score())
        game = game.points(Side.AWAY, tennis, 1)
        assertEquals("D" to "D", game.score())
        game = game.points(Side.AWAY, tennis, 1)
        assertEquals("D" to "Ad", game.score())
        game = game.points(Side.HOME, tennis, 1)
        assertEquals("D" to "D", game.score())
        game = game.points(Side.AWAY, tennis, 2)
        assertEquals("0" to "0", game.score())
        assertEquals("1", game.away.history[0])
    }

    @Test
    fun aWonTennisGamePassesTheServeAndCountsInTheCurrentSet() {
        val first = board(ScoreboardSport.TENNIS)
        assertTrue(first.home.possession)
        val won = first.points(Side.AWAY, tennis, 4)
        assertTrue(won.away.possession)
        assertFalse(won.home.possession)
        assertEquals("1", won.away.history[0])
        val second = won.points(Side.AWAY, tennis, 4)
        assertEquals("2", second.away.history[0])
        assertTrue(second.home.possession)
    }

    @Test
    fun takingATennisPointBackStepsDownTheScale() {
        val game = board(ScoreboardSport.TENNIS).points(Side.HOME, tennis, 2).points(Side.HOME, tennis, 1, -1)
        assertEquals("15" to "0", game.score())
        assertEquals("0" to "0", game.points(Side.HOME, tennis, 5, -1).score())
    }

    @Test
    fun theNextTennisSetStartsAtLoveWithoutTouchingTheGames() {
        var game = board(ScoreboardSport.TENNIS).points(Side.HOME, tennis, 4).points(Side.HOME, tennis, 2)
        game = game.nextSet(tennis)
        assertEquals("2", game.period)
        assertEquals("0" to "0", game.score())
        assertEquals("1", game.home.history[0])
        val set2 = game.adjusted(tennis, Side.AWAY, ControlKey.GAME, 1)
        assertEquals("1", set2.away.history[1])
        assertEquals("0", set2.home.history[1])
    }

    private val volleyball = modularTemplate(ScoreboardSport.VOLLEYBALL)

    @Test
    fun pointsDoNotGoBelowZeroAndAPointPassesTheServe() {
        var game = board(ScoreboardSport.VOLLEYBALL)
        game = game.points(Side.AWAY, volleyball, 1)
        assertEquals("0" to "1", game.score())
        assertTrue(game.away.possession)
        assertEquals("0" to "0", game.points(Side.AWAY, volleyball, 3, -1).score())
    }

    @Test
    fun theNextVolleyballSetSavesThePointsAndResetsPointsAndTimeouts() {
        var game = board(ScoreboardSport.VOLLEYBALL)
        game = game.points(Side.HOME, volleyball, 25).points(Side.AWAY, volleyball, 20)
        game = game.selected(Side.HOME, ControlKey.STAT1, "2").adjusted(volleyball, Side.HOME, ControlKey.SECONDARY, 1)
        val next = game.nextSet(volleyball)
        assertEquals("2", next.period)
        assertEquals("25", next.home.history[0])
        assertEquals("20", next.away.history[0])
        assertEquals("0" to "0", next.score())
        assertEquals("0", next.home.stats[0])
        assertEquals("1", next.home.secondary)
        assertEquals(1, next.maxHistory())
    }

    private val basketball = modularTemplate(ScoreboardSport.BASKETBALL)

    @Test
    fun basketballKeepsItsPointsBetweenQuartersAndResetsFoulsAndBonus() {
        var game = board(ScoreboardSport.BASKETBALL)
        game = game.points(Side.HOME, basketball, 12)
        repeat(3) { game = game.cycled(basketball, Side.HOME, ControlKey.STAT2) }
        game = game.cycled(basketball, Side.AWAY, ControlKey.STAT3)
        assertEquals("3", game.home.stats[1])
        assertEquals("BONUS", game.away.stats[2])
        val next = game.nextSet(basketball)
        assertEquals("12", next.home.primary)
        assertEquals("0", next.home.stats[1])
        assertEquals("NO BONUS", next.away.stats[2])
        assertEquals("12", next.home.history[0])
    }

    @Test
    fun aCycleWrapsAroundItsOptions() {
        val hockey = modularTemplate(ScoreboardSport.HOCKEY)
        var game = board(ScoreboardSport.HOCKEY)
        game = game.cycled(hockey, Side.HOME, ControlKey.STAT1)
        assertEquals("PP", game.home.stats[0])
        assertEquals("NO PP", game.cycled(hockey, Side.HOME, ControlKey.STAT1).home.stats[0])
    }

    @Test
    fun aNewMatchClearsScoresHistoryAndTheClockButKeepsTheNames() {
        val renamed = board(ScoreboardSport.VOLLEYBALL).let { it.copy(home = it.home.copy(name = "Ana")) }
        var game = renamed.points(Side.HOME, volleyball, 5).nextSet(volleyball).adjusted(volleyball, Side.AWAY, ControlKey.SECONDARY, 1)
        game = game.copy(clock = ScoreboardClock(maximum = 20, seconds = 300, startedAt = now))
        val fresh = game.newMatch(volleyball, now)
        assertEquals("1", fresh.period)
        assertEquals("0" to "0", fresh.score())
        assertEquals("0", fresh.away.secondary)
        assertEquals(0, fresh.maxHistory())
        assertEquals("0:00", fresh.clock.format(now))
        assertTrue(fresh.clock.running)
        assertEquals("Ana", fresh.home.name)
        assertTrue(fresh.home.possession)
    }

    @Test
    fun aHistoricScoreFillsInTheOtherTeamWithZero() {
        val game = board(ScoreboardSport.TENNIS).withHistoryScore(Side.HOME, 2, "6")
        assertEquals("6", game.home.history[1])
        assertEquals("0", game.away.history[1])
        assertEquals("", game.withHistoryScore(Side.HOME, 2, "").home.history[1])
        assertEquals(2, game.maxHistory())
    }

    @Test
    fun choosingASportLoadsItsLayoutPeriodAndClock() {
        val settings = ScoreboardSettings().withSport(ScoreboardSport.BASKETBALL)
        assertEquals(ScoreboardLayout.SIDE_BY_SIDE, settings.modular.layout)
        assertEquals("10:00", settings.modular.clock.format(now))
        assertEquals(ClockDirection.DOWN, settings.modular.clock.direction)
        assertEquals(10, settings.modular.clock.maximum)
        assertEquals("5", settings.modular.home.stats[0])
        assertEquals(ScoreboardLayout.STACK_HISTORY, settings.withSport(ScoreboardSport.TENNIS).modular.layout)
        assertEquals(ScoreboardSport.PADEL, settings.withSport(ScoreboardSport.PADEL).sport)
    }

    @Test
    fun theInfoBoxListsTheClockThePeriodAndTheText() {
        val settings = ScoreboardSettings().withSport(ScoreboardSport.HOCKEY)
        val template = modularTemplate(ScoreboardSport.HOCKEY)
        val modular = settings.modular.copy(infoBoxText = "Final")
        assertEquals(listOf("15:00", "PER 1", "Final"), modular.infoLines(template, ScoreboardSport.HOCKEY, now))
        assertEquals(listOf("PER 1", "Final"), modular.copy(showClock = false).infoLines(template, ScoreboardSport.HOCKEY, now))
        val football = modularTemplate(ScoreboardSport.FOOTBALL)
        val shown = settings.withSport(ScoreboardSport.FOOTBALL).modular.infoLines(football, ScoreboardSport.FOOTBALL, now)
        assertEquals(listOf("30:00"), shown)
    }
}
