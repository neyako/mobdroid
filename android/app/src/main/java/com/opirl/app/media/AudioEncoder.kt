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
import com.opirl.app.engine.media.AudioCodec
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink

class MicInput(val device: AudioDeviceInfo?, val source: Int = MediaRecorder.AudioSource.CAMCORDER)

class AudioEncoder(
    private val bitrateBps: Int,
    private val sink: MediaSink?,
    input: MicInput,
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
    var input: MicInput = input

    @Volatile
    var opusSink: MediaSink? = null

    @Volatile
    private var running = true

    private val codec: MediaCodec? = if (sink == null) null else createCodec(MediaFormat.MIMETYPE_AUDIO_AAC)

    private fun createCodec(mime: String): MediaCodec = MediaCodec.createEncoderByType(mime).also { codec ->
        try {
            codec.configure(
                MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    }
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, frameBytes)
                },
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(input: MicInput): AudioRecord {
        val record = AudioRecord.Builder()
            .setAudioSource(input.source)
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
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            if (input.device != null) record.setPreferredDevice(input.device)
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            throw e
        }
        return record
    }

    private var record: AudioRecord = try {
        openRecord(input)
    } catch (e: Exception) {
        codec?.release()
        throw e
    }
    private var opened = input
    private val thread = Thread(::run, "AudioEncoder").apply { start() }

    fun release() {
        running = false
        thread.join(2000)
    }

    private fun reopen(wanted: MicInput) {
        runCatching { record.stop() }
        record.release()
        record = try {
            openRecord(wanted)
        } catch (e: Exception) {
            Log.w(TAG, "Microphone ${wanted.device?.productName} failed, using the default", e)
            openRecord(MicInput(null))
        }
        opened = wanted
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
        var lastPtsUs = 0L
        var chunks = 0
        var chunkPeak = 0f
        var shownDb = AUDIO_LEVEL_FLOOR_DB
        var opus: MediaCodec? = null
        var opusTarget: MediaSink? = null
        try {
            while (running) {
                val wanted = input
                if (wanted !== opened) {
                    reopen(wanted)
                    framesRead = 0
                    lastAnchorUpdate = 0
                }
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
                val ptsUs = maxOf(
                    (anchorNanos + (framesRead - anchorFrames) * 1_000_000_000L / sampleRate) / 1000,
                    lastPtsUs + 1,
                )
                lastPtsUs = ptsUs
                framesRead += 1024
                mix.apply(pcm, muted)
                chunkPeak = maxOf(chunkPeak, peak(pcm))
                if (++chunks % 5 == 0) {
                    shownDb = maxOf(decibels(chunkPeak), shownDb - 6f)
                    chunkPeak = 0f
                    onLevel(shownDb)
                }
                val wantedOpus = opusSink
                if (wantedOpus !== opusTarget) {
                    opus?.let {
                        runCatching { it.stop() }
                        it.release()
                    }
                    opus = null
                    opusTarget = wantedOpus
                    if (wantedOpus != null) opus = createCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                }
                opus?.let { encode(it, info, pcm, ptsUs, opusTarget!!, AudioCodec.OPUS) }
                if (codec != null) encode(codec, info, pcm, ptsUs, sink!!, AudioCodec.AAC)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio failed", e)
            if (running) onError("Audio: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
            runCatching { codec?.stop() }
            codec?.release()
            runCatching { opus?.stop() }
            opus?.release()
            onLevel(AUDIO_LEVEL_FLOOR_DB)
        }
    }

    private fun encode(codec: MediaCodec, info: MediaCodec.BufferInfo, pcm: ByteArray, ptsUs: Long, sink: MediaSink, kind: AudioCodec) {
        val inIndex = codec.dequeueInputBuffer(20_000)
        if (inIndex >= 0) {
            codec.getInputBuffer(inIndex)!!.apply {
                clear()
                put(pcm, 0, frameBytes)
            }
            codec.queueInputBuffer(inIndex, 0, frameBytes, ptsUs, 0)
        }
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data, 0, info.size)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    sink.setAudioFormat(AudioFormat(sampleRate, channels, data, kind))
                } else {
                    sink.writeAudio(EncodedFrame(data, info.presentationTimeUs, true))
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

