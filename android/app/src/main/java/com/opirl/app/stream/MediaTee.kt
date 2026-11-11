package com.opirl.app.stream

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoFormat

class MediaTee : MediaSink {
    private val lock = Any()
    private var videoFormat: VideoFormat? = null
    private var audioFormat: AudioFormat? = null

    @Volatile
    private var streams = emptyArray<MediaSink>()

    @Volatile
    private var recorder: MediaSink? = null

    @Volatile
    private var recorderVideo = false

    fun setStreams(sinks: List<MediaSink>) = synchronized(lock) {
        for (sink in sinks) {
            if (streams.any { it === sink }) continue
            videoFormat?.let(sink::setVideoFormat)
            audioFormat?.let(sink::setAudioFormat)
        }
        streams = sinks.toTypedArray()
    }

    fun setRecorder(sink: MediaSink?, video: Boolean) = synchronized(lock) {
        if (sink != null) {
            if (video) videoFormat?.let(sink::setVideoFormat)
            audioFormat?.let(sink::setAudioFormat)
        }
        recorderVideo = video
        recorder = sink
    }

    fun forgetVideoFormat() = synchronized(lock) { videoFormat = null }

    fun forgetAudioFormat() = synchronized(lock) { audioFormat = null }

    override fun setVideoFormat(format: VideoFormat): Unit = synchronized(lock) {
        videoFormat = format
        for (sink in streams) sink.setVideoFormat(format)
        if (recorderVideo) recorder?.setVideoFormat(format)
    }

    override fun setAudioFormat(format: AudioFormat): Unit = synchronized(lock) {
        audioFormat = format
        for (sink in streams) sink.setAudioFormat(format)
        recorder?.setAudioFormat(format)
    }

    override fun writeVideo(frame: EncodedFrame) {
        for (sink in streams) sink.writeVideo(frame)
        if (recorderVideo) recorder?.writeVideo(frame)
    }

    override fun writeAudio(frame: EncodedFrame) {
        for (sink in streams) sink.writeAudio(frame)
        recorder?.writeAudio(frame)
    }
}

class VideoOnlySink(private val sink: MediaSink) : MediaSink {
    override fun setVideoFormat(format: VideoFormat) = sink.setVideoFormat(format)

    override fun setAudioFormat(format: AudioFormat) = Unit

    override fun writeVideo(frame: EncodedFrame) = sink.writeVideo(frame)

    override fun writeAudio(frame: EncodedFrame) = Unit
}

class AudioOnlySink(private val sink: MediaSink) : MediaSink {
    override fun setVideoFormat(format: VideoFormat) = Unit

    override fun setAudioFormat(format: AudioFormat) = sink.setAudioFormat(format)

    override fun writeVideo(frame: EncodedFrame) = Unit

    override fun writeAudio(frame: EncodedFrame) = sink.writeAudio(frame)
}
