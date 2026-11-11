package com.opirl.app.engine.flv

import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class NalUnitsTest {
    private fun lengthPrefixed(data: ByteArray): ByteArray {
        val out = ByteWriter()
        annexBToLengthPrefixed(data, 0, data.size, out)
        return out.toByteArray()
    }

    @Test
    fun lengthPrefixConversionHandlesMixedStartCodesAndTrailingZeros() {
        val data = concat(
            hex("00000001"), hex("6742"), hex("000001"), hex("68ce"), hex("00"),
            hex("00000001"), hex("65aabb"), hex("0000"),
        )
        assertEquals("00000002" + "6742" + "00000002" + "68ce" + "00000003" + "65aabb", lengthPrefixed(data).toHex())
    }

    @Test
    fun lengthPrefixConversionIgnoresEmptyUnitsAndLeadingGarbage() {
        val data = concat(hex("00"), hex("000001"), hex("000001"), hex("41ff"))
        assertEquals("00000002" + "41ff", lengthPrefixed(data).toHex())
    }

    @Test
    fun startCodeScanFindsCodeAtTheVeryEndOfTheBuffer() {
        val data = hex("aabbcc000001")
        assertEquals(3, findStartCode(data, 0, data.size))
        assertEquals(-1, findStartCode(data, 4, data.size))
    }

    @Test
    fun startCodeScanSkipsBytesThatCannotBeginOne() {
        assertEquals(5, findStartCode(hex("0102030400000001"), 0, 8))
        assertEquals(-1, findStartCode(hex("00000200000300"), 0, 7))
    }

    @Test
    fun leadingNaluTypesStopsAtFirstVcl() {
        val data = annexB(hex("6742"), hex("68ce"), hex("6501"), hex("0605"))
        val seen = leadingNaluTypes(data, 5, ::h264NaluType)
        assertEquals(typeMask(7, 8, 5), seen)
    }

    @Test
    fun unescapeRemovesEmulationPreventionBytes() {
        assertContentEquals(hex("00000001000002"), unescapeRbsp(hex("000003000100000302")))
        assertContentEquals(hex("0000030201"), unescapeRbsp(hex("0000030302" + "01")))
    }

    @Test
    fun escapingRoundTripsRandomPayloadsWithManyZeros() {
        val random = Random(7)
        repeat(200) {
            val data = ByteArray(random.nextInt(300)) { if (random.nextInt(3) == 0) random.nextInt(4).toByte() else 0 }
            val out = ByteWriter()
            out.putEscaped(data, 0, data.size)
            val escaped = out.toByteArray()
            for (i in 0 until escaped.size - 2) {
                val forbidden = escaped[i].toInt() == 0 && escaped[i + 1].toInt() == 0 && (escaped[i + 2].toInt() and 0xFF) <= 2
                assertEquals(false, forbidden)
            }
            assertContentEquals(data, unescapeRbsp(escaped))
        }
    }
}
