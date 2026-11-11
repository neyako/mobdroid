package com.opirl.app.engine.mp4

import com.opirl.app.engine.flv.ElementaryStreams
import com.opirl.app.engine.flv.FfmpegTools
import com.opirl.app.engine.flv.GeneratedAudio
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.media.EncodedFrame
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach

class Mp4RecorderTest {
    private val files = AtomicInteger()

    @BeforeEach
    fun requireFfmpeg() {
        assumeTrue(FfmpegTools.available, "ffmpeg and ffprobe are not installed")
    }

    private fun record(video: GeneratedVideo, audio: GeneratedAudio): File {
        val file = FfmpegTools.file("record-${files.incrementAndGet()}.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val recorder = Mp4Recorder(raf.channel, raf.channel) { error(it) }
            recorder.start()
            ElementaryStreams.feed(recorder, video, audio)
            assertNotNull(recorder.stop())
        }
        return file
    }

    private fun finish(file: File): Mp4Summary? = RandomAccessFile(file, "rw").use { finishMp4(it.channel, it.channel) }

    private fun assertDecodes(file: File, codec: String, videoFrames: Int, audioFrames: Int) {
        val streams = FfmpegTools.probeStreams(file)
        val v = streams.single { it["codec_type"] == "video" }
        assertEquals(codec, v["codec_name"])
        assertEquals("${ElementaryStreams.WIDTH}", v["width"])
        assertEquals("${ElementaryStreams.HEIGHT}", v["height"])
        assertEquals("$videoFrames", v["nb_read_frames"])
        assertEquals(videoFrames.toDouble() / ElementaryStreams.FPS, v.getValue("duration").toDouble(), 0.001)
        val a = streams.single { it["codec_type"] == "audio" }
        assertEquals("aac", a["codec_name"])
        assertEquals("48000", a["sample_rate"])
        assertEquals("2", a["channels"])
        assertEquals("$audioFrames", a["nb_read_frames"])
        val decode = FfmpegTools.run(listOf("ffmpeg", "-v", "error", "-xerror", "-i", file.path, "-f", "null", "-"), 60)
        assertEquals("", decode.output.trim())
        assertEquals(0, decode.exitCode)
    }

    private fun audioFrames(video: GeneratedVideo, audio: GeneratedAudio) =
        audio.frames.count { it.ptsUs <= video.frames.last().ptsUs }

    @Test
    fun h264AndAacDecodeWithEveryFrame() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        assertDecodes(record(video, audio), "h264", video.frames.size, audioFrames(video, audio))
    }

    @Test
    fun hevcAndAacDecodeWithEveryFrame() {
        val video = ElementaryStreams.hevc(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        assertDecodes(record(video, audio), "hevc", video.frames.size, audioFrames(video, audio))
    }

    @Test
    fun av1AndAacDecodeWithEveryFrame() {
        val video = ElementaryStreams.av1(mediaCodecStyle = true)
        val audio = ElementaryStreams.aac()
        assertDecodes(record(video, audio), "av1", video.frames.size, audioFrames(video, audio))
    }

    @Test
    fun aRecordingCutOffMidBlockIsRecoveredUpToItsLastWholeBlock() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        val file = record(video, audio)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(FILE_HEADER_SIZE + 8L)
            val moovAt = FILE_HEADER_SIZE + raf.readLong()
            raf.setLength(moovAt - 10)
            raf.seek(FILE_HEADER_SIZE + 8L)
            raf.writeLong(MDAT_HEADER_SIZE.toLong())
        }
        assertNotNull(finish(file))
        val secondUs = 1_000_000L
        assertDecodes(file, "h264", video.frames.count { it.ptsUs < secondUs }, audio.frames.count { it.ptsUs < secondUs })
    }

    @Test
    fun finishingAFinishedRecordingKeepsEveryFrame() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        val file = record(video, audio)
        val size = file.length()
        assertNotNull(finish(file))
        assertEquals(size, file.length())
        assertDecodes(file, "h264", video.frames.size, audioFrames(video, audio))
    }

    @Test
    fun audioThatStartsAfterTheVideoKeepsItsOffset() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        val late = GeneratedAudio(audio.format, audio.frames.map { EncodedFrame(it.data, it.ptsUs + 300_000, true) })
        val streams = FfmpegTools.probeStreams(record(video, late), countFrames = false)
        assertEquals(0.0, streams.single { it["codec_type"] == "video" }.getValue("start_time").toDouble(), 0.001)
        assertEquals(0.3, streams.single { it["codec_type"] == "audio" }.getValue("start_time").toDouble(), 0.001)
    }

    @Test
    fun audioThatArrivesBeforeItsKeyFrameIsKept() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        val file = FfmpegTools.file("record-${files.incrementAndGet()}.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val recorder = Mp4Recorder(raf.channel, raf.channel) { error(it) }
            recorder.start()
            recorder.setVideoFormat(video.format)
            recorder.setAudioFormat(audio.format)
            audio.frames.take(10).forEach(recorder::writeAudio)
            Thread.sleep(200)
            ElementaryStreams.feed(recorder, video, GeneratedAudio(audio.format, audio.frames.drop(10)))
            assertNotNull(recorder.stop())
        }
        val stream = FfmpegTools.probeStreams(file).single { it["codec_type"] == "audio" }
        assertEquals("${audioFrames(video, audio)}", stream["nb_read_frames"])
        assertEquals(0.0, stream.getValue("start_time").toDouble(), 0.001)
    }

    @Test
    fun aFileWithoutAKeyFrameHasNothingToFinish() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val file = FfmpegTools.file("record-${files.incrementAndGet()}.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val recorder = Mp4Recorder(raf.channel, raf.channel) { error(it) }
            recorder.start()
            recorder.setVideoFormat(video.format)
            video.frames.filterNot { it.isKeyFrame }.forEach(recorder::writeVideo)
            assertNull(recorder.stop())
        }
    }

    @Test
    fun aBacklogOverTheLimitStopsTheRecordingAndKeepsWhatWasWritten() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val written = video.frames.take(31)
        val limit = written.sumOf { it.data.size }
        val file = FfmpegTools.file("record-${files.incrementAndGet()}.mp4")
        val errors = ArrayList<String>()
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val recorder = Mp4Recorder(raf.channel, raf.channel, backlogLimitBytes = limit) { errors += it }
            recorder.start()
            recorder.setVideoFormat(video.format)
            written.forEach(recorder::writeVideo)
            while (recorder.bytesWritten == 0L) Thread.sleep(5)
            recorder.writeVideo(EncodedFrame(ByteArray(limit + 1), written.last().ptsUs + 33_333, false))
            video.frames.drop(31).forEach(recorder::writeVideo)
            assertNotNull(recorder.stop())
        }
        assertEquals(listOf("Storage is too slow"), errors)
        val stream = FfmpegTools.probeStreams(file).single { it["codec_type"] == "video" }
        assertEquals("${written.size}", stream["nb_read_frames"])
    }

    @Test
    fun aRecordingThatCannotBeFinishedIsReportedAndCanBeFinishedLater() {
        val video = ElementaryStreams.h264(repeatHeaders = true)
        val audio = ElementaryStreams.aac()
        val file = FfmpegTools.file("record-${files.incrementAndGet()}.mp4")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val unreadable = RandomAccessFile(file, "r").channel.also { it.close() }
            val recorder = Mp4Recorder(unreadable, raf.channel) { error(it) }
            recorder.start()
            ElementaryStreams.feed(recorder, video, audio)
            assertFailsWith<IOException> { recorder.stop() }
        }
        assertNotNull(finish(file))
        assertDecodes(file, "h264", video.frames.size, audioFrames(video, audio))
    }
}
