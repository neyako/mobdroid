package com.opirl.app.engine.rist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RistBufferTest {
    private val ms = 1_000_000L

    @Test
    fun packetsLeaveTheBufferByAgeAndByCount() {
        val buffer = RistRetransmitBuffer(100 * ms, maximumPackets = 4)
        for (index in 0 until 6) buffer.next(index * ms)
        assertEquals(4, buffer.size)
        assertNull(buffer.find(1, 6 * ms))
        assertNotNull(buffer.find(2, 6 * ms))
        assertNull(buffer.find(5, 200 * ms))
        buffer.next(200 * ms)
        assertEquals(1, buffer.size)
    }

    @Test
    fun aPacketIsRequestedOncePerSpacingAndOnlySoOften() {
        val buffer = RistRetransmitBuffer(1000 * ms)
        val slot = buffer.next(0)
        assertEquals(RistRequestResult.Missing, buffer.request(9, 0, 10 * ms, 2))
        assertEquals(RistRequestResult.Accepted, buffer.request(0, 0, 10 * ms, 2))
        assertEquals(RistRequestResult.Duplicate, buffer.request(0, 20 * ms, 10 * ms, 2))
        slot.queued = false
        slot.retransmits = 1
        assertEquals(RistRequestResult.Duplicate, buffer.request(0, 5 * ms, 10 * ms, 2))
        assertEquals(RistRequestResult.Accepted, buffer.request(0, 20 * ms, 10 * ms, 2))
        slot.queued = false
        slot.retransmits = 2
        assertEquals(RistRequestResult.Exhausted, buffer.request(0, 40 * ms, 10 * ms, 2))
    }

    @Test
    fun sequencesWrapAtSixteenBits() {
        val buffer = RistRetransmitBuffer(1000 * ms, maximumPackets = 8)
        var last = 0
        for (index in 0 until 65540) last = buffer.next(0).sequence
        assertEquals(3, last)
        assertNotNull(buffer.find(65535, 0))
    }

    @Test
    fun packetsAreSharedOutByWeight() {
        val selector = RistWeightedSelector()
        val picks = IntArray(3)
        val weights = intArrayOf(3, 1, 5)
        val usable = booleanArrayOf(true, true, false)
        repeat(400) { picks[selector.pick(weights, usable, 3)]++ }
        assertEquals(300, picks[0])
        assertEquals(100, picks[1])
        assertEquals(0, picks[2])
        assertEquals(-1, selector.pick(weights, booleanArrayOf(false, false, false), 3))
    }

    @Test
    fun aSlowLinkGetsALowerWeightThanAFastOne() {
        val fast = RistLinkWeight()
        val slow = RistLinkWeight()
        repeat(600) {
            fast.update(20.0)
            slow.update(if (it % 3 == 0) 600.0 else 300.0)
        }
        assertTrue(fast.weight > slow.weight, "${fast.weight} > ${slow.weight}")
        assertTrue(slow.weight >= 1)
    }

    @Test
    fun nacksInBothFormsListTheirSequences() {
        val range = rangeNack(0x1234, 10 to 2, 65535 to 1)
        val bitmask = bitmaskNack(0x1234, 100 to 0b101)
        val packet = range + bitmask
        val sequences = parseRtcp(packet, 0, packet.size).filterIsInstance<Nack>().flatMap { it.sequences.toList() }
        assertEquals(listOf(10, 11, 12, 65535, 0, 100, 101, 103), sequences)
    }

    @Test
    fun aGreHeaderIsReadBack() {
        for (version in 1..2) {
            val out = ByteArray(64)
            val start = RIST_HEADROOM - greHeaderSize(version, GreFrame.Data)
            val payloadAt = writeGreHeader(out, start, version, GreFrame.Data, 7, 32768, 1968)
            val shifted = out.copyOfRange(start, out.size)
            val gre = parseGre(shifted, shifted.size)!!
            assertEquals(GreFrame.Data, gre.frame)
            assertEquals(payloadAt - start, gre.payloadOffset)
        }
    }
}
