package app.mobdroid.camera

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val flip5Back = Flip5Fixture.cameras.first { it.id == "0" }
private val active = IRect(0, 0, 4000, 3000)

private fun choose(sizes: List<Dim>, width: Int, height: Int, fps: Int = 30, maxFps: (Dim) -> Int = { 30 }) =
    pickSize(sizes, { it.width }, { it.height }, maxFps, width, height, fps)

class CameraMathTest {
    @ParameterizedTest
    @CsvSource(
        "1920, 1080, 1920, 1080",
        "1280, 720, 1280, 720",
        "3840, 2160, 3840, 2160",
        "2560, 1440, 3840, 2160",
        "1080, 1920, 1920, 1080",
        "800, 450, 1280, 720",
        "320, 180, 640, 360",
    )
    fun `size choice on the flip5 back camera`(width: Int, height: Int, expectedWidth: Int, expectedHeight: Int) {
        assertEquals(Dim(expectedWidth, expectedHeight), choose(flip5Back.sizes, width, height))
    }

    @Test
    fun `size choice falls back to the largest matching size when the request is bigger than anything`() {
        val sizes = listOf(Dim(1280, 720), Dim(1920, 1080), Dim(1440, 1080))
        assertEquals(Dim(1920, 1080), choose(sizes, 3840, 2160))
    }

    @Test
    fun `size choice without any matching aspect takes the closest covering size`() {
        val sizes = listOf(Dim(640, 480), Dim(1600, 1200), Dim(1920, 1440))
        assertEquals(Dim(1920, 1440), choose(sizes, 1920, 1080))
    }

    @Test
    fun `size choice respects devices that cap 60 fps below the largest size`() {
        val sizes = listOf(Dim(3840, 2160), Dim(1920, 1080), Dim(1280, 720))
        val maxFps = { size: Dim -> if (size.width > 1920) 30 else 60 }
        assertEquals(Dim(1920, 1080), choose(sizes, 1920, 1080, 60, maxFps))
        assertEquals(Dim(1920, 1080), choose(sizes, 3840, 2160, 60, maxFps))
        assertEquals(Dim(3840, 2160), choose(sizes, 3840, 2160, 30, maxFps))
    }

    @Test
    fun `size choice ignores fps when no size supports it`() {
        assertEquals(Dim(1920, 1080), choose(flip5Back.sizes, 1920, 1080, 60))
    }

    @Test
    fun `size choice of nothing is null`() {
        assertNull(choose(emptyList(), 1920, 1080))
    }

    @ParameterizedTest
    @CsvSource(
        "30, 30, 30, 30",
        "24, 30, 24, 24",
        "25, 30, 24, 24",
        "15, 30, 15, 15",
        "10, 30, 10, 10",
        "60, 30, 30, 30",
    )
    fun `fps range on the flip5 back camera`(fps: Int, sizeMaxFps: Int, lower: Int, upper: Int) {
        assertEquals(Fps(lower, upper), chooseFpsRange(flip5Back.fpsRanges, fps, sizeMaxFps))
    }

    @Test
    fun `fps range prefers a fixed range and otherwise the tightest one`() {
        assertEquals(Fps(60, 60), chooseFpsRange(listOf(Fps(30, 30), Fps(15, 60), Fps(60, 60)), 60, 60))
        assertEquals(Fps(15, 60), chooseFpsRange(listOf(Fps(30, 30), Fps(15, 60)), 60, 60))
        assertEquals(Fps(24, 30), chooseFpsRange(listOf(Fps(7, 30), Fps(15, 30), Fps(24, 30)), 30, 30))
        assertNull(chooseFpsRange(emptyList(), 30, 30))
    }

    @Test
    fun `sixty fps needs both a range and a size that can deliver it`() {
        val sizes = mapOf(Dim(1920, 1080) to 60, Dim(3840, 2160) to 30)
        assertTrue(supportsFps(listOf(Fps(30, 30), Fps(60, 60)), sizes, 60))
        assertEquals(false, supportsFps(listOf(Fps(30, 30)), sizes, 60))
        assertEquals(false, supportsFps(listOf(Fps(60, 60)), mapOf(Dim(1920, 1080) to 30), 60))
    }

    @ParameterizedTest
    @CsvSource(
        "1, 0, 0, 4000, 3000",
        "2, 1000, 750, 3000, 2250",
        "8, 1750, 1312, 2250, 1687",
        "0.5, 0, 0, 4000, 3000",
        "NaN, 0, 0, 4000, 3000",
    )
    fun `centered crop region`(zoom: Float, left: Int, top: Int, right: Int, bottom: Int) {
        assertEquals(IRect(left, top, right, bottom), cropRegion(active, zoom))
    }

    @ParameterizedTest
    @CsvSource(
        "1, 0.5, 0.5, 0.15, 1700, 1275, 2300, 1725",
        "1, 0, 0, 0.15, 0, 0, 600, 450",
        "1, 1, 1, 0.15, 3400, 2550, 4000, 3000",
        "1, 2, -1, 0.15, 3400, 0, 4000, 450",
        "2, 0.5, 0.5, 0.3, 1700, 1275, 2300, 1725",
        "2, 0, 0, 0.3, 700, 525, 1300, 975",
        "0.5, 0, 0, 0.15, 0, 0, 600, 450",
    )
    fun `tap region follows the visible frame`(
        zoom: Float,
        x: Float,
        y: Float,
        fraction: Float,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) {
        assertEquals(IRect(left, top, right, bottom), meteringRect(active, zoom, x, y, fraction))
    }

    @ParameterizedTest
    @CsvSource(
        "1.0, 0.33333334, -12, 12, 3",
        "-1.0, 0.33333334, -12, 12, -3",
        "5.0, 0.33333334, -12, 12, 12",
        "-5.0, 0.1, -20, 20, -20",
        "1.5, 0.1, -20, 20, 15",
        "1.0, 0, 0, 0, 0",
        "NaN, 0.1, -20, 20, 0",
    )
    fun `exposure compensation index`(ev: Float, step: Float, lower: Int, upper: Int, expected: Int) {
        assertEquals(expected, exposureIndex(ev, step, lower, upper))
    }

    @Test
    fun `retry delay backs off and caps`() {
        assertEquals(listOf(250L, 250L, 500L, 1000L, 2000L, 4000L, 5000L, 5000L), (0..7).map { retryDelayMs(it) })
    }

    @ParameterizedTest
    @CsvSource(
        "PREVIEW, 'OFF;VIDEO;PREVIEW', PREVIEW",
        "PREVIEW, 'OFF;VIDEO', VIDEO",
        "PREVIEW, OFF, OFF",
        "VIDEO, 'OFF;VIDEO;PREVIEW', VIDEO",
        "VIDEO, OFF, OFF",
        "OFF, 'OFF;VIDEO;PREVIEW', OFF",
    )
    fun `stabilization falls back to what the camera supports`(
        requested: Stabilization,
        available: String,
        expected: Stabilization,
    ) {
        val modes = available.split(';').map { Stabilization.valueOf(it) }
        assertEquals(expected, effectiveStabilization(requested, modes))
    }
}
