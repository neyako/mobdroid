package com.opirl.app.media

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink

class AudioEncoder(
    bitrateBps: Int,
    private val sink: MediaSink?,
    device: AudioDeviceInfo?,
    private val mix: AudioMix,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val sampleRate = 48_000
    private val channels = 2
    private val frameBytes = 1024 * channels * 2

    @Volatile
    var muted = false

    @Volatile
    private var running = true

    private val codec: MediaCodec? = if (sink == null) null else MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec ->
        try {
            codec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    private val record = try {
        createRecord()
    } catch (e: Exception) {
        codec?.release()
        throw e
    }

    @SuppressLint("MissingPermission")
    private fun createRecord() = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        .setAudioFormat(
            AndroidAudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AndroidAudioFormat.CHANNEL_IN_STEREO)
                .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                .build(),
        )
        .setBufferSizeInBytes(
            maxOf(
                AudioRecord.getMinBufferSize(
                    sampleRate,
                    AndroidAudioFormat.CHANNEL_IN_STEREO,
                    AndroidAudioFormat.ENCODING_PCM_16BIT,
                ),
                frameBytes,
            ) * 4,
        )
        .build()

    private val thread = Thread(::run, "AudioEncoder")

    init {
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            if (device != null) record.setPreferredDevice(device)
            codec?.start()
            record.startRecording()
            thread.start()
        } catch (e: Exception) {
            record.release()
            codec?.release()
            throw e
        }
    }

    fun release() {
        running = false
        thread.join(2000)
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val pcm = ByteArray(frameBytes)
        val timestamp = AudioTimestamp()
        val info = MediaCodec.BufferInfo()
        var framesRead = 0L
        var anchorFrames = 0L
        var anchorNanos = System.nanoTime()
        var lastAnchorUpdate = 0L
        var chunkPeak = 0f
        var shownDb = AUDIO_LEVEL_FLOOR_DB
        try {
            while (running) {
                var filled = 0
                while (filled < frameBytes && running) {
                    val n = record.read(pcm, filled, frameBytes - filled)
                    if (n < 0) throw IllegalStateException("AudioRecord read error $n")
                    filled += n
                }
                if (!running) break
                if (framesRead - lastAnchorUpdate >= sampleRate || lastAnchorUpdate == 0L) {
                    if (record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        anchorFrames = timestamp.framePosition
                        anchorNanos = timestamp.nanoTime
                    } else if (lastAnchorUpdate == 0L) {
                        anchorFrames = 0
                        anchorNanos = System.nanoTime() - 1024L * 1_000_000_000L / sampleRate
                    }
                    lastAnchorUpdate = maxOf(framesRead, 1)
                }
                val ptsUs = (anchorNanos + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate) / 1000
                framesRead += 1024
                mix.apply(pcm, muted)
                chunkPeak = maxOf(chunkPeak, peak(pcm))
                if (framesRead % (1024 * 5) == 0L) {
                    shownDb = maxOf(decibels(chunkPeak), shownDb - 6f)
                    chunkPeak = 0f
                    onLevel(shownDb)
                }
                if (codec == null) continue
                val inIndex = codec.dequeueInputBuffer(20_000)
                if (inIndex >= 0) {
                    codec.getInputBuffer(inIndex)!!.apply {
                        clear()
                        put(pcm, 0, frameBytes)
                    }
                    codec.queueInputBuffer(inIndex, 0, frameBytes, ptsUs, 0)
                }
                drain(codec, info)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio failed", e)
            if (running) onError("Audio: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec?.stop() }
            codec?.release()
            onLevel(AUDIO_LEVEL_FLOOR_DB)
        }
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    sink!!.setAudioFormat(AudioFormat(sampleRate, channels, data))
                } else {
                    sink!!.writeAudio(EncodedFrame(data, info.presentationTimeUs, true))
                }
            }
            codec.releaseOutputBuffer(index, false)
        }
    }

    private fun peak(pcm: ByteArray): Float {
        var max = 0
        var i = 0
        while (i < pcm.size) {
            val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8)
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 8
        }
        return max / 32768f
    }

    private companion object {
        const val TAG = "AudioEncoder"
    }
}

const val AUDIO_LEVEL_FLOOR_DB = -60f

fun decibels(peak: Float): Float =
    if (peak <= 0.001f) AUDIO_LEVEL_FLOOR_DB else (20f * kotlin.math.log10(peak)).coerceIn(AUDIO_LEVEL_FLOOR_DB, 0f)

