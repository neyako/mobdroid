package com.opirl.app.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThermalGuardTest {
    @Test
    fun lowersTheFrameRateAtCriticalAndKeepsItLowWhileBusy() {
        val guard = ThermalGuard()
        assertFalse(guard.update(3, busy = true))
        assertEquals(60, guard.fps(60))
        assertTrue(guard.update(4, busy = true))
        assertEquals(30, guard.fps(60))
        assertEquals(25, guard.fps(25))
        assertFalse(guard.update(1, busy = true))
        assertEquals(30, guard.fps(60))
        assertFalse(guard.update(3, busy = false))
        assertTrue(guard.update(2, busy = false))
        assertEquals(60, guard.fps(60))
    }
}
