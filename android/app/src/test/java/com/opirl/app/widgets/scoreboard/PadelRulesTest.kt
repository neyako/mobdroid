package com.opirl.app.widgets.scoreboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PadelRulesTest {
    private fun games(vararg sides: Side) = sides.fold(PadelScoreboard()) { board, side -> board.increment(side) }

    private fun sets(board: PadelScoreboard) = board.sets.map { it.home to it.away }

    @Test
    fun aSetIsCompleteAtSixGamesWithTwoClearOrAtSeven() {
        assertTrue(isSetCompleted(Score(6, 4)))
        assertTrue(isSetCompleted(Score(0, 6)))
        assertTrue(isSetCompleted(Score(7, 5)))
        assertTrue(isSetCompleted(Score(6, 7)))
        assertFalse(isSetCompleted(Score(6, 5)))
        assertFalse(isSetCompleted(Score(6, 6)))
        assertFalse(isSetCompleted(Score(5, 4)))
    }

    @Test
    fun theTieBreakSetIsWonAtSevenSix() {
        val board = games(*Array(5) { Side.HOME }, *Array(5) { Side.AWAY }, Side.HOME, Side.AWAY)
        assertEquals(listOf(6 to 6), sets(board))
        assertFalse(isSetCompleted(board.currentSet))
        val won = board.increment(Side.HOME)
        assertEquals(listOf(7 to 6), sets(won))
        assertTrue(isSetWin(7, 6))
        assertFalse(isSetWin(6, 5))
    }

    @Test
    fun aGameAfterACompletedSetOnlyStartsTheNextSet() {
        val board = games(*Array(6) { Side.HOME })
        assertEquals(listOf(6 to 0), sets(board))
        val next = board.increment(Side.AWAY)
        assertEquals(listOf(6 to 0, 0 to 0), sets(next))
        assertEquals(listOf(6 to 0, 0 to 1), sets(next.increment(Side.AWAY)))
    }

    @Test
    fun thereAreNoMoreSetsOnceTheFifthIsComplete() {
        var board = PadelScoreboard()
        repeat(PADEL_SETS) { repeat(6) { board = board.increment(Side.HOME) }; board = board.increment(Side.HOME) }
        assertEquals(PADEL_SETS, board.sets.size)
        assertTrue(isMatchCompleted(board.sets))
        assertEquals(board, board.increment(Side.AWAY))
    }

    @Test
    fun undoTakesBackTheLastGameAndStepsBackOverAnEmptyNewSet() {
        val board = games(*Array(6) { Side.HOME }, Side.AWAY, Side.AWAY)
        assertEquals(listOf(6 to 0, 0 to 1), sets(board))
        assertEquals(listOf(6 to 0, 0 to 0), sets(board.undo()))
        assertEquals(listOf(5 to 0), sets(board.undo().undo()))
        val started = games(*Array(6) { Side.HOME }).increment(Side.AWAY)
        assertEquals(listOf(6 to 0, 0 to 0), sets(started))
        assertEquals(listOf(5 to 0), sets(started.undo()))
    }

    @Test
    fun undoWithoutHistoryAndResetDoNothingOrClear() {
        assertEquals(PadelScoreboard(), PadelScoreboard().undo())
        assertEquals(PadelScoreboard(), games(Side.HOME, Side.AWAY).resetScore())
    }

    @Test
    fun singlesShowOnePlayerPerTeamAndAMissingPlayerHasAFallbackName() {
        val board = PadelScoreboard(type = PadelType.SINGLES).withPlayerName(1, "Ana").withPlayerName(3, "Bo")
        assertEquals(listOf("Ana"), board.names(Side.HOME))
        assertEquals(listOf("Bo"), board.names(Side.AWAY))
        val doubles = board.copy(type = PadelType.DOUBLES).withoutPlayer(2)
        assertEquals(listOf("Ana", "Player"), doubles.names(Side.HOME))
        assertEquals(5, doubles.withNewPlayer().players.maxOf { it.id })
    }
}
