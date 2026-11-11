package com.opirl.app.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LutCubeTest {
    private fun entry(cube: LutCube, red: Int, green: Int, blue: Int): List<Int> {
        val index = ((blue * cube.size + green) * cube.size + red) * 3
        return (0..2).map { cube.rgb.get(index + it).toInt() and 0xFF }
    }

    @Test
    fun aCubeFileListsRedFastest() {
        val cube = parseCubeLut(
            """
            TITLE "test"
            # comment
            LUT_3D_SIZE 2
            DOMAIN_MIN 0 0 0
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 0.5
            """.trimIndent(),
        )
        assertEquals(2, cube.size)
        assertEquals(listOf(255, 0, 0), entry(cube, 1, 0, 0))
        assertEquals(listOf(0, 255, 0), entry(cube, 0, 1, 0))
        assertEquals(listOf(255, 255, 128), entry(cube, 1, 1, 1))
    }

    @Test
    fun aCubeFileWithTheWrongNumberOfPointsIsRejected() {
        assertFailsWith<IllegalArgumentException> { parseCubeLut("LUT_3D_SIZE 2\n0 0 0\n1 1 1") }
        assertFailsWith<IllegalArgumentException> { parseCubeLut("0 0 0") }
        assertFailsWith<IllegalArgumentException> { parseCubeLut("LUT_1D_SIZE 2\n0 0 0\n1 1 1") }
    }

    @Test
    fun anImageHoldsOneBlueSlicePerTile() {
        val cube = imageLut(8, 8) { x, y ->
            val blue = y / 4 * 2 + x / 4
            (x % 4 * 85 shl 16) or (y % 4 * 85 shl 8) or blue * 85
        }
        assertEquals(4, cube.size)
        assertEquals(listOf(255, 85, 170), entry(cube, 3, 1, 2))
        assertEquals(listOf(0, 255, 255), entry(cube, 0, 3, 3))
    }

    @Test
    fun anImageThatIsNotACubeIsRejected() {
        assertFailsWith<IllegalArgumentException> { imageLut(10, 10) { _, _ -> 0 } }
    }

    @Test
    fun aLargeCubeIsResampledTo64() {
        val size = 65
        val text = buildString {
            append("LUT_3D_SIZE $size\n")
            for (blue in 0 until size) for (green in 0 until size) for (red in 0 until size) {
                append("${red / 64f} ${green / 64f} ${blue / 64f}\n")
            }
        }
        val cube = parseCubeLut(text)
        assertEquals(64, cube.size)
        assertEquals(listOf(255, 0, 255), entry(cube, 63, 0, 63))
        assertEquals(listOf(130, 65, 0), entry(cube, 32, 16, 0))
    }
}
