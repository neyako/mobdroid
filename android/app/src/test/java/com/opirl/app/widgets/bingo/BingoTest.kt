package com.opirl.app.widgets.bingo

import kotlin.test.Test
import kotlin.test.assertEquals

class BingoTest {
    @Test
    fun `the card grows with the number of squares`() {
        assertEquals(
            listOf(2, 2, 2, 3, 3, 4, 4, 5, 5, 5),
            listOf(0, 1, 4, 5, 9, 10, 16, 17, 25, 30).map { bingoSide(it) },
        )
    }

    @Test
    fun `squares follow the lines of the text and keep their marks by line`() {
        val marked = listOf(BingoSquare("a", true), BingoSquare("b", false), BingoSquare("c", true))
        assertEquals(
            listOf(BingoSquare("a", true), BingoSquare("new", false), BingoSquare("", true), BingoSquare("d", false)),
            squaresFromText("a\n new \n\nd", marked),
        )
        assertEquals(listOf(BingoSquare("x", true)), squaresFromText("x", marked))
        assertEquals(listOf(BingoSquare("", false)), squaresFromText("", emptyList()))
        assertEquals("a\nb\nc", squaresToText(marked))
    }

    @Test
    fun `a square is marked and unmarked one at a time and all can be cleared`() {
        val card = BingoSettings(squares = listOf(BingoSquare("a"), BingoSquare("b", true)))
        assertEquals(listOf(true, true), card.toggled(0).squares.map { it.checked })
        assertEquals(listOf(false, false), card.toggled(1).squares.map { it.checked })
        assertEquals(card, card.toggled(7))
        assertEquals(listOf(false, false), card.toggled(0).unchecked().squares.map { it.checked })
    }
}
