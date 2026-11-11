package com.opirl.app.effects

import com.opirl.app.settings.Comparison
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EffectMathTest {
    @Test
    fun pixelsGrowWithTheStrengthAndFillThePictureEvenly() {
        assertEquals(20f, pixellateCell(1920, 1080, 0f))
        assertEquals(120f, pixellateCell(1920, 1080, 1f))
        assertEquals(1280f / 38, pixellateCell(720, 1280, 0.3f))
    }

    @Test
    fun tiltIsTheRollBeyondTheDisplayRotation() {
        assertEquals(0f, horizonTilt(0f, 9.81f, 0)!!, 0.001f)
        assertEquals(0f, horizonTilt(9.81f, 0f, 90)!!, 0.001f)
        assertEquals(0f, horizonTilt(-9.81f, 0f, 270)!!, 0.001f)
        assertEquals((PI / 4).toFloat(), horizonTilt(6.94f, 6.94f, 0)!!, 0.001f)
        assertEquals((-PI / 4).toFloat(), horizonTilt(6.94f, 6.94f, 90)!!, 0.001f)
    }

    @Test
    fun aPhoneLyingFlatHasNoTilt() {
        assertNull(horizonTilt(0.5f, -0.8f, 0))
    }

    @Test
    fun aTiltedPictureIsEnlargedUntilItCoversTheFrame() {
        assertEquals(1f, horizonScale(1920f, 1080f, 0f), 0.001f)
        assertEquals(1920f / 1080, horizonScale(1920f, 1080f, (PI / 2).toFloat()), 0.001f)
        assertTrue(horizonScale(1920f, 1080f, 0.1f) > 1.1f)
        assertEquals(horizonScale(1920f, 1080f, 0.2f), horizonScale(1920f, 1080f, -0.2f), 0.001f)
    }

    @Test
    fun theHorizonSettlesOnTheTarget() {
        var angle = 0f
        repeat(60) { angle = horizonStep(angle, 0.3f) }
        assertEquals(0.3f, angle, 0.001f)
    }

    @Test
    fun aGreenHueRangeKeysOnGreen() {
        val key = chromaKey(90, 150)
        assertEquals(listOf(0, 100, 0), listOf(key.red, key.green, key.blue).map { Math.round(it * 100) })
        assertTrue(key.threshold in 0.1f..0.5f)
    }

    @Test
    fun aHueRangeMayWrapAroundRed() {
        val key = chromaKey(330, 30)
        assertEquals(listOf(100, 0, 0), listOf(key.red, key.green, key.blue).map { Math.round(it * 100) })
    }

    @Test
    fun numbersAreComparedAsNumbersAndTextWithoutCase() {
        assertTrue(Comparison.GREATER.evaluate("10", "9"))
        assertTrue(Comparison.LESS.evaluate("abc", "ABD"))
        assertTrue(Comparison.EQUAL.evaluate(" 5.0", "5"))
        assertTrue(Comparison.EQUAL.evaluate("Live", "live"))
        assertTrue(Comparison.NOT_EQUAL.evaluate("12:30", "13:30"))
        assertTrue(Comparison.GREATER_EQUAL.evaluate("3 viewers", "3"))
        assertTrue(Comparison.CONTAINS.evaluate("Hello World", "WORLD"))
        assertFalse(Comparison.LESS_EQUAL.evaluate("2", "1"))
    }
}
