package com.opirl.app.engine.srt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun processNak(maximumRange: Int, vararg entries: Int): List<Int> {
    val packet = nakPacket(*entries)
    val result = ArrayList<Int>()
    processSrtNak(packet, packet.size, maximumRange) { result.add(it) }
    return result
}

class SrtPacketsTest {
    @Test
    fun nakSingleAndRange() {
        assertEquals(listOf(7, 10, 11, 12, 20), processNak(100_000, 7, 10 or Int.MIN_VALUE, 12, 20))
    }

    @Test
    fun nakTooBigRangeIsIgnored() {
        assertEquals(listOf(5), processNak(100_000, Int.MIN_VALUE, 0x7fffffff, 5))
    }

    @Test
    fun nakReversedRangeIsIgnored() {
        assertEquals(listOf(5), processNak(100_000, 10 or Int.MIN_VALUE, 3, 5))
    }

    @Test
    fun nakTruncatedRange() {
        assertEquals(listOf(1), processNak(100_000, 1, 10 or Int.MIN_VALUE))
    }

    @Test
    fun nakRangeAcrossSequenceWrap() {
        assertEquals(
            listOf(0x7ffffffe, 0x7fffffff, 0, 1),
            processNak(100_000, 0x7ffffffe or Int.MIN_VALUE, 1),
        )
    }

    @Test
    fun nakRangeLargerThanFlowWindowIsIgnored() {
        assertEquals(listOf(9), processNak(SRT_MAX_FLOW_WINDOW_PACKETS, 0 or Int.MIN_VALUE, 8192, 9))
        assertEquals(8192, processNak(SRT_MAX_FLOW_WINDOW_PACKETS, 0 or Int.MIN_VALUE, 8191).size)
    }

    @Test
    fun sequenceAckComparisonSurvivesWrap() {
        assertTrue(isSrtSnAcked(5, 6))
        assertFalse(isSrtSnAcked(6, 6))
        assertFalse(isSrtSnAcked(7, 6))
        assertTrue(isSrtSnAcked(0x7ffffff0, 3))
        assertFalse(isSrtSnAcked(3, 0x7ffffff0))
    }

    @Test
    fun sequenceOrderingUsesCircularComparison() {
        assertEquals(-1, compareSrtSn(0x7fffffff, 0))
        assertEquals(1, compareSrtSn(0, 0x7fffffff))
        assertEquals(0, compareSrtSn(42, 42))
        assertEquals(0x00000000, nextSrtSn(0x7fffffff))
    }

    @Test
    fun bigEndianAccessors() {
        val bytes = ByteArray(12)
        bytes.putInt(0, 0x01020304)
        bytes.putShort(4, 0xA0B0)
        bytes.putLong(4, 0x1122334455667788L)
        assertEquals("010203041122334455667788", hex(bytes))
        assertEquals(0x01020304, bytes.getInt(0))
        assertEquals(0x1122, bytes.getShort(4))
        assertEquals(0x1122334455667788L, bytes.getLong(4))
        assertEquals(0xFFFF, byteArrayOf(-1, -1).getShort(0))
    }
}
