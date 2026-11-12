package com.opirl.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplayTest {
    @Test
    fun theStartCountsBackFromTheEndAndLeavesASecondToPlay() {
        assertEquals(8_000, replayStartMs(20_000, 12_000))
        assertEquals(0, replayStartMs(20_000, 30_000))
        assertEquals(19_000, replayStartMs(20_000, 0))
        assertEquals(0, replayStartMs(600, 10_000))
    }

    @Test
    fun speedsReadAsMultipliers() {
        assertEquals("1x", formatSpeed(100))
        assertEquals("0.5x", formatSpeed(50))
    }
}
