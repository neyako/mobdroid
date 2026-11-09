package app.mobdroid.engine.ts

import app.mobdroid.engine.flv.ElementaryStreams
import app.mobdroid.engine.flv.FfmpegTools
import app.mobdroid.engine.flv.GeneratedAudio
import app.mobdroid.engine.flv.GeneratedVideo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MpegTsFfmpegTest {
    private val files = AtomicInteger()

    @BeforeEach
    fun requireFfmpeg() {
        assumeTrue(FfmpegTools.available, "ffmpeg and ffprobe are not installed")
    }

    private fun mux(video: GeneratedVideo?, audio: GeneratedAudio?): File {
        val file = FfmpegTools.file("mux-${files.incrementAndGet()}.ts")
        FileOutputStream(file).use { stream ->
            ElementaryStreams.feed(MpegTsMuxer { buffer, offset, length -> stream.write(buffer, offset, length) }, video, audio)
        }
        return file
    }

    private fun expectedAudioFrames(video: GeneratedVideo, audio: GeneratedAudio): Int {
        val endUs = video.frames.last().ptsUs
        return audio.frames.count { it.ptsUs <= endUs }
    }

    private fun assertDecodes(file: File, codec: String, video: GeneratedVideo, audio: GeneratedAudio) {
        val streams = FfmpegTools.probeStreams(file)
        val v = streams.single { it["codec_type"] == "video" }
        assertEquals(codec, v["codec_name"])
        assertEquals("${video.width}", v["width"])
        assertEquals("${video.height}", v["height"])
        assertEquals("${video.frames.size}", v["nb_read_frames"])
        val a = streams.single { it["codec_type"] == "audio" }
        assertEquals("aac", a["codec_name"])
        assertEquals("48000", a["sample_rate"])
        assertEquals("2", a["channels"])
        assertEquals("${expectedAudioFrames(video, audio)}", a["nb_read_frames"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun h264AndAacDecodeWithEveryFrame(encoderRepeatsParameterSets: Boolean) {
        val video = ElementaryStreams.h264(encoderRepeatsParameterSets)
        val audio = ElementaryStreams.aac()
        assertDecodes(mux(video, audio), "h264", video, audio)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun hevcAndAacDecodeWithEveryFrame(encoderRepeatsParameterSets: Boolean) {
        val video = ElementaryStreams.hevc(encoderRepeatsParameterSets)
        val audio = ElementaryStreams.aac()
        assertDecodes(mux(video, audio), "hevc", video, audio)
    }

    @Test
    fun videoAloneAndAudioAloneStillProduceValidStreams() {
        val video = ElementaryStreams.h264(false)
        val videoOnly = FfmpegTools.probeStreams(mux(video, null)).single()
        assertEquals("h264", videoOnly["codec_name"])
        assertEquals("${video.frames.size}", videoOnly["nb_read_frames"])
        val audio = ElementaryStreams.aac()
        val audioOnly = FfmpegTools.probeStreams(mux(null, audio)).single()
        assertEquals("aac", audioOnly["codec_name"])
        assertEquals("${audio.frames.size}", audioOnly["nb_read_frames"])
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun av1IsDemuxedAsAv01TaggedDataWhoseObusRoundTripToDecodableVideo(mediaCodecStyle: Boolean) {
        val video = ElementaryStreams.av1(mediaCodecStyle)
        val file = mux(video, null)
        val stream = FfmpegTools.probeStreams(file, countFrames = false).single()
        assertEquals("AV01", stream["codec_tag_string"])
        assertEquals("0x100", stream["id"])

        val packets = FfmpegTools.probePackets(file)
        assertEquals(video.frames.size, packets.size)
        val spacing = packets.zipWithNext().map { (a, b) -> b["pts"]!!.toLong() - a["pts"]!!.toLong() }
        assertTrue(spacing.all { it in 2998..3002 }, "spacing $spacing")

        val payloadFile = FfmpegTools.file("av1-payload-${files.incrementAndGet()}.bin")
        val extract = FfmpegTools.run(
            listOf("ffmpeg", "-v", "error", "-y", "-i", file.path, "-map", "0:0", "-c", "copy", "-f", "data", payloadFile.path),
            60,
        )
        assertEquals(0, extract.exitCode, extract.output)
        val payload = payloadFile.readBytes()
        var offset = 0
        val obuStream = ByteArrayOutputStream()
        for (packet in packets) {
            val size = packet["size"]!!.toInt()
            obuStream.write(temporalUnitFrom(payload.copyOfRange(offset, offset + size)))
            offset += size
        }
        assertEquals(payload.size, offset)
        val rebuilt = FfmpegTools.file("av1-rebuilt-${files.incrementAndGet()}.obu")
        rebuilt.writeBytes(obuStream.toByteArray())
        val decoded = FfmpegTools.probeStreams(rebuilt).single()
        assertEquals("av1", decoded["codec_name"])
        assertEquals("${video.width}", decoded["width"])
        assertEquals("${video.height}", decoded["height"])
        assertEquals("${video.frames.size}", decoded["nb_read_frames"])
    }

    private fun temporalUnitFrom(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0))
        val starts = (0..payload.size - 3).filter {
            payload[it].toInt() == 0 && payload[it + 1].toInt() == 0 && payload[it + 2].toInt() == 1
        }
        assertEquals(0, starts.first())
        starts.forEachIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else payload.size
            out.write(unescape(payload.copyOfRange(start + 3, end)))
        }
        return out.toByteArray()
    }

    private fun unescape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        for (b in data) {
            if (zeros >= 2 && b.toInt() == 3) {
                zeros = 0
                continue
            }
            out.write(b.toInt())
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    @Test
    fun unescapedAv1PayloadMatchesTheEncodedObusExceptTemporalDelimiters() {
        val video = ElementaryStreams.av1(false)
        val file = mux(video, null)
        val payloadFile = FfmpegTools.file("av1-compare-${files.incrementAndGet()}.bin")
        FfmpegTools.run(listOf("ffmpeg", "-v", "error", "-y", "-i", file.path, "-map", "0:0", "-c", "copy", "-f", "data", payloadFile.path), 60)
        val first = FfmpegTools.probePackets(file).first()["size"]!!.toInt()
        val firstUnit = temporalUnitFrom(payloadFile.readBytes().copyOfRange(0, first))
        assertContentEquals(video.frames.first().data, firstUnit)
    }
}
