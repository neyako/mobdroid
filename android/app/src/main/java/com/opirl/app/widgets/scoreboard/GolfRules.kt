package com.opirl.app.widgets.scoreboard

const val GOLF_MAX_PLAYERS = 4
const val GOLF_MAX_STROKES = 10
const val GOLF_NO_SCORE = -1

fun formatRelative(score: Int) = when {
    score == 0 -> "E"
    score > 0 -> "+$score"
    else -> "$score"
}

fun GolfPlayer.totalRelativeToPar(pars: List<Int>, holes: Int): Int =
    (0 until minOf(holes, pars.size, scores.size)).sumOf { if (scores[it] != GOLF_NO_SCORE) scores[it] - pars[it] else 0 }

fun GolfPlayer.totalStrokes(holes: Int): Int = scores.take(holes).filter { it >= 0 }.sum()

fun GolfPlayer.holesPlayed(holes: Int): Int = scores.take(holes).count { it != GOLF_NO_SCORE }

fun scoreCellColor(strokes: Int, par: Int): Int? {
    if (strokes < 0) return null
    return when (strokes - par) {
        in Int.MIN_VALUE..-2 -> 0xFF315C95.toInt()
        -1 -> 0xFF1D7942.toInt()
        0 -> 0xFF666666.toInt()
        1 -> 0xFFA21010.toInt()
        2 -> 0xFF6B1CA9.toInt()
        else -> 0xFF683915.toInt()
    }
}

fun GolfScoreboard.parAt(hole: Int) = pars.getOrElse(hole) { 4 }

val GolfScoreboard.hole: Int get() = currentHole.coerceIn(0, numberOfHoles - 1)

fun GolfScoreboard.withPars(pars: List<Int>): GolfScoreboard =
    copy(pars = pars + DEFAULT_PARS.drop(pars.size))

fun GolfScoreboard.withPar(hole: Int, par: Int) =
    withPars(pars.mapIndexed { index, value -> if (index == hole) par.coerceIn(1, 9) else value })

fun GolfScoreboard.withHoles(holes: Int) = copy(numberOfHoles = holes, currentHole = 0)

fun GolfScoreboard.withStrokes(player: Int, hole: Int, strokes: Int): GolfScoreboard = copy(
    players = players.mapIndexed { index, value ->
        if (index != player) {
            value
        } else {
            val scores = value.scores + List((hole + 1 - value.scores.size).coerceAtLeast(0)) { GOLF_NO_SCORE }
            value.copy(scores = scores.mapIndexed { at, old -> if (at == hole) strokes else old })
        }
    },
)

fun GolfScoreboard.adjusted(player: Int, hole: Int, delta: Int): GolfScoreboard {
    val current = players.getOrNull(player)?.scores?.getOrNull(hole)?.takeIf { it >= 0 } ?: parAt(hole)
    return withStrokes(player, hole, (current + delta).coerceIn(1, GOLF_MAX_STROKES))
}

fun GolfScoreboard.newRound() = copy(
    players = players.map { it.copy(scores = List(GOLF_HOLES) { GOLF_NO_SCORE }) },
    currentHole = 0,
)

fun GolfScoreboard.withNewPlayer() =
    if (players.size >= GOLF_MAX_PLAYERS) this else copy(players = players + GolfPlayer("Player ${players.size + 1}"))

fun GolfScoreboard.withoutPlayer(index: Int) = copy(players = players.filterIndexed { at, _ -> at != index })

fun GolfScoreboard.withPlayer(index: Int, change: (GolfPlayer) -> GolfPlayer) =
    copy(players = players.mapIndexed { at, value -> if (at == index) change(value) else value })
