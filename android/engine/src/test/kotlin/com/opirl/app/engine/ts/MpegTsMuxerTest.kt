package com.opirl.app.engine.ts

import com.opirl.app.engine.flv.annexB
import com.opirl.app.engine.flv.concat
import com.opirl.app.engine.flv.hex
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Collector {
    val calls = ArrayList<ByteArray>()
    val muxer = MpegTsMuxer { buffer, offset, length -> calls.add(buffer.copyOfRange(offset, offset + length)) }

    fun packets(): List<TsPacket> {
        val out = ByteArrayOutputStream()
        calls.forEach { out.write(it) }
        return TsParser.packets(out.toByteArray())
    }
}

class MpegTsMuxerTest {
    private val h264Sps = hex("6742c00dda0507ec0440000003004000000f03c50aa8")
    private val h264Pps = hex("68ce0fc8")
    private val hevcVps = hex("40010c01ffff01600000030090000003000003003cba0240")
    private val hevcSps = hex("42010101600000030090000003000003003ca00a080f165ba4a4c2f0168080000003008000000f04")
    private val hevcPps = hex("4401c073c189")
    private val audioFormat = AudioFormat(48000, 2, hex("1190"))
    private val sequenceHeader = hex("0a0b000000043cffbc02f80040")
    private val random = Random(3)

    private fun h264Format() = VideoFormat(VideoCodec.H264, 320, 240, 30, annexB(h264Sps, h264Pps))

    private fun hevcFormat() = VideoFormat(VideoCodec.HEVC, 320, 240, 30, annexB(hevcVps, hevcSps, hevcPps))

    private fun av1Format() = VideoFormat(VideoCodec.AV1, 320, 240, 30, sequenceHeader)

    private fun payload(size: Int) = ByteArray(size) { (random.nextInt(255) + 1).toByte() }

    private fun frame(data: ByteArray, ptsUs: Long, key: Boolean = false) = EncodedFrame(data, ptsUs, key)

    private fun pid(high: Byte, low: Byte) = ((high.toInt() and 0x1F) shl 8) or (low.toInt() and 0xFF)

    @Test
    fun everyOutputCallCarriesWholePacketsOfAtMostSeven() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        listOf(1, 100, 170, 171, 182, 183, 184, 185, 366, 367, 5000, 70000).forEachIndexed { i, size ->
            collector.muxer.writeVideo(frame(annexB(payload(size)), i * 33_333L, i % 3 == 0))
        }
        assertTrue(collector.calls.isNotEmpty())
        for (call in collector.calls) {
            assertTrue(call.size % 188 == 0 && call.size in 188..188 * 7, "call of ${call.size} bytes")
        }
        collector.packets()
    }

    @Test
    fun pesPayloadSurvivesFramingForEverySizeAroundPacketBoundaries() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        val frames = (1..700).map { annexB(payload(it)) }
        frames.forEachIndexed { i, data -> collector.muxer.writeVideo(frame(data, i * 33_333L)) }
        val pes = TsParser.pes(collector.packets(), VIDEO_PID)
        assertEquals(frames.size, pes.size)
        frames.forEachIndexed { i, data -> assertContentEquals(data, pes[i].payload, "frame $i") }
    }

    @Test
    fun continuityCountersIncrementPerPidWithoutGaps() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.setAudioFormat(audioFormat)
        repeat(60) { i ->
            collector.muxer.writeVideo(frame(annexB(payload(3000)), i * 33_333L, i % 30 == 0))
            collector.muxer.writeAudio(frame(payload(300), i * 33_333L))
        }
        val packets = collector.packets()
        for (pid in listOf(PAT_PID, PMT_PID, VIDEO_PID, AUDIO_PID)) {
            val counters = packets.filter { it.pid == pid }.map { it.continuity }
            assertTrue(counters.size > 1, "pid $pid")
            counters.zipWithNext().forEach { (a, b) -> assertEquals((a + 1) and 15, b, "pid $pid") }
        }
    }

    @Test
    fun pcrRidesOnVideoPacketsMonotonicallyAndStaysBelowPts() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.setAudioFormat(audioFormat)
        repeat(90) { i ->
            collector.muxer.writeVideo(frame(annexB(payload(800)), 5_000_000L + i * 33_333L))
            collector.muxer.writeAudio(frame(payload(200), 5_000_000L + i * 33_333L))
        }
        val packets = collector.packets()
        val pcrPackets = packets.filter { it.pcr != null }
        assertTrue(pcrPackets.all { it.pid == VIDEO_PID && it.payloadUnitStart })
        val values = pcrPackets.map { it.pcr!! }
        assertTrue(values.size >= 40)
        values.zipWithNext().forEach { (a, b) ->
            assertTrue(b >= a)
            assertTrue(b - a <= 9000)
        }
        val firstPts = TsParser.pes(packets, VIDEO_PID).first().pts!!
        assertTrue(firstPts > values.first(), "PTS $firstPts must start above PCR ${values.first()}")
        assertTrue(TsParser.pes(packets, AUDIO_PID).all { it.pts!! > values.first() })
    }

    @Test
    fun pcrFallsBackToAudioPidWithoutVideo() {
        val collector = Collector()
        collector.muxer.setAudioFormat(audioFormat)
        repeat(50) { i -> collector.muxer.writeAudio(frame(payload(200), i * 21_333L)) }
        val packets = collector.packets()
        assertTrue(packets.any { it.pid == AUDIO_PID && it.pcr != null })
        assertTrue(packets.none { it.pcr != null && it.pid != AUDIO_PID })
        val pmt = TsParser.section(packets, PMT_PID)
        assertEquals(AUDIO_PID, pid(pmt.body[0], pmt.body[1]))
    }

    @Test
    fun keyFramesCarryRandomAccessAndFollowAFreshPatAndPmt() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        repeat(70) { i -> collector.muxer.writeVideo(frame(annexB(payload(500)), i * 33_333L, i % 30 == 0)) }
        val packets = collector.packets()
        val pes = TsParser.pes(packets, VIDEO_PID)
        pes.forEachIndexed { i, unit ->
            val key = i % 30 == 0
            assertEquals(key, unit.firstPacket.randomAccess, "frame $i")
            if (key) {
                val index = unit.firstPacket.index
                assertEquals(PAT_PID, packets[index - 2].pid)
                assertEquals(PMT_PID, packets[index - 1].pid)
            }
        }
        assertTrue(packets.none { it.randomAccess && !it.payloadUnitStart })
    }

    @Test
    fun patAndPmtDescribeBothStreamsWithValidCrc() {
        val collector = Collector()
        collector.muxer.setVideoFormat(hevcFormat())
        collector.muxer.setAudioFormat(audioFormat)
        collector.muxer.writeVideo(frame(annexB(payload(50)), 0, true))
        collector.muxer.writeAudio(frame(payload(50), 0))
        val packets = collector.packets()
        val pat = TsParser.section(packets, PAT_PID)
        assertTrue(pat.crcValid)
        assertEquals(0, pat.tableId)
        assertEquals(1, ((pat.body[0].toInt() and 0xFF) shl 8) or (pat.body[1].toInt() and 0xFF))
        assertEquals(PMT_PID, pid(pat.body[2], pat.body[3]))
        val pmt = TsParser.section(packets, PMT_PID)
        assertTrue(pmt.crcValid)
        assertEquals(2, pmt.tableId)
        assertEquals(VIDEO_PID, pid(pmt.body[0], pmt.body[1]))
        val entries = pmt.body.copyOfRange(4, pmt.body.size)
        assertEquals(0x24, entries[0].toInt())
        assertEquals(VIDEO_PID, pid(entries[1], entries[2]))
        assertEquals(0x0F, entries[5].toInt())
        assertEquals(AUDIO_PID, pid(entries[6], entries[7]))
        assertEquals(10, entries.size)
    }

    @Test
    fun h264StreamTypeIs0x1B() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.writeVideo(frame(annexB(payload(50)), 0, true))
        assertEquals(0x1B, TsParser.section(collector.packets(), PMT_PID).body[4].toInt())
    }

    @Test
    fun lateAudioFormatBumpsPmtVersionAndAddsTheStream() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.writeVideo(frame(annexB(payload(50)), 0, true))
        collector.muxer.setAudioFormat(audioFormat)
        collector.muxer.writeAudio(frame(payload(50), 10_000))
        val packets = collector.packets()
        val pmts = packets.filter { it.pid == PMT_PID && it.payloadUnitStart }
        assertEquals(2, pmts.size)
        val first = TsParser.section(packets.subList(0, pmts[1].index), PMT_PID)
        val second = TsParser.section(packets.subList(pmts[1].index, packets.size), PMT_PID)
        assertEquals(0, first.version)
        assertEquals(1, second.version)
        assertEquals(first.body.size + 5, second.body.size)
    }

    @Test
    fun emptyFramesProduceNoOutput() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.setAudioFormat(audioFormat)
        collector.muxer.writeVideo(frame(ByteArray(0), 0, true))
        collector.muxer.writeAudio(frame(ByteArray(0), 0))
        assertTrue(collector.calls.isEmpty())
    }

    @Test
    fun framesWrittenBeforeTheirFormatAreIgnored() {
        val collector = Collector()
        collector.muxer.writeVideo(frame(annexB(payload(50)), 0, true))
        collector.muxer.writeAudio(frame(payload(50), 0))
        assertTrue(collector.calls.isEmpty())
    }

    @Test
    fun pesHeadersCarryPtsEqualToDtsForVideoAndPtsOnlyForAudio() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.setAudioFormat(audioFormat)
        collector.muxer.writeVideo(frame(annexB(payload(500)), 1_000_000, true))
        collector.muxer.writeVideo(frame(annexB(payload(500)), 1_033_333))
        collector.muxer.writeAudio(frame(payload(200), 1_020_000))
        val packets = collector.packets()
        val video = TsParser.pes(packets, VIDEO_PID)
        assertTrue(video.all { it.streamId == 0xE0 && it.alignment && it.pts == it.dts })
        assertEquals(2999L, video[1].pts!! - video[0].pts!!)
        val audio = TsParser.pes(packets, AUDIO_PID).single()
        assertEquals(0xC0, audio.streamId)
        assertNull(audio.dts)
        assertEquals(1800L, audio.pts!! - video[0].pts!!)
        assertTrue(audio.declaredLength > 0)
        assertTrue(video.all { it.declaredLength > 0 })
    }

    @Test
    fun largeVideoFramesUseUnboundedPesLength() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.writeVideo(frame(annexB(payload(70000)), 0, true))
        assertEquals(0, TsParser.pes(collector.packets(), VIDEO_PID).single().declaredLength)
    }

    @Test
    fun audioPesCarriesAnAdtsHeaderBeforeTheRawFrame() {
        val collector = Collector()
        collector.muxer.setAudioFormat(audioFormat)
        val raw = payload(200)
        collector.muxer.writeAudio(frame(raw, 0))
        val pes = TsParser.pes(collector.packets(), AUDIO_PID).single()
        assertContentEquals(hex("fff14c8019fffc") + raw, pes.payload)
    }

    @Test
    fun h264KeyFramesGetParameterSetsOnlyWhenTheEncoderDoesNotRepeatThem() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        val bare = annexB(hex("65") + payload(100))
        val withSets = annexB(h264Sps, h264Pps, hex("65") + payload(100))
        val inter = annexB(hex("41") + payload(100))
        collector.muxer.writeVideo(frame(bare, 0, true))
        collector.muxer.writeVideo(frame(withSets, 33_333, true))
        collector.muxer.writeVideo(frame(inter, 66_666, false))
        val pes = TsParser.pes(collector.packets(), VIDEO_PID)
        assertContentEquals(concat(annexB(h264Sps, h264Pps), bare), pes[0].payload)
        assertContentEquals(withSets, pes[1].payload)
        assertContentEquals(inter, pes[2].payload)
    }

    @Test
    fun hevcKeyFramesGetAllThreeParameterSetsWhenAnyIsMissing() {
        val collector = Collector()
        collector.muxer.setVideoFormat(hevcFormat())
        val bare = annexB(hex("2601") + payload(100))
        val partial = annexB(hevcSps, hevcPps, hex("2601") + payload(100))
        val complete = annexB(hevcVps, hevcSps, hevcPps, hex("2601") + payload(100))
        collector.muxer.writeVideo(frame(bare, 0, true))
        collector.muxer.writeVideo(frame(partial, 33_333, true))
        collector.muxer.writeVideo(frame(complete, 66_666, true))
        val pes = TsParser.pes(collector.packets(), VIDEO_PID)
        val sets = annexB(hevcVps, hevcSps, hevcPps)
        assertContentEquals(concat(sets, bare), pes[0].payload)
        assertContentEquals(concat(sets, partial), pes[1].payload)
        assertContentEquals(complete, pes[2].payload)
    }

    @Test
    fun timestampsAreRebasedToTheFirstFrameAndWrapAt33Bits() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        val base = 1_000_000_000_000L
        val wrapUs = (1L shl 33) * 100 / 9
        collector.muxer.writeVideo(frame(annexB(payload(50)), base, true))
        collector.muxer.writeVideo(frame(annexB(payload(50)), base + wrapUs + 90_000, false))
        val packets = collector.packets()
        val pes = TsParser.pes(packets, VIDEO_PID)
        assertEquals(63_000L, pes[0].pts)
        val expected = (63_000L + (wrapUs + 90_000) * 9 / 100) and 0x1_FFFF_FFFFL
        assertEquals(expected, pes[1].pts)
        assertTrue(pes[1].pts!! < 100_000)
    }

    @Test
    fun audioThatPrecedesVideoByTenMillisecondsStillGetsPositiveTimestamps() {
        val collector = Collector()
        collector.muxer.setVideoFormat(h264Format())
        collector.muxer.setAudioFormat(audioFormat)
        collector.muxer.writeAudio(frame(payload(100), 2_000_000))
        collector.muxer.writeVideo(frame(annexB(payload(100)), 1_990_000, true))
        val packets = collector.packets()
        val video = TsParser.pes(packets, VIDEO_PID).single()
        val audio = TsParser.pes(packets, AUDIO_PID).single()
        assertEquals(audio.pts!! - 900, video.pts)
        assertNotNull(packets.firstOrNull { it.pcr != null })
    }

    @Test
    fun av1UsesPrivatePesWithRegistrationAndVideoDescriptors() {
        val collector = Collector()
        collector.muxer.setVideoFormat(av1Format())
        collector.muxer.writeVideo(frame(concat(hex("1200"), sequenceHeader, hex("320300aabb")), 0, true))
        val packets = collector.packets()
        val pmt = TsParser.section(packets, PMT_PID)
        assertTrue(pmt.crcValid)
        val entry = pmt.body.copyOfRange(4, pmt.body.size)
        assertEquals(0x06, entry[0].toInt())
        assertEquals(VIDEO_PID, pid(entry[1], entry[2]))
        assertEquals(12, ((entry[3].toInt() and 0x0F) shl 8) or (entry[4].toInt() and 0xFF))
        assertContentEquals(hex("0504" + "41563031" + "8004" + "81000cc0"), entry.copyOfRange(5, 17))
        val pes = TsParser.pes(packets, VIDEO_PID).single()
        assertEquals(0xBD, pes.streamId)
        assertNotNull(pes.dts)
        assertTrue(pes.firstPacket.randomAccess && pes.firstPacket.priority)
    }

    @Test
    fun av1PayloadUsesStartCodesEscapesAndDropsTemporalDelimiters() {
        val collector = Collector()
        collector.muxer.setVideoFormat(av1Format())
        val frameObu = hex("32060000010203ff")
        collector.muxer.writeVideo(frame(concat(hex("1200"), frameObu), 0, true))
        collector.muxer.writeVideo(frame(concat(hex("1200"), sequenceHeader, frameObu), 33_333, true))
        collector.muxer.writeVideo(frame(concat(hex("1200"), frameObu), 66_666, false))
        val pes = TsParser.pes(collector.packets(), VIDEO_PID)
        val escapedSequenceHeader = hex("000001" + "0a0b00000300043cffbc02f80040")
        val escapedFrame = hex("000001" + "3206000003010203ff")
        assertContentEquals(concat(escapedSequenceHeader, escapedFrame), pes[0].payload)
        assertContentEquals(concat(escapedSequenceHeader, escapedFrame), pes[1].payload)
        assertContentEquals(escapedFrame, pes[2].payload)
        assertTrue(!pes[2].firstPacket.randomAccess && !pes[2].firstPacket.priority)
    }
}
