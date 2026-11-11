package com.opirl.app.engine.whip

import com.opirl.app.engine.media.VideoCodec
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal const val ANSWER_SDP = "v=0\r\n" +
    "o=- 4215775240449105457 1731359000 IN IP4 0.0.0.0\r\n" +
    "s=-\r\n" +
    "t=0 0\r\n" +
    "a=msid-semantic:WMS*\r\n" +
    "a=fingerprint:sha-256 6B:8B:F0:65:5F:78:E2:51:3B:AC:6F:F3:3F:46:1B:35:DC:B8:5F:64:1A:24:C2:43:F0:A1:58:D0:A1:2C:19:08\r\n" +
    "a=extmap-allow-mixed\r\n" +
    "a=group:BUNDLE 0 1\r\n" +
    "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\n" +
    "c=IN IP4 0.0.0.0\r\n" +
    "a=setup:active\r\n" +
    "a=mid:0\r\n" +
    "a=ice-ufrag:YsZrvVzSOqWnBtYh\r\n" +
    "a=ice-pwd:HgSPiZtLMKuOmJoOwuLIPkmJBslYbYcN\r\n" +
    "a=rtcp-mux\r\n" +
    "a=rtcp-rsize\r\n" +
    "a=rtpmap:111 opus/48000/2\r\n" +
    "a=fmtp:111 minptime=10;useinbandfec=1\r\n" +
    "a=recvonly\r\n" +
    "a=candidate:1753257689 1 udp 2130706431 127.0.0.1 CANDIDATE_PORT typ host\r\n" +
    "a=candidate:1753257689 2 udp 2130706431 127.0.0.1 CANDIDATE_PORT typ host\r\n" +
    "a=candidate:99 1 tcp 1671430143 127.0.0.1 9 typ host tcptype passive\r\n" +
    "a=end-of-candidates\r\n" +
    "m=video 9 UDP/TLS/RTP/SAVPF 96 98\r\n" +
    "c=IN IP4 0.0.0.0\r\n" +
    "a=setup:active\r\n" +
    "a=mid:1\r\n" +
    "a=ice-ufrag:YsZrvVzSOqWnBtYh\r\n" +
    "a=ice-pwd:HgSPiZtLMKuOmJoOwuLIPkmJBslYbYcN\r\n" +
    "a=rtcp-mux\r\n" +
    "a=rtcp-rsize\r\n" +
    "a=rtpmap:96 H264/90000\r\n" +
    "a=fmtp:96 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f\r\n" +
    "a=rtcp-fb:96 nack\r\n" +
    "a=rtcp-fb:96 nack pli\r\n" +
    "a=rtpmap:98 rtx/90000\r\n" +
    "a=fmtp:98 apt=96\r\n" +
    "a=recvonly\r\n"

class WhipPacketsTest {
    private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { all, nal -> all + byteArrayOf(0, 0, 0, 1) + nal }

    private fun nal(header: ByteArray, size: Int): ByteArray = header + ByteArray(size - header.size) { (it * 7 + size).toByte().let { b -> if (b.toInt() == 0) 1 else b } }

    private class Packet(val payload: ByteArray, val marker: Boolean)

    private fun packets(packetizer: VideoPacketizer, frame: ByteArray, config: ByteArray?, key: Boolean): List<Packet> {
        val out = ArrayList<Packet>()
        packetizer.packetize(frame, config, key) { buf, length, marker ->
            out.add(Packet(buf.copyOfRange(RTP_HEADER_SIZE, RTP_HEADER_SIZE + length), marker))
        }
        return out
    }

    private fun h264Nals(packets: List<Packet>): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var fragment = ByteArray(0)
        for (packet in packets) {
            val payload = packet.payload
            when (val type = payload[0].toInt() and 0x1F) {
                24 -> {
                    var at = 1
                    while (at < payload.size) {
                        val size = readU16(payload, at)
                        nals.add(payload.copyOfRange(at + 2, at + 2 + size))
                        at += 2 + size
                    }
                }
                28 -> {
                    val header = payload[1].toInt()
                    if (header and 0x80 != 0) {
                        fragment = byteArrayOf(((payload[0].toInt() and 0xE0) or (header and 0x1F)).toByte())
                    }
                    fragment += payload.copyOfRange(2, payload.size)
                    if (header and 0x40 != 0) {
                        nals.add(fragment)
                    }
                }
                else -> {
                    assertTrue(type in 1..23)
                    nals.add(payload)
                }
            }
        }
        return nals
    }

    private fun hevcNals(packets: List<Packet>): List<ByteArray> {
        val nals = ArrayList<ByteArray>()
        var fragment = ByteArray(0)
        for (packet in packets) {
            val payload = packet.payload
            when ((payload[0].toInt() shr 1) and 0x3F) {
                48 -> {
                    var at = 2
                    while (at < payload.size) {
                        val size = readU16(payload, at)
                        nals.add(payload.copyOfRange(at + 2, at + 2 + size))
                        at += 2 + size
                    }
                }
                49 -> {
                    val header = payload[2].toInt()
                    if (header and 0x80 != 0) {
                        fragment = byteArrayOf(((payload[0].toInt() and 0x81) or ((header and 0x3F) shl 1)).toByte(), payload[1])
                    }
                    fragment += payload.copyOfRange(3, payload.size)
                    if (header and 0x40 != 0) {
                        nals.add(fragment)
                    }
                }
                else -> nals.add(payload)
            }
        }
        return nals
    }

    private fun assertNals(expected: List<ByteArray>, actual: List<ByteArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun anH264KeyFrameCarriesItsParameterSetsAndComesBackWhole() {
        val sps = nal(byteArrayOf(0x67), 24)
        val pps = nal(byteArrayOf(0x68), 6)
        val idr = nal(byteArrayOf(0x65), 5000)
        val sei = nal(byteArrayOf(0x06), 20)
        val out = packets(H264Packetizer(), annexB(sei, idr), annexB(sps, pps), key = true)
        assertNals(listOf(sps, pps, sei, idr), h264Nals(out))
        assertTrue(out.all { it.payload.size <= RTP_MAX_PAYLOAD })
        assertEquals(listOf(out.lastIndex), out.indices.filter { out[it].marker })
        assertEquals(24, out.first().payload[0].toInt() and 0x1F)
    }

    @Test
    fun parameterSetsAlreadyInTheFrameAreNotSentTwice() {
        val sps = nal(byteArrayOf(0x67), 24)
        val pps = nal(byteArrayOf(0x68), 6)
        val idr = nal(byteArrayOf(0x65), 300)
        assertNals(listOf(sps, pps, idr), h264Nals(packets(H264Packetizer(), annexB(sps, pps, idr), annexB(sps, pps), key = true)))
        val slice = nal(byteArrayOf(0x41), 300)
        assertNals(listOf(slice), h264Nals(packets(H264Packetizer(), annexB(slice), annexB(sps, pps), key = false)))
    }

    @Test
    fun anHevcKeyFrameCarriesItsParameterSetsAndComesBackWhole() {
        val vps = nal(byteArrayOf(0x40, 0x01), 24)
        val sps = nal(byteArrayOf(0x42, 0x01), 40)
        val pps = nal(byteArrayOf(0x44, 0x01), 8)
        val idr = nal(byteArrayOf(0x26, 0x01), 4000)
        val out = packets(HevcPacketizer(), annexB(idr), annexB(vps, sps, pps), key = true)
        assertNals(listOf(vps, sps, pps, idr), hevcNals(out))
        assertTrue(out.all { it.payload.size <= RTP_MAX_PAYLOAD })
        assertEquals(listOf(out.lastIndex), out.indices.filter { out[it].marker })
    }

    @Test
    fun theOfferNamesTheCodecTheCandidatesAndBothTracks() {
        val offer = buildOffer(
            SdpOfferParams(
                sessionId = 1,
                iceUfrag = "ufrag",
                icePassword = "password",
                fingerprint = "AA:BB",
                setup = DtlsSetup.ACTPASS,
                cname = "cname",
                streamId = "stream",
                audioTrackId = "audio",
                videoTrackId = "video",
                audioSsrc = 11,
                videoCodec = VideoCodec.HEVC,
                videoSsrc = -2,
                rtxSsrc = 33,
                offerRtx = true,
                candidates = gatherLocalCandidates(listOf(InetAddress.getByName("192.0.2.7")), 5000),
            ),
        )
        val lines = offer.split("\r\n")
        assertTrue("a=group:BUNDLE 0 1" in lines)
        assertTrue("m=audio 9 UDP/TLS/RTP/SAVPF 111" in lines)
        assertTrue("m=video 9 UDP/TLS/RTP/SAVPF 97 98" in lines)
        assertTrue("a=rtpmap:97 H265/90000" in lines)
        assertTrue("a=setup:actpass" in lines)
        assertTrue("a=ssrc:4294967294 cname:cname" in lines)
        assertTrue("a=ssrc-group:FID 4294967294 33" in lines)
        assertTrue(lines.any { it.startsWith("a=candidate:1 1 udp ") && it.endsWith(" 192.0.2.7 5000 typ host") })
        assertEquals(2, lines.count { it == "a=fingerprint:sha-256 AA:BB" })
    }

    @Test
    fun anAnswerGivesTheTransportTheCandidatesAndWhatWasAccepted() {
        val answer = parseAnswer(ANSWER_SDP.replace("CANDIDATE_PORT", "8189"), VideoCodec.H264, H264_PAYLOAD_TYPE)
        assertEquals("YsZrvVzSOqWnBtYh", answer.iceUfrag)
        assertEquals(DtlsSetup.ACTIVE, answer.setup)
        assertEquals("sha-256", answer.fingerprintAlgorithm.lowercase())
        assertEquals(32, answer.fingerprint.size)
        assertEquals(listOf(8189), answer.candidates.map { it.address.port })
        val video = assertNotNull(answer.video)
        assertEquals(96, video.payloadType)
        assertEquals(98, video.rtxPayloadType)
        assertTrue(video.nack && video.pli && !video.rejected)
        assertEquals(111, assertNotNull(answer.audio).payloadType)
        val refused = parseAnswer(ANSWER_SDP.replace("H264/90000", "VP8/90000"), VideoCodec.H264, H264_PAYLOAD_TYPE)
        assertEquals(-1, assertNotNull(refused.video).payloadType)
        assertFailsWith<SdpException> { parseAnswer("v=0\r\ns=-\r\n", VideoCodec.H264, H264_PAYLOAD_TYPE) }
    }

    @Test
    fun feedbackPacketsAreReadBack() {
        val compound = RtcpCompound(ByteArray(256))
        compound.nack(1, 2, intArrayOf(100, 101, 105, 300, 65535, 0))
        compound.pli(1, 2)
        val report = ByteArray(32)
        report[0] = 0x81.toByte()
        report[1] = 201.toByte()
        writeU16(report, 2, 7)
        writeU32(report, 4, 9)
        writeU32(report, 8, 2)
        report[12] = 64
        writeU32(report, 24, 0x12345678)
        writeU32(report, 28, 65536)
        val all = compound.buf.copyOf(compound.length) + report
        val parsed = parseRtcp(all, 0, all.size)
        val nack = parsed.filterIsInstance<RtcpPacket.Nack>().single()
        assertEquals(2, nack.mediaSsrc)
        assertEquals(listOf(100, 101, 105, 300, 65535, 0), nack.sequences.toList())
        assertEquals(2, parsed.filterIsInstance<RtcpPacket.Pli>().single().mediaSsrc)
        val block = parsed.filterIsInstance<RtcpPacket.ReceiverReport>().single().blocks.single()
        assertEquals(2, block.ssrc)
        assertEquals(64, block.fractionLost)
        assertEquals(1500.0, roundTripMs(block, (0x1234L shl 32) + (0x5678L shl 16) + (5L shl 31)))
    }

    @Test
    fun aSentPacketCanBeTakenAgainButNotTooOften() {
        val history = NackHistory(8)
        val into = ByteArray(RTP_PACKET_CAPACITY)
        for (sequence in 0 until 12) history.put(sequence, ByteArray(20) { sequence.toByte() }, 20)
        assertEquals(-1, history.take(3, 1, 10, into))
        assertEquals(20, history.take(11, 1, 10, into))
        assertEquals(11, into[0].toInt())
        assertEquals(-1, history.take(11, 5, 10, into))
        assertEquals(20, history.take(11, 12, 10, into))
    }

    @Test
    fun anOpusPacketSaysHowLongItIs() {
        assertEquals(960, opusPacketSamples(byteArrayOf(0x78, 0)))
        assertEquals(480, opusPacketSamples(byteArrayOf(0x70, 0)))
        assertEquals(1920, opusPacketSamples(byteArrayOf(0x79, 0)))
    }

    @Test
    fun onlyWhipAndHttpUrlsAreTaken() {
        assertEquals("https://example.com/live/whip", parseWhipUrl("whips://example.com/live/whip").toString())
        assertEquals("http://10.0.0.2:8889/live/whip", parseWhipUrl(" whip://10.0.0.2:8889/live/whip ").toString())
        assertFailsWith<IllegalArgumentException> { parseWhipUrl("rtmp://example.com/live") }
        assertFailsWith<IllegalArgumentException> { parseWhipUrl("whip://") }
    }
}
