package com.opirl.app.widgets.wheel

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WheelTest {
    private fun options(vararg weights: Int) = weights.mapIndexed { index, weight -> WheelOption(('A' + index).toString(), weight) }

    private fun pointed(options: List<WheelOption>, rotation: Double) = optionAtPointer(options, rotation)?.text

    @Test
    fun `options come from the lines of the text`() {
        assertEquals(listOf("Yes", "No", "", "Later"), optionsFromText("\n Yes \nNo\n\nLater  \n\n").map { it.text })
        assertEquals(listOf(""), optionsFromText("  ").map { it.text })
        assertEquals("Yes\nNo", optionsToText(optionsFromText("Yes\nNo")))
    }

    @Test
    fun `a weight is its share of the total`() {
        val wheel = options(1, 3, 6)
        assertEquals(listOf(10, 30, 60), wheel.map { weightPercent(wheel, it.weight) })
        assertEquals(0, weightPercent(emptyList(), 0))
    }

    @Test
    fun `the pointer on the right reads the sector the wheel has turned under it`() {
        val wheel = options(1, 1, 1, 1)
        assertEquals("A", pointed(wheel, 45.0))
        assertEquals("B", pointed(wheel, 315.0))
        assertEquals("C", pointed(wheel, 225.0))
        assertEquals("D", pointed(wheel, 135.0))
        assertEquals("D", pointed(wheel, -225.0))
        assertEquals("A", pointed(wheel, 45.0 + 3600))
    }

    @Test
    fun `heavier options cover more of the wheel`() {
        val wheel = options(1, 3)
        assertEquals("A", pointed(wheel, 60.0))
        assertEquals("B", pointed(wheel, 0.0))
        assertEquals("B", pointed(wheel, 180.0))
        assertEquals(null, pointed(emptyList(), 0.0))
        assertEquals(null, pointed(options(0, 0), 0.0))
    }

    private val slowest = object : Random() {
        override fun nextBits(bitCount: Int) = 0
    }

    private fun turn(spin: WheelSpin, start: Long, at: Long, onlyWhenSpinning: Boolean = false): Double {
        val before = spin.update(start + at, onlyWhenSpinning).rotation
        val after = spin.update(start + at + 10, onlyWhenSpinning).rotation
        return (after - before + 360) % 360 / 0.01
    }

    @Test
    fun `the wheel slows down evenly and stops after twelve seconds`() {
        val spin = WheelSpin(slowest)
        spin.spin()
        val first = spin.update(1000, false)
        assertEquals(0.0, first.rotation)
        assertFalse(first.finished)
        val speed = Math.toDegrees(8.0)
        for (seconds in listOf(1, 3, 6, 9, 11)) {
            assertEquals(speed * (1 - seconds / 12.0), turn(spin, 1000, seconds * 1000L), speed * 0.01)
        }
        val stopped = spin.update(1000 + 12_000, false).rotation
        assertEquals(stopped, spin.update(1000 + 14_500, false).rotation)
        assertEquals(stopped, spin.update(1000 + 40_000, false).rotation)
    }

    @Test
    fun `the result is due once, when the spin has run its twelve seconds`() {
        val spin = WheelSpin(slowest)
        spin.spin()
        val ticks = (0..20).map { spin.update(it * 1000L, false) }
        assertEquals(listOf(12), ticks.indices.filter { ticks[it].finished })
        assertEquals(listOf(true), ticks.map { it.shown }.distinct())
    }

    @Test
    fun `queued spins start three seconds after the previous one has ended`() {
        val spin = WheelSpin(slowest)
        spin.spin()
        spin.spin()
        val ticks = (0..40).map { spin.update(it * 1000L, false) }
        assertEquals(listOf(12, 27), ticks.indices.filter { ticks[it].finished })
        assertEquals(ticks[12].rotation, ticks[14].rotation)
        assertTrue(ticks[16].rotation != ticks[15].rotation)
    }

    @Test
    fun `a wheel that shows only while spinning appears, waits a moment, spins and goes`() {
        val spin = WheelSpin(slowest)
        assertFalse(spin.update(0, true).shown)
        spin.spin()
        val appears = spin.update(1000, true)
        assertTrue(appears.shown)
        assertEquals(2500, appears.wakeAt)
        assertEquals(appears.rotation, spin.update(2400, true).rotation)
        assertTrue(spin.update(3500, true).rotation != appears.rotation)
        assertTrue(spin.update(1000 + 1500 + 16_999, true).shown)
        val gone = spin.update(1000 + 1500 + 17_000, true)
        assertFalse(gone.shown)
        assertEquals(Long.MAX_VALUE, gone.wakeAt)
    }

    @Test
    fun `it asks to be drawn again only while something moves`() {
        val spin = WheelSpin(slowest)
        assertEquals(Long.MAX_VALUE, spin.update(0, false).wakeAt)
        spin.spin()
        assertEquals(33, spin.update(0, false).wakeAt)
        assertEquals(12_000, spin.update(11_990, false).wakeAt)
        assertEquals(17_000, spin.update(12_000, false).wakeAt)
        assertEquals(Long.MAX_VALUE, spin.update(17_000, false).wakeAt)
    }

    @Test
    fun `results are kept newest first and at most a hundred`() {
        val state = WheelState()
        repeat(105) { state.add(WheelResult("$it", it.toLong())) }
        val results = state.results()
        assertEquals(100, results.size)
        assertEquals("104", results.first().text)
        assertEquals("5", results.last().text)
        state.remove(results[1])
        assertEquals(listOf("104", "102"), state.results().take(2).map { it.text })
    }
}
