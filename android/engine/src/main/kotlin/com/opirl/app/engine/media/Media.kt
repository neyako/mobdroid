package com.opirl.app.engine.media

enum class VideoCodec { H264, HEVC, AV1 }

class VideoFormat(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val config: ByteArray,
)

class AudioFormat(
    val sampleRate: Int,
    val channels: Int,
    val config: ByteArray,
)

class EncodedFrame(
    val data: ByteArray,
    val ptsUs: Long,
    val isKeyFrame: Boolean,
)

interface MediaSink {
    fun setVideoFormat(format: VideoFormat)

    fun setAudioFormat(format: AudioFormat)

    fun writeVideo(frame: EncodedFrame)

    fun writeAudio(frame: EncodedFrame)
}
