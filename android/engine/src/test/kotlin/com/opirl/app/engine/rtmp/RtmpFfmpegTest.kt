package com.opirl.app.engine.rtmp

import com.opirl.app.engine.flv.ElementaryStreams
import com.opirl.app.engine.flv.FfmpegTools
import com.opirl.app.engine.flv.GeneratedAudio
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.media.EncodedFrame
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

class RtmpFfmpegTest {
    private val runs = AtomicInteger()

    @BeforeEach
    fun requireFfmpeg() {
        assumeTrue(FfmpegTools.available, "ffmpeg and ffprobe are not installed")
    }

    private fun waitForListening(port: Int) {
        val tables = listOf("/proc/net/tcp", "/proc/net/tcp6").map(::File).filter { it.exists() }
        if (tables.isEmpty()) {
            Thread.sleep(1500)
            return
        }
        val suffix = ":%04X".format(port)
        val listening = waitUntil(10) {
            tables.any { table ->
                table.readLines().drop(1).any { line ->
                    val columns = line.trim().split(Regex("\\s+"))
                    columns.size > 3 && columns[1].endsWith(suffix) && columns[3] == "0A"
                }
            }
        }
        check(listening) { "ffmpeg did not start listening on $port" }
    }

    private fun ingest(
        video: GeneratedVideo?,
        audio: GeneratedAudio?,
        frames: (GeneratedVideo) -> List<EncodedFrame> = { it.frames },
    ): File {
        val run = runs.incrementAndGet()
        val port = FfmpegTools.freePort()
        val output = FfmpegTools.file("ingest-$run.mkv")
        val receiver = FfmpegTools.start(
            listOf("ffmpeg", "-v", "warning", "-y", "-listen", "1", "-i", "rtmp://127.0.0.1:$port/live/key", "-c", "copy", output.path),
            FfmpegTools.file("ingest-$run.log"),
        )
        try {
            waitForListening(port)
            val listener = RecordingListener()
            val publisher = RtmpPublisher("rtmp://127.0.0.1:$port/live", "key", listener)
            video?.let { publisher.setVideoFormat(it.format) }
            audio?.let { publisher.setAudioFormat(it.format) }
            publisher.start()
            assertTrue(listener.awaitConnected(), "not connected: ${listener.reasons}")
            val sourceVideo = video?.let { GeneratedVideo(it.format, frames(it)) }
            ElementaryStreams.feed(publisher, sourceVideo, audio)
            assertTrue(waitUntil { publisher.stats().queuedBytes == 0L })
            Thread.sleep(300)
            publisher.stop()
            assertTrue(listener.reasons.isEmpty(), "unexpected disconnect ${listener.reasons}")
        } finally {
            if (!receiver.waitFor(30, TimeUnit.SECONDS)) {
                receiver.destroyForcibly()
            }
        }
        return output
    }

    private fun assertIngested(output: File, codec: String, video: GeneratedVideo, audio: GeneratedAudio?) {
        val streams = FfmpegTools.probeStreams(output)
        val v = streams.single { it["codec_type"] == "video" }
        assertEquals(codec, v["codec_name"])
        assertEquals("${video.width}", v["width"])
        assertEquals("${video.height}", v["height"])
        assertEquals("${video.frames.size}", v["nb_read_frames"])
        if (audio != null) {
            val a = streams.single { it["codec_type"] == "audio" }
            assertEquals("aac", a["codec_name"])
            assertEquals("48000", a["sample_rate"])
            assertEquals("2", a["channels"])
            val expected = audio.frames.count { it.ptsUs <= video.frames.last().ptsUs }
            assertEquals("$expected", a["nb_read_frames"])
        }
    }

    @Test
    fun h264AndAacAreIngestedFrameForFrame() {
        val video = ElementaryStreams.h264(true)
        val audio = ElementaryStreams.aac()
        assertIngested(ingest(video, audio), "h264", video, audio)
    }

    @Test
    fun h264WithoutRepeatedParameterSetsIsIngested() {
        val video = ElementaryStreams.h264(false)
        val audio = ElementaryStreams.aac()
        assertIngested(ingest(video, audio), "h264", video, audio)
    }

    @Test
    fun hevcAndAacAreIngestedThroughEnhancedRtmp() {
        val video = ElementaryStreams.hevc(false)
        val audio = ElementaryStreams.aac()
        assertIngested(ingest(video, audio), "hevc", video, audio)
    }

    @Test
    fun av1WithTemporalDelimitersAndInBandSequenceHeadersAndAacIsIngestedThroughEnhancedRtmp() {
        val video = ElementaryStreams.av1(false)
        val audio = ElementaryStreams.aac()
        assertIngested(ingest(video, audio), "av1", video, audio)
    }

    @Test
    fun av1FromAnEncoderThatKeepsTheSequenceHeaderOutOfBandIsIngested() {
        val video = ElementaryStreams.av1(true)
        assertIngested(ingest(video, null), "av1", video, null)
    }

    @Test
    fun timestampsStartAtZeroAndSurviveTheExtendedTimestampRange() {
        val video = ElementaryStreams.h264(true)
        val fiveHoursUs = 5L * 3600 * 1_000_000
        val output = ingest(video, null) { source ->
            source.frames.mapIndexed { index, frame ->
                if (index < 30) frame else EncodedFrame(frame.data, frame.ptsUs + fiveHoursUs, frame.isKeyFrame)
            }
        }
        val packets = FfmpegTools.probePackets(output).map { it["pts"]!!.toLong() }
        assertEquals(60, packets.size)
        assertEquals(0L, packets.first())
        val firstHalf = packets.take(30)
        val secondHalf = packets.drop(30)
        assertTrue(firstHalf.zipWithNext().all { (a, b) -> b - a in 31..36 })
        assertEquals(30, secondHalf.count { it >= 5L * 3600 * 1000 })
        assertTrue(secondHalf.zipWithNext().all { (a, b) -> b - a in 31..36 })
        assertTrue(secondHalf.first() - 5L * 3600 * 1000 in 960..1100)
    }

    @Test
    fun streamsStartingMidGopBeginAtTheNextKeyFrame() {
        val video = ElementaryStreams.h264(true)
        val output = ingest(video, null) { source -> source.frames.drop(10) }
        val stream = FfmpegTools.probeStreams(output).single()
        assertEquals("h264", stream["codec_name"])
        assertEquals("${video.frames.size - 30}", stream["nb_read_frames"])
    }
}
