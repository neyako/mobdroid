package com.opirl.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class QuickButtonGridTest {
    private fun row(count: Int, columns: Int, width: Int) = (0 until count).map { quickButtonX(it, count, columns, width, 50) }

    @Test
    fun fullRowsFillTheBarAndAShortLastRowIsCentred() {
        assertEquals(listOf(0, 50, 100, 0, 50, 100, 50), row(7, 3, 150))
        assertEquals(listOf(0, 50, 0, 50, 0, 50, 25), row(7, 2, 100))
    }

    @Test
    fun aBarWiderThanItsColumnsCentresThem() {
        assertEquals(listOf(11, 61, 111, 36, 86), row(5, 3, 173))
    }
}
