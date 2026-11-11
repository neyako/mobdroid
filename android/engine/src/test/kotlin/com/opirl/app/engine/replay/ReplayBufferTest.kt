package com.opirl.app.engine.replay

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplayBufferTest {
    private val format = VideoFormat(VideoCodec.H264, 1280, 720, 30, byteArrayOf(1))
    private val audioFormat = AudioFormat(48_000, 2, byteArrayOf(2))

    private fun fill(buffer: ReplayBuffer, seconds: Int, frameBytes: Int = 100) {
        buffer.setVideoFormat(format)
        buffer.setAudioFormat(audioFormat)
        for (i in 0 until seconds * 30) {
            val ptsUs = i * 1_000_000L / 30
            buffer.writeVideo(EncodedFrame(ByteArray(frameBytes), ptsUs, i % 30 == 0))
            buffer.writeAudio(EncodedFrame(ByteArray(10), ptsUs, true))
        }
    }

    @Test
    fun keepsTheWantedDurationFromAKeyFrame() {
        val buffer = ReplayBuffer(10_000_000, Long.MAX_VALUE)
        fill(buffer, 25)
        val clip = buffer.clip(10_000_000)!!
        assertTrue(clip.video.first().isKeyFrame)
        assertTrue(clip.durationUs in 10_000_000..11_000_000, "${clip.durationUs}")
        assertEquals(clip.video.first().ptsUs, clip.audio.first().ptsUs)
        assertEquals(clip.video.last().ptsUs, clip.audio.last().ptsUs)
    }

    @Test
    fun aShorterClipStartsAtTheKeyFrameBeforeIt() {
        val buffer = ReplayBuffer(10_000_000, Long.MAX_VALUE)
        fill(buffer, 25)
        val clip = buffer.clip(3_500_000)!!
        assertTrue(clip.video.first().isKeyFrame)
        assertTrue(clip.durationUs in 3_500_000..4_500_000, "${clip.durationUs}")
    }

    @Test
    fun theByteLimitDropsTheOldestGroups() {
        val buffer = ReplayBuffer(60_000_000, 100_000)
        fill(buffer, 30, frameBytes = 1000)
        val clip = buffer.clip(60_000_000)!!
        assertTrue(clip.video.first().isKeyFrame)
        assertTrue(clip.video.sumOf { it.data.size } <= 100_000 + 30 * 1000)
        assertEquals((30 * 30 - 1) * 1_000_000L / 30, clip.video.last().ptsUs)
    }

    @Test
    fun aNewVideoConfigStartsOver() {
        val buffer = ReplayBuffer(10_000_000, Long.MAX_VALUE)
        fill(buffer, 5)
        buffer.setVideoFormat(VideoFormat(VideoCodec.H264, 1280, 720, 30, byteArrayOf(9)))
        assertNull(buffer.clip(10_000_000))
    }

    @Test
    fun aClipIsWrittenInTimestampOrder() {
        val buffer = ReplayBuffer(10_000_000, Long.MAX_VALUE)
        fill(buffer, 3)
        val order = ArrayList<Long>()
        var formats = 0
        buffer.clip(2_000_000)!!.writeTo(object : MediaSink {
            override fun setVideoFormat(format: VideoFormat) {
                formats++
            }

            override fun setAudioFormat(format: AudioFormat) {
                formats++
            }

            override fun writeVideo(frame: EncodedFrame) {
                order += frame.ptsUs
            }

            override fun writeAudio(frame: EncodedFrame) {
                order += frame.ptsUs
            }
        })
        assertEquals(2, formats)
        assertEquals(order.sorted(), order)
    }
}
