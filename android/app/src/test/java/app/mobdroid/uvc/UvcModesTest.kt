package app.mobdroid.uvc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UvcModesTest {
    private fun frame(mjpeg: Boolean, width: Int, height: Int, vararg intervals: Int) =
        intArrayOf(if (mjpeg) 1 else 0, width, height, intervals.size, *intervals)

    private fun continuous(mjpeg: Boolean, width: Int, height: Int, min: Int, max: Int, step: Int) =
        intArrayOf(if (mjpeg) 1 else 0, width, height, 0, min, max, step)

    private fun fps(modes: List<UvcMode>) = modes.map { it.format.fps }

    @Test
    fun discreteIntervalsRoundToWholeFpsButMatchLibuvcByFloor() {
        val modes = UvcModes.parse(frame(false, 640, 480, 333667, 666666))

        assertEquals(listOf(UvcFormat(640, 480, 30, false), UvcFormat(640, 480, 15, false)), modes.map { it.format })
        assertEquals(listOf(29, 15), modes.map { it.fpsMatch })
    }

    @Test
    fun intervalsRoundingToTheSameFpsCollapseIntoTheFirst() {
        val modes = UvcModes.parse(frame(true, 1280, 720, 333333, 333334))

        assertEquals(1, modes.size)
        assertEquals(30, modes.single().fpsMatch)
    }

    @Test
    fun continuousRangeOffersOnlyRatesLibuvcAccepts() {
        val modes = UvcModes.parse(continuous(true, 1920, 1080, 333333, 2000000, 333333))

        assertEquals(listOf(30, 15), fps(modes))
    }

    @Test
    fun continuousRangeWithUnitStepOffersEveryStandardRateInside() {
        val modes = UvcModes.parse(continuous(true, 1920, 1080, 166666, 2000000, 1))

        assertEquals(listOf(60, 50, 30, 25, 24, 20, 15, 10, 5), fps(modes))
    }

    @Test
    fun continuousRangeWithoutStepOnlyOffersTheMinimumInterval() {
        val modes = UvcModes.parse(continuous(false, 640, 480, 333333, 666666, 0))

        assertEquals(listOf(30), fps(modes))
    }

    @Test
    fun oddWidthYuyvIsDroppedButMjpegIsKept() {
        val raw = frame(false, 641, 480, 333333) + frame(true, 641, 480, 333333)

        assertEquals(listOf(UvcFormat(641, 480, 30, true)), UvcModes.parse(raw).map { it.format })
    }

    @Test
    fun modesAreSortedBySizeThenFpsWithMjpegFirst() {
        val raw = frame(false, 640, 480, 333333) +
            frame(true, 1920, 1080, 333333, 166666) +
            frame(false, 1920, 1080, 333333) +
            frame(true, 1280, 720, 333333)

        assertEquals(
            listOf(
                UvcFormat(1920, 1080, 60, true),
                UvcFormat(1920, 1080, 30, true),
                UvcFormat(1920, 1080, 30, false),
                UvcFormat(1280, 720, 30, true),
                UvcFormat(640, 480, 30, false),
            ),
            UvcModes.parse(raw).map { it.format },
        )
    }

    @Test
    fun truncatedDescriptorsAreIgnored() {
        val raw = frame(true, 1920, 1080, 333333) + intArrayOf(1, 1280, 720, 5, 333333)

        assertEquals(listOf(UvcFormat(1920, 1080, 30, true)), UvcModes.parse(raw).map { it.format })
    }

    @Test
    fun closestPrefersMjpegAtTheSameFps() {
        val formats = listOf(UvcFormat(1920, 1080, 30, false), UvcFormat(1920, 1080, 30, true))

        assertEquals(UvcFormat(1920, 1080, 30, true), formats.closest(1920, 1080, 30))
    }

    @Test
    fun closestFallsBackToYuyvWhenMjpegDoesNotOfferTheSize() {
        val formats = listOf(UvcFormat(1920, 1080, 30, true), UvcFormat(640, 480, 30, false))

        assertEquals(UvcFormat(640, 480, 30, false), formats.closest(640, 480, 30))
    }

    @Test
    fun closestKeepsTheExactFpsOverAnMjpegAtAnotherFps() {
        val formats = listOf(UvcFormat(1280, 720, 15, true), UvcFormat(1280, 720, 30, false))

        assertEquals(UvcFormat(1280, 720, 30, false), formats.closest(1280, 720, 30))
    }

    @Test
    fun closestPicksTheNearestFpsAtTheExactSize() {
        val formats = listOf(UvcFormat(1280, 720, 60, true), UvcFormat(1280, 720, 24, true))

        assertEquals(UvcFormat(1280, 720, 24, true), formats.closest(1280, 720, 30))
    }

    @Test
    fun closestIsNullWhenTheSizeIsNotOffered() {
        assertNull(listOf(UvcFormat(1920, 1080, 30, true)).closest(1280, 720, 30))
    }
}
