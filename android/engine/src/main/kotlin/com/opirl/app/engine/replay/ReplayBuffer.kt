package com.opirl.app.engine.replay

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoFormat

class ReplayClip(
    val videoFormat: VideoFormat,
    val audioFormat: AudioFormat?,
    val video: List<EncodedFrame>,
    val audio: List<EncodedFrame>,
) {
    val durationUs: Long get() = video.last().ptsUs - video.first().ptsUs

    fun writeTo(sink: MediaSink) {
        sink.setVideoFormat(videoFormat)
        audioFormat?.let(sink::setAudioFormat)
        var a = 0
        for (frame in video) {
            while (a < audio.size && audio[a].ptsUs <= frame.ptsUs) sink.writeAudio(audio[a++])
            sink.writeVideo(frame)
        }
        while (a < audio.size) sink.writeAudio(audio[a++])
    }
}

class ReplayBuffer(private val maxDurationUs: Long, private val maxBytes: Long) : MediaSink {
    private val video = ArrayDeque<EncodedFrame>()
    private val audio = ArrayDeque<EncodedFrame>()
    private var videoFormat: VideoFormat? = null
    private var audioFormat: AudioFormat? = null
    private var bytes = 0L
    private var keyFrames = 0

    @Synchronized
    override fun setVideoFormat(format: VideoFormat) {
        val current = videoFormat
        if (current != null && (current.codec != format.codec || !current.config.contentEquals(format.config))) clear()
        videoFormat = format
    }

    @Synchronized
    override fun setAudioFormat(format: AudioFormat) {
        val current = audioFormat
        if (current != null && (current.codec != format.codec || !current.config.contentEquals(format.config))) clear()
        audioFormat = format
    }

    @Synchronized
    override fun writeVideo(frame: EncodedFrame) {
        if (video.isEmpty() && !frame.isKeyFrame) return
        video.addLast(frame)
        bytes += frame.data.size
        if (frame.isKeyFrame) {
            keyFrames++
            trim(frame.ptsUs)
        }
    }

    @Synchronized
    override fun writeAudio(frame: EncodedFrame) {
        if (video.isEmpty()) return
        audio.addLast(frame)
        bytes += frame.data.size
    }

    @Synchronized
    fun clear() {
        video.clear()
        audio.clear()
        bytes = 0
        keyFrames = 0
    }

    @Synchronized
    fun clip(durationUs: Long): ReplayClip? {
        val format = videoFormat ?: return null
        if (video.isEmpty()) return null
        val startUs = video.last().ptsUs - durationUs
        var first = 0
        for ((index, frame) in video.withIndex()) {
            if (!frame.isKeyFrame) continue
            if (frame.ptsUs > startUs && index > 0) break
            first = index
        }
        val frames = video.subList(first, video.size).toList()
        if (frames.size < 2) return null
        val fromUs = frames.first().ptsUs
        return ReplayClip(format, audioFormat, frames, audio.filter { it.ptsUs >= fromUs })
    }

    private fun trim(nowUs: Long) {
        while (keyFrames > 1 && (bytes > maxBytes || nowUs - secondKeyFrameUs() >= maxDurationUs)) {
            bytes -= video.removeFirst().data.size
            while (!video.first().isKeyFrame) bytes -= video.removeFirst().data.size
            keyFrames--
        }
        val fromUs = video.first().ptsUs
        while (audio.isNotEmpty() && audio.first().ptsUs < fromUs) bytes -= audio.removeFirst().data.size
    }

    private fun secondKeyFrameUs(): Long {
        for (index in 1 until video.size) if (video[index].isKeyFrame) return video[index].ptsUs
        return Long.MAX_VALUE
    }
}
