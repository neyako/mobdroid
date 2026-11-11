package com.opirl.app.engine.srt

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val PEER = 0x2ab1f77c
private const val SOCKET = 0x0badcafe

private class Harness(
    streamId: String? = "1234",
    latencyMs: Int = 2000,
    enhanced: Boolean = false,
    initialSequence: Int = 0x100,
) : SrtSenderListener {
    val sent = ArrayList<ByteArray>()
    val disconnects = ArrayList<String>()
    var connectedCount = 0
    val sender = SrtSender(
        SrtConfig(streamId, latencyMs, enhanced),
        SrtOutput { packet, length -> sent.add(packet.copyOf(length)) },
        this,
        socketId = SOCKET,
        initialSequenceNumber = initialSequence,
    )

    override fun onSrtConnected() {
        connectedCount += 1
    }

    override fun onSrtDisconnected(reason: String) {
        disconnects.add(reason)
    }

    fun connect(start: Long = 0): Long {
        sender.start(start)
        sender.input(handshakeResponse(5, SRT_HANDSHAKE_INDUCTION, PEER), 64, start + ms(1))
        sender.input(handshakeResponse(5, SRT_HANDSHAKE_CONCLUSION, PEER), 64, start + ms(2))
        sent.clear()
        return start + ms(10)
    }

    fun send(packets: Int, now: Long, containsAudio: Boolean = false, seed: Int = 1) {
        sender.write(tsPayload(seed, 7 * packets), 0, 1316 * packets, containsAudio, now)
        sender.flush(now)
    }

    fun input(packet: ByteArray, now: Long) {
        sender.input(packet, packet.size, now)
    }

    fun dataPackets(): List<ByteArray> = sent.filter { isDataPacket(it) }
}

class SrtSenderTest {
    @Test
    fun inductionPacketMatchesMoblin() {
        val harness = Harness()
        harness.sender.start(ms(5))
        assertEquals(
            "80000000" + "00000000" + "00000000" + "00000000" +
                "00000004" + "0000" + "0002" + "00000100" + "000005dc" + "00002000" + "00000001" +
                "0badcafe" + "00000000" + "0100007f" + "00000000" + "00000000" + "00000000",
            hex(harness.sent.single()),
        )
    }

    @Test
    fun conclusionPacketMatchesMoblinWithStreamId() {
        val harness = Harness()
        harness.sender.start(ms(5))
        harness.sent.clear()
        harness.input(handshakeResponse(4, SRT_HANDSHAKE_INDUCTION, PEER, 0), ms(10))
        assertEquals(
            "80000000" + "00000000" + "00001388" + "00000000" +
                "00000005" + "0000" + "0005" + "00000100" + "000005dc" + "00002000" + "ffffffff" +
                "2ab1f77c" + "00000000" + "0100007f" + "00000000" + "00000000" + "00000000" +
                "0001" + "0003" + "00010503" + "000000bf" + "07d0" + "07d0" +
                "0005" + "0001" + "34333231",
            hex(harness.sent.single()),
        )
    }

    @Test
    fun conclusionWithoutStreamIdOnlyAdvertisesHandshakeRequest() {
        val harness = Harness(streamId = null)
        harness.sender.start(0)
        harness.sent.clear()
        harness.input(handshakeResponse(5, SRT_HANDSHAKE_INDUCTION, PEER), ms(1))
        val packet = harness.sent.single()
        assertEquals(80, packet.size)
        assertEquals(1, packet.getShort(22))
        assertEquals("0001000300010503000000bf07d007d0", hex(packet.copyOfRange(64, 80)))
    }

    @Test
    fun streamIdIsPaddedAndByteSwappedPerWord() {
        val harness = Harness(streamId = "abcde")
        harness.sender.start(0)
        harness.sent.clear()
        harness.input(handshakeResponse(5, SRT_HANDSHAKE_INDUCTION, PEER), ms(1))
        val packet = harness.sent.single()
        assertEquals("00050002" + "64636261" + "00000065", hex(packet.copyOfRange(80, 92)))
    }

    @Test
    fun handshakeIsRetransmittedWithFreshTimestampUntilAnswered() {
        val harness = Harness()
        harness.sender.start(0)
        assertEquals(1, harness.sent.size)
        harness.sender.onTimer(ms(100))
        assertEquals(1, harness.sent.size)
        harness.sender.onTimer(ms(250))
        assertEquals(2, harness.sent.size)
        assertEquals(250_000, harness.sent[1].getInt(8))
        assertEquals(hex(harness.sent[0]).substring(24), hex(harness.sent[1]).substring(24))
        assertEquals(ms(500), harness.sender.nextDeadline())
    }

    @Test
    fun handshakeTimesOutAfterFiveSeconds() {
        val harness = Harness()
        harness.sender.start(0)
        harness.sender.onTimer(ms(4999))
        assertTrue(harness.disconnects.isEmpty())
        harness.sender.onTimer(ms(5000))
        assertEquals(listOf("SRT handshake timed out"), harness.disconnects)
        assertEquals(0, harness.connectedCount)
    }

    @Test
    fun serverRejectionDisconnectsWithReason() {
        val harness = Harness()
        harness.sender.start(0)
        harness.input(handshakeResponse(5, 1403, 0), ms(5))
        assertEquals(listOf("SRT server rejected the connection (reason 1403)"), harness.disconnects)
    }

    @Test
    fun conclusionResponseConnects() {
        val harness = Harness()
        harness.connect()
        assertEquals(1, harness.connectedCount)
        assertTrue(harness.sender.isConnected)
    }

    @Test
    fun dataPacketsCarryHeaderAndPayload() {
        val harness = Harness()
        harness.connect()
        val payload = tsPayload(3, 8)
        harness.sender.write(payload, 0, payload.size, false, ms(5))
        harness.sender.flush(ms(5))
        val packets = harness.dataPackets()
        assertEquals(2, packets.size)
        assertEquals(0x100, sequenceOf(packets[0]))
        assertEquals(0x101, sequenceOf(packets[1]))
        assertEquals(0xE0000001.toInt(), packets[0].getInt(4))
        assertEquals(5000, packets[0].getInt(8))
        assertEquals(PEER, packets[0].getInt(12))
        assertEquals(16 + 1316, packets[0].size)
        assertEquals(16 + 188, packets[1].size)
        assertContentEquals(payload.copyOfRange(0, 1316), payloadOf(packets[0]))
        assertContentEquals(payload.copyOfRange(1316, 1504), payloadOf(packets[1]))
    }

    @Test
    fun writesAreCoalescedAcrossCallsUntilFlushed() {
        val harness = Harness()
        harness.connect()
        harness.sender.write(tsPayload(1, 3), 0, 564, false, ms(5))
        harness.sender.write(tsPayload(2, 3), 0, 564, false, ms(5))
        assertTrue(harness.dataPackets().isEmpty())
        harness.sender.flush(ms(5))
        val packet = harness.dataPackets().single()
        assertEquals(16 + 1128, packet.size)
    }

    @Test
    fun outputIsPacedToTenPacketsPerTick() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(25, now)
        assertEquals(10, harness.dataPackets().size)
        assertEquals(now + ms(2), harness.sender.nextDeadline())
        harness.sender.flush(now + ms(1))
        assertEquals(10, harness.dataPackets().size)
        harness.sender.onTimer(now + ms(2))
        assertEquals(20, harness.dataPackets().size)
        harness.sender.onTimer(now + ms(4))
        assertEquals(25, harness.dataPackets().size)
    }

    @Test
    fun ackPrunesInFlightAndAnswersWithAckAck() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(5, now)
        assertEquals(5, harness.sender.counters().packetsInFlight)
        harness.sent.clear()
        harness.input(ackPacket(7, 0x103, 20_000), now + ms(30))
        val counters = harness.sender.counters()
        assertEquals(2, counters.packetsInFlight)
        assertEquals(20.0, counters.rttMs)
        val ackAck = harness.sent.single()
        assertEquals(SRT_TYPE_ACKACK, controlType(ackAck))
        assertEquals(7, ackAck.getInt(4))
        assertEquals(PEER, ackAck.getInt(12))
        assertEquals(20, ackAck.size)
    }

    @Test
    fun lightAckPrunesWithoutAckAck() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(5, now)
        harness.sent.clear()
        harness.input(lightAckPacket(0x105), now + ms(30))
        assertEquals(0, harness.sender.counters().packetsInFlight)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun nakRetransmitsWithRetransmissionBitOncePerRtt() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(5, now)
        harness.input(ackPacket(1, 0x100, 50_000), now + ms(10))
        harness.sent.clear()
        harness.input(nakPacket(0x102), now + ms(20))
        val first = harness.sent.single()
        assertEquals(0x102, sequenceOf(first))
        assertEquals(0xE4000001.toInt(), first.getInt(4))
        assertEquals(1, harness.sender.counters().retransmittedPackets)
        harness.sent.clear()
        harness.input(nakPacket(0x102), now + ms(40))
        assertTrue(harness.sent.isEmpty())
        harness.input(nakPacket(0x102), now + ms(80))
        assertEquals(0x102, sequenceOf(harness.sent.single()))
        assertEquals(2, harness.sender.counters().retransmittedPackets)
        assertEquals(3, harness.sender.counters().receivedNaks)
    }

    @Test
    fun nakRangeRetransmitsEveryPacketInRange() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(6, now)
        harness.sent.clear()
        harness.input(nakPacket(*nakRange(0x101, 0x103), 0x105), now + ms(20))
        assertEquals(listOf(0x101, 0x102, 0x103, 0x105), harness.sent.map { sequenceOf(it) })
    }

    @Test
    fun nakForUnknownOrAckedPacketsIsIgnored() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(3, now)
        harness.input(lightAckPacket(0x102), now + ms(5))
        harness.sent.clear()
        harness.input(nakPacket(0x100, 0x500), now + ms(20))
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun audioIsRetransmittedBeforeVideoInEnhancedMode() {
        val harness = Harness(enhanced = true)
        val now = harness.connect()
        harness.send(1, now, containsAudio = false, seed = 1)
        harness.send(1, now + ms(3), containsAudio = true, seed = 2)
        harness.sent.clear()
        harness.input(nakPacket(0x100, 0x101), now + ms(10))
        assertEquals(listOf(0x101), harness.sent.map { sequenceOf(it) })
        harness.sender.onTimer(now + ms(13))
        assertEquals(listOf(0x101, 0x100), harness.sent.map { sequenceOf(it) })
    }

    @Test
    fun unsentPacketsAreDroppedAfterFiveQuartersOfLatency() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(300, now)
        assertEquals(30, harness.dataPackets().size)
        harness.sender.onTimer(now + ms(2600))
        val counters = harness.sender.counters()
        assertEquals(0, counters.queuedPackets)
        assertEquals(0L, counters.queuedBytes)
        assertEquals(243, counters.droppedPackets.toInt())
        assertEquals(57, counters.packetsInFlight)
    }

    @Test
    fun inFlightPacketsAreDroppedAfterThreeHalvesOfLatency() {
        val harness = Harness()
        val now = harness.connect()
        harness.send(2, now)
        harness.sender.onTimer(now + ms(2999))
        assertEquals(2, harness.sender.counters().packetsInFlight)
        harness.sender.onTimer(now + ms(3001))
        assertEquals(0, harness.sender.counters().packetsInFlight)
        assertEquals(2L, harness.sender.counters().droppedPackets)
    }

    @Test
    fun enhancedModeDropsEarlier() {
        val harness = Harness(enhanced = true)
        val now = harness.connect()
        harness.send(2, now)
        harness.sender.onTimer(now + ms(1300))
        assertEquals(2, harness.sender.counters().packetsInFlight)
        harness.sender.onTimer(now + ms(1400))
        assertEquals(0, harness.sender.counters().packetsInFlight)
    }

    @Test
    fun sequenceNumbersWrapAtThirtyOneBits() {
        val harness = Harness(initialSequence = 0x7ffffffe)
        val now = harness.connect()
        harness.send(3, now)
        assertEquals(listOf(0x7ffffffe, 0x7fffffff, 0), harness.dataPackets().map { sequenceOf(it) })
        harness.input(lightAckPacket(0), now + ms(5))
        assertEquals(1, harness.sender.counters().packetsInFlight)
        harness.sent.clear()
        harness.input(nakPacket(0), now + ms(20))
        assertEquals(0, sequenceOf(harness.sent.single()))
    }

    @Test
    fun peerIdleDisconnectsAndSaysGoodbye() {
        val harness = Harness()
        harness.connect()
        harness.sender.onTimer(ms(5002))
        assertTrue(harness.disconnects.isEmpty())
        harness.sender.onTimer(ms(5003))
        assertEquals(listOf("SRT server stopped responding"), harness.disconnects)
        assertEquals(SRT_TYPE_SHUTDOWN, controlType(harness.sent.last()))
        assertFalse(harness.sender.isConnected)
    }

    @Test
    fun keepAliveIsEchoedAndSentWhenIdle() {
        val harness = Harness()
        harness.connect()
        harness.input(controlHeader(SRT_TYPE_KEEPALIVE), ms(500))
        val echo = harness.sent.single()
        assertEquals(SRT_TYPE_KEEPALIVE, controlType(echo))
        assertEquals(PEER, echo.getInt(12))
        harness.sent.clear()
        harness.sender.onTimer(ms(1499))
        assertTrue(harness.sent.isEmpty())
        harness.sender.onTimer(ms(1500))
        assertEquals(SRT_TYPE_KEEPALIVE, controlType(harness.sent.single()))
    }

    @Test
    fun peerShutdownDisconnectsWithoutAnswering() {
        val harness = Harness()
        val now = harness.connect()
        harness.input(controlHeader(SRT_TYPE_SHUTDOWN, size = 20), now + ms(5))
        assertEquals(listOf("SRT server closed the connection"), harness.disconnects)
        assertTrue(harness.sent.isEmpty())
    }

    @Test
    fun stopSendsShutdownAndIsSilent() {
        val harness = Harness()
        val now = harness.connect()
        harness.sender.stop(now + ms(5))
        val shutdown = harness.sent.single()
        assertEquals(SRT_TYPE_SHUTDOWN, controlType(shutdown))
        assertEquals(PEER, shutdown.getInt(12))
        assertTrue(harness.disconnects.isEmpty())
        harness.sender.write(tsPayload(1, 7), 0, 1316, false, now + ms(6))
        harness.sender.flush(now + ms(6))
        assertEquals(1, harness.sent.size)
    }

    @Test
    fun queuesAreHardBoundedEvenWithAHugeLatency() {
        val harness = Harness(latencyMs = 65535)
        var now = harness.connect()
        repeat(20_000) {
            harness.send(1, now)
            if (it % 500 == 0) {
                harness.input(controlHeader(SRT_TYPE_KEEPALIVE), now)
            }
            now += ms(2)
        }
        val counters = harness.sender.counters()
        assertEquals(16384, counters.packetsInFlight)
        assertEquals(20_000L - 16384, counters.droppedPackets)
        assertEquals(0, counters.queuedPackets)
    }

    @Test
    fun recycledBuffersNeverLeakStalePayload() {
        val harness = Harness()
        var now = harness.connect()
        repeat(200) { round ->
            val payload = tsPayload(round, 7)
            harness.sender.write(payload, 0, payload.size, false, now)
            harness.sender.flush(now)
            val packet = harness.dataPackets().last()
            assertContentEquals(payload, payloadOf(packet))
            harness.input(lightAckPacket(sequenceOf(packet) + 1), now + ms(1))
            now += ms(3)
        }
        assertEquals(0, harness.sender.counters().packetsInFlight)
        assertEquals(200, harness.dataPackets().size)
    }
}
