package com.opirl.app.stream

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaTeeTest {
    private class Sink : MediaSink {
        val calls = ArrayList<String>()

        override fun setVideoFormat(format: VideoFormat) {
            calls += "video format"
        }

        override fun setAudioFormat(format: AudioFormat) {
            calls += "audio format"
        }

        override fun writeVideo(frame: EncodedFrame) {
            calls += "video"
        }

        override fun writeAudio(frame: EncodedFrame) {
            calls += "audio"
        }
    }

    private fun runningTee() = MediaTee().apply {
        setVideoFormat(VideoFormat(VideoCodec.H264, 1920, 1080, 30, ByteArray(0)))
        setAudioFormat(AudioFormat(48000, 2, ByteArray(0)))
    }

    private fun MediaTee.writeOneOfEach() {
        writeVideo(EncodedFrame(ByteArray(1), 0, true))
        writeAudio(EncodedFrame(ByteArray(1), 0, true))
    }

    @Test
    fun aRecorderAttachedMidStreamGetsTheFormatsBeforeItsFirstFrame() {
        val tee = runningTee()
        val recorder = Sink()
        tee.setRecorder(recorder, video = true)
        tee.writeOneOfEach()
        assertEquals(listOf("video format", "audio format", "video", "audio"), recorder.calls)
    }

    @Test
    fun aRecorderWithItsOwnVideoEncoderGetsOnlyAudio() {
        val tee = runningTee()
        val recorder = Sink()
        tee.setRecorder(recorder, video = false)
        tee.writeOneOfEach()
        assertEquals(listOf("audio format", "audio"), recorder.calls)
    }

    @Test
    fun aStreamAttachedWhileRecordingGetsTheFormatsBeforeItsFirstFrame() {
        val tee = runningTee()
        val stream = Sink()
        tee.setStream(stream)
        tee.writeOneOfEach()
        assertEquals(listOf("video format", "audio format", "video", "audio"), stream.calls)
    }
}
