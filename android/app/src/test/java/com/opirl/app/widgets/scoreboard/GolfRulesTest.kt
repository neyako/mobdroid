package com.opirl.app.widgets.scoreboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GolfRulesTest {
    private val pars = DEFAULT_PARS

    private fun player(vararg scores: Int) = GolfPlayer("P", scores.toList() + List(GOLF_HOLES - scores.size) { GOLF_NO_SCORE })

    @Test
    fun relativeToParCountsOnlyPlayedHoles() {
        val played = player(4, 5, 2, -1, 6)
        assertEquals(1, played.totalRelativeToPar(pars, 18))
        assertEquals(0, played.totalRelativeToPar(pars, 3))
        assertEquals(0, player().totalRelativeToPar(pars, 18))
    }

    @Test
    fun strokesAndHolesPlayedStopAtTheNumberOfHoles() {
        val played = player(4, 5, 2, -1, 6)
        assertEquals(17, played.totalStrokes(18))
        assertEquals(11, played.totalStrokes(3))
        assertEquals(4, played.holesPlayed(18))
        assertEquals(3, played.holesPlayed(3))
    }

    @Test
    fun scoresAreFormattedRelativeToPar() {
        assertEquals("E", formatRelative(0))
        assertEquals("+3", formatRelative(3))
        assertEquals("-2", formatRelative(-2))
    }

    @Test
    fun cellColoursFollowTheDifferenceToPar() {
        assertNull(scoreCellColor(-1, 4))
        assertEquals(0xFF315C95.toInt(), scoreCellColor(2, 4))
        assertEquals(0xFF315C95.toInt(), scoreCellColor(1, 4))
        assertEquals(0xFF1D7942.toInt(), scoreCellColor(3, 4))
        assertEquals(0xFF666666.toInt(), scoreCellColor(4, 4))
        assertEquals(0xFFA21010.toInt(), scoreCellColor(5, 4))
        assertEquals(0xFF6B1CA9.toInt(), scoreCellColor(6, 4))
        assertEquals(0xFF683915.toInt(), scoreCellColor(8, 4))
    }

    @Test
    fun anAdjustmentStartsFromParAndStaysBetweenOneAndTen() {
        val round = GolfScoreboard()
        assertEquals(5, round.adjusted(0, 0, 1).players[0].scores[0])
        assertEquals(2, round.adjusted(1, 2, -1).players[1].scores[2])
        assertEquals(1, round.withStrokes(0, 0, 1).adjusted(0, 0, -1).players[0].scores[0])
        assertEquals(10, round.withStrokes(0, 0, 10).adjusted(0, 0, 1).players[0].scores[0])
    }

    @Test
    fun aNewRoundClearsEveryScoreAndGoesBackToTheFirstHole() {
        val round = GolfScoreboard(currentHole = 7).withStrokes(0, 7, 5).withStrokes(1, 0, 3).newRound()
        assertEquals(0, round.currentHole)
        assertEquals(true, round.players.all { player -> player.scores.all { it == GOLF_NO_SCORE } })
    }

    @Test
    fun parsAreKeptWithinOneToNineAndPaddedToEighteenHoles() {
        val round = GolfScoreboard().withPars(listOf(3, 3)).withPar(0, 12)
        assertEquals(18, round.pars.size)
        assertEquals(9, round.pars[0])
        assertEquals(DEFAULT_PARS[2], round.pars[2])
        assertEquals(0, GolfScoreboard(currentHole = 12).withHoles(9).currentHole)
    }

    @Test
    fun aGolfRoundHoldsAtMostFourPlayers() {
        val round = (1..5).fold(GolfScoreboard()) { board, _ -> board.withNewPlayer() }
        assertEquals(GOLF_MAX_PLAYERS, round.players.size)
        assertEquals(GOLF_MAX_PLAYERS - 1, round.withoutPlayer(0).players.size)
    }
}
