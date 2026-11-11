package app.mobdroid.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VideoEncoder(
    private val codec: VideoCodec,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitrateBps: Int,
    private val keyFrameIntervalSeconds: Int,
    private val sink: MediaSink,
    private val onError: (String) -> Unit,
) {
    private val info = CodecCatalog.encoders[codec] ?: error("No hardware ${codec.label} encoder")
    private val mediaCodec = MediaCodec.createByCodecName(info.name)
    private val thread = HandlerThread("VideoEncoder").apply { start() }
    private val handler = Handler(thread.looper)
    val inputSurface: Surface

    @Volatile
    var lastOutputNanos = System.nanoTime()
        private set

    @Volatile
    private var released = false
    private var lastKeyFrameUs = 0L

    init {
        val format = MediaFormat.createVideoFormat(codec.mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyFrameIntervalSeconds)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 500_000L)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (info.supportsCbr) {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                } else {
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                },
            )
            when (codec) {
                VideoCodec.H264 -> {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel42)
                }
                VideoCodec.HEVC -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                VideoCodec.AV1 -> setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8)
            }
        }
        var surface: Surface? = null
        try {
            mediaCodec.setCallback(Callback(), handler)
            try {
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "Configure with profile failed, retrying without", e)
                format.removeKey(MediaFormat.KEY_PROFILE)
                format.removeKey(MediaFormat.KEY_LEVEL)
                mediaCodec.reset()
                mediaCodec.setCallback(Callback(), handler)
                mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            surface = mediaCodec.createInputSurface()
            mediaCodec.start()
        } catch (e: Exception) {
            released = true
            surface?.release()
            mediaCodec.release()
            thread.quitSafely()
            throw e
        }
        inputSurface = surface
    }

    fun setBitrate(bps: Int) = handler.post {
        if (!released) {
            runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
        }
    }

    fun requestKeyFrame() = handler.post {
        if (!released) {
            runCatching { mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
    }

    fun release(timeoutMs: Long = 2000): Boolean {
        val done = CountDownLatch(1)
        val posted = handler.post {
            if (!released) {
                released = true
                runCatching { mediaCodec.stop() }
                runCatching { mediaCodec.release() }
                inputSurface.release()
            }
            thread.quitSafely()
            done.countDown()
        }
        return !posted || Thread.currentThread() == thread || done.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private inner class Callback : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    sink.setVideoFormat(VideoFormat(this@VideoEncoder.codec, width, height, fps, data))
                } else {
                    lastOutputNanos = System.nanoTime()
                    val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (keyFrame) {
                        lastKeyFrameUs = info.presentationTimeUs
                    } else if (info.presentationTimeUs - lastKeyFrameUs > keyFrameIntervalSeconds * 1_250_000L) {
                        lastKeyFrameUs = info.presentationTimeUs
                        requestKeyFrame()
                    }
                    sink.writeVideo(EncodedFrame(data, info.presentationTimeUs, keyFrame))
                }
            }
            codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error", e)
            onError("Video encoder: ${e.diagnosticInfo}")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (this@VideoEncoder.codec == VideoCodec.AV1) {
                val csd = format.getByteBuffer("csd-0") ?: return
                val data = ByteArray(csd.remaining()).also { csd.duplicate().get(it) }
                sink.setVideoFormat(VideoFormat(VideoCodec.AV1, width, height, fps, data))
            }
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
