package com.opirl.app.widgets.bingo

import kotlinx.serialization.Serializable

@Serializable
data class BingoSquare(val text: String = "", val checked: Boolean = false)

@Serializable
data class BingoSettings(
    val background: Int = 0xBF000000.toInt(),
    val foreground: Int = 0xFFFFFFFF.toInt(),
    val squares: List<BingoSquare> = emptyList(),
)

fun bingoSide(squares: Int): Int = when {
    squares <= 4 -> 2
    squares <= 9 -> 3
    squares <= 16 -> 4
    else -> 5
}

fun squaresFromText(text: String, old: List<BingoSquare>): List<BingoSquare> =
    text.split('\n').mapIndexed { index, line -> BingoSquare(line.trim(), old.getOrNull(index)?.checked ?: false) }

fun squaresToText(squares: List<BingoSquare>): String = squares.joinToString("\n") { it.text }

fun BingoSettings.toggled(index: Int): BingoSettings =
    copy(squares = squares.mapIndexed { at, square -> if (at == index) square.copy(checked = !square.checked) else square })

fun BingoSettings.unchecked(): BingoSettings = copy(squares = squares.map { it.copy(checked = false) })
