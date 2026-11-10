package app.mobdroid.stream

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoFormat

class MediaTee : MediaSink {
    private val lock = Any()
    private var videoFormat: VideoFormat? = null
    private var audioFormat: AudioFormat? = null

    @Volatile
    private var stream: MediaSink? = null

    @Volatile
    private var recorder: MediaSink? = null

    @Volatile
    private var recorderVideo = false

    fun setStream(sink: MediaSink?) = synchronized(lock) {
        if (sink != null) {
            videoFormat?.let(sink::setVideoFormat)
            audioFormat?.let(sink::setAudioFormat)
        }
        stream = sink
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
        stream?.setVideoFormat(format)
        if (recorderVideo) recorder?.setVideoFormat(format)
    }

    override fun setAudioFormat(format: AudioFormat): Unit = synchronized(lock) {
        audioFormat = format
        stream?.setAudioFormat(format)
        recorder?.setAudioFormat(format)
    }

    override fun writeVideo(frame: EncodedFrame) {
        stream?.writeVideo(frame)
        if (recorderVideo) recorder?.writeVideo(frame)
    }

    override fun writeAudio(frame: EncodedFrame) {
        stream?.writeAudio(frame)
        recorder?.writeAudio(frame)
    }
}

class VideoOnlySink(private val sink: MediaSink) : MediaSink {
    override fun setVideoFormat(format: VideoFormat) = sink.setVideoFormat(format)

    override fun setAudioFormat(format: AudioFormat) = Unit

    override fun writeVideo(frame: EncodedFrame) = sink.writeVideo(frame)

    override fun writeAudio(frame: EncodedFrame) = Unit
}
