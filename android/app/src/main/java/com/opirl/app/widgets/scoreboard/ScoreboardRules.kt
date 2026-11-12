package com.opirl.app.widgets.scoreboard

private fun Score.plus(side: Side) = if (side == Side.HOME) copy(home = home + 1) else copy(away = away + 1)

private fun Score.minus(side: Side): Score = when {
    side == Side.HOME && home > 0 -> copy(home = home - 1)
    side == Side.AWAY && away > 0 -> copy(away = away - 1)
    else -> this
}

fun GenericScoreboard.increment(side: Side) = copy(score = score.plus(side), changes = changes + side)

fun GenericScoreboard.undo(): GenericScoreboard {
    val side = changes.lastOrNull() ?: return this
    return copy(score = score.minus(side), changes = changes.dropLast(1))
}

fun GenericScoreboard.resetScore() = copy(score = Score(), changes = emptyList())

fun isSetWin(first: Int, second: Int) = first == 7 || (first == 6 && second <= 4)

fun isSetCompleted(score: Score): Boolean {
    val most = maxOf(score.home, score.away)
    val least = minOf(score.home, score.away)
    return (most == 6 && least <= 4) || most == 7
}

const val PADEL_SETS = 5

fun isMatchCompleted(sets: List<Score>) = sets.size >= PADEL_SETS && isSetCompleted(sets.last())

val PadelScoreboard.currentSet: Score get() = sets.lastOrNull() ?: Score()

fun PadelScoreboard.increment(side: Side): PadelScoreboard {
    val all = sets.ifEmpty { listOf(Score()) }
    val last = all.last()
    if (isSetCompleted(last)) return if (isMatchCompleted(all)) this else copy(sets = all + Score())
    return copy(sets = all.dropLast(1) + last.plus(side), changes = changes + side)
}

fun PadelScoreboard.undo(): PadelScoreboard {
    val side = changes.lastOrNull() ?: return this
    var all = sets.ifEmpty { listOf(Score()) }
    if (all.last() == Score() && all.size > 1) all = all.dropLast(1)
    return copy(sets = all.dropLast(1) + all.last().minus(side), changes = changes.dropLast(1))
}

fun PadelScoreboard.resetScore() = copy(sets = listOf(Score()), changes = emptyList())

fun PadelScoreboard.playerName(id: Int) = players.firstOrNull { it.id == id }?.name ?: "Player"

fun PadelScoreboard.names(side: Side): List<String> {
    val picks = if (side == Side.HOME) listOf(home1, home2) else listOf(away1, away2)
    return picks.take(if (type == PadelType.DOUBLES) 2 else 1).map { playerName(it) }
}

fun PadelScoreboard.withPlayer(slot: Int, id: Int) = when (slot) {
    0 -> copy(home1 = id)
    1 -> copy(home2 = id)
    2 -> copy(away1 = id)
    else -> copy(away2 = id)
}

fun PadelScoreboard.withNewPlayer(): PadelScoreboard {
    val id = (players.maxOfOrNull { it.id } ?: 0) + 1
    return copy(players = players + PadelPlayer(id, "Player $id"))
}

fun PadelScoreboard.withoutPlayer(id: Int) = copy(players = players.filterNot { it.id == id })

fun PadelScoreboard.withPlayerName(id: Int, name: String) =
    copy(players = players.map { if (it.id == id) it.copy(name = name) else it })
