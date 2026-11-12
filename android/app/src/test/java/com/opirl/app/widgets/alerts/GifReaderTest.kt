package com.opirl.app.widgets.alerts

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val CLEAR = 4
private const val END = 5
private const val A = 0xFFFF0000.toInt()
private const val B = 0xFF00FF00.toInt()
private const val C = 0xFF0000FF.toInt()
private const val NONE = 0

private class TestFrame(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val pixels: List<Int>,
    val delayCs: Int = 10,
    val disposal: Int = 0,
    val transparent: Int? = null,
    val interlaced: Boolean = false,
)

private fun lzw(pixels: List<Int>): ByteArray {
    val codes = ArrayList<Int>()
    pixels.chunked(2).forEach { pair ->
        codes += CLEAR
        codes += pair
    }
    codes += END
    val out = ByteArrayOutputStream()
    var bits = 0
    var count = 0
    for (code in codes) {
        bits = bits or (code shl count)
        count += 3
        while (count >= 8) {
            out.write(bits and 0xFF)
            bits = bits ushr 8
            count -= 8
        }
    }
    if (count > 0) out.write(bits and 0xFF)
    return out.toByteArray()
}

private fun gif(width: Int, height: Int, frames: List<TestFrame>): ByteArray {
    val out = ByteArrayOutputStream()
    out.write("GIF89a".toByteArray())
    out.write(byteArrayOf(width.toByte(), 0, height.toByte(), 0, 0x81.toByte(), 0, 0))
    for (color in listOf(0x000000, 0xFF0000, 0x00FF00, 0x0000FF)) {
        out.write(byteArrayOf((color shr 16).toByte(), (color shr 8).toByte(), color.toByte()))
    }
    for (frame in frames) {
        val packed = (frame.disposal shl 2) or (if (frame.transparent != null) 1 else 0)
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4, packed.toByte(), frame.delayCs.toByte(), 0, (frame.transparent ?: 0).toByte(), 0))
        out.write(byteArrayOf(0x2C, frame.x.toByte(), 0, frame.y.toByte(), 0, frame.width.toByte(), 0, frame.height.toByte(), 0))
        out.write(if (frame.interlaced) 0x40 else 0)
        out.write(2)
        val data = lzw(frame.pixels)
        out.write(data.size)
        out.write(data)
        out.write(0)
    }
    out.write(0x3B)
    return out.toByteArray()
}

private fun decode(bytes: ByteArray): List<Pair<List<Int>, Int>> {
    val frames = ArrayList<Pair<List<Int>, Int>>()
    GifReader(bytes).forEachFrame(100) { pixels, delay -> frames += pixels.toList() to delay }
    return frames
}

class GifReaderTest {
    @Test
    fun keepsEarlierPixelsWhereALaterFrameIsTransparent() {
        val frames = decode(
            gif(
                2,
                2,
                listOf(
                    TestFrame(0, 0, 2, 2, listOf(1, 2, 3, 1)),
                    TestFrame(0, 0, 2, 2, listOf(0, 3, 0, 2), transparent = 0),
                ),
            ),
        )
        assertEquals(listOf(A, B, C, A), frames[0].first)
        assertEquals(listOf(A, C, C, B), frames[1].first)
    }

    @Test
    fun clearsTheFrameAreaWhenItsDisposalIsRestoreToBackground() {
        val frames = decode(
            gif(
                2,
                2,
                listOf(
                    TestFrame(0, 0, 2, 2, listOf(1, 1, 1, 1), disposal = 2),
                    TestFrame(1, 1, 1, 1, listOf(2)),
                ),
            ),
        )
        assertEquals(listOf(A, A, A, A), frames[0].first)
        assertEquals(listOf(NONE, NONE, NONE, B), frames[1].first)
    }

    @Test
    fun bringsBackTheCanvasWhenItsDisposalIsRestoreToPrevious() {
        val frames = decode(
            gif(
                2,
                2,
                listOf(
                    TestFrame(0, 0, 2, 2, listOf(1, 1, 1, 1)),
                    TestFrame(0, 0, 1, 2, listOf(3, 3), disposal = 3),
                    TestFrame(1, 0, 1, 1, listOf(2)),
                ),
            ),
        )
        assertEquals(listOf(C, A, C, A), frames[1].first)
        assertEquals(listOf(A, B, A, A), frames[2].first)
    }

    @Test
    fun placesInterlacedRowsWhereTheyBelong() {
        assertContentEquals(intArrayOf(0, 4, 2, 1, 3), interlacedRows(5))
        assertContentEquals(intArrayOf(0, 2, 1), interlacedRows(3))
        val frames = decode(gif(1, 5, listOf(TestFrame(0, 0, 1, 5, listOf(1, 2, 3, 1, 2), interlaced = true))))
        assertEquals(listOf(A, A, C, B, B), frames[0].first)
    }

    @Test
    fun readsDelaysTheWayBrowsersDo() {
        val frames = decode(
            gif(
                1,
                1,
                listOf(
                    TestFrame(0, 0, 1, 1, listOf(1), delayCs = 0),
                    TestFrame(0, 0, 1, 1, listOf(1), delayCs = 1),
                    TestFrame(0, 0, 1, 1, listOf(1), delayCs = 2),
                    TestFrame(0, 0, 1, 1, listOf(1), delayCs = 7),
                ),
            ),
        )
        assertEquals(listOf(100, 100, 20, 70), frames.map { it.second })
    }

    @Test
    fun keepsTheFramesBeforeBrokenData() {
        val bytes = gif(1, 1, listOf(TestFrame(0, 0, 1, 1, listOf(1)), TestFrame(0, 0, 1, 1, listOf(2))))
        assertEquals(1, decode(bytes.copyOf(bytes.size - 14)).size)
        assertFalse(GifReader(byteArrayOf(1, 2, 3)).valid)
        assertFalse(GifReader("GIF89a".toByteArray()).valid)
        assertTrue(isGif(bytes))
        assertFalse(isGif("PNG".toByteArray()))
    }

    @Test
    fun stopsAtTheFrameLimit() {
        val bytes = gif(1, 1, List(5) { TestFrame(0, 0, 1, 1, listOf(1)) })
        var seen = 0
        assertEquals(3, GifReader(bytes).forEachFrame(3) { _, _ -> seen++ })
        assertEquals(3, seen)
    }

    @Test
    fun decodesTheBundledImagesLikeAReferenceDecoder() {
        val expected = mapOf(
            "white_star" to Triple(Triple(236, 236, 19), listOf(70), mapOf(0 to 1292152958L, 7 to 87861294L, 18 to 1292152958L)),
            "sunglasses" to Triple(
                Triple(480, 480, 19),
                listOf(50, 1000),
                mapOf(0 to 2055074163L, 1 to 1292260166L, 10 to 3177107510L, 18 to 886717098L),
            ),
            "moblin_pixels" to Triple(Triple(409, 409, 39), listOf(100), mapOf(0 to 4165095776L, 20 to 1076963956L, 38 to 4165095776L)),
            "angry" to Triple(Triple(427, 480, 3), listOf(150), mapOf(0 to 901800508L, 1 to 1323800665L, 2 to 3890905096L)),
        )
        for ((name, spec) in expected) {
            val reader = GifReader(File("src/main/assets/alerts/images/$name.gif").readBytes())
            assertTrue(reader.valid, name)
            assertEquals(spec.first.first, reader.width, name)
            assertEquals(spec.first.second, reader.height, name)
            val checksums = HashMap<Int, Long>()
            val delays = HashSet<Int>()
            var index = 0
            val count = reader.forEachFrame(1000) { pixels, delay ->
                delays += delay
                if (index in spec.third) {
                    val crc = CRC32()
                    for (pixel in pixels) {
                        crc.update(pixel ushr 24)
                        crc.update(pixel ushr 16)
                        crc.update(pixel ushr 8)
                        crc.update(pixel)
                    }
                    checksums[index] = crc.value
                }
                index++
            }
            assertEquals(spec.first.third, count, name)
            assertEquals(spec.third, checksums, name)
            assertTrue(delays.containsAll(spec.second), name)
        }
    }
}
