package com.opirl.app.media

import com.opirl.app.R
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import android.view.Surface
import com.opirl.app.engine.audio.NO_AUDIO
import com.opirl.app.engine.audio.PCM_RATE
import com.opirl.app.engine.audio.PcmConverter
import com.opirl.app.engine.audio.PcmRing
import java.nio.ByteOrder
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class VideoState(
    val index: Int = 0,
    val name: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
)

class VideoFile(val uri: Uri, val name: String)

class AudioMix {
    @Volatile
    var source: VideoPlayer? = null

    fun apply(pcm: ByteArray, muted: Boolean) {
        val video = source
        if (muted || video?.silencesMic == true) Arrays.fill(pcm, 0)
        video?.mix(pcm)
    }
}

class VideoPlayer(
    private val context: Context,
    private val files: List<VideoFile>,
    private val surface: Surface,
    private val onFormat: (width: Int, height: Int, rotation: Int) -> Unit,
    private val onIdle: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val _state = MutableStateFlow(VideoState(name = files.first().name))
    val state: StateFlow<VideoState> = _state.asStateFlow()

    @Volatile
    var volume = 1f

    @Volatile
    var micWhilePlaying = true

    private val ring = PcmRing(PCM_RATE)
    private val commands = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private var running = true

    @Volatile
    private var clockRunning = false

    @Volatile
    private var baseUs = 0L

    @Volatile
    private var startNs = 0L

    @Volatile
    private var slewUs = 0L
    private var playing = true
    private var seekRequestUs = -1L
    private var skip = 0
    private var connected = false
    private val thread = Thread(::run, "VideoPlayer").apply { start() }

    val silencesMic: Boolean get() = !micWhilePlaying && clockRunning

    fun mix(pcm: ByteArray) {
        if (!clockRunning) return
        val deviationUs = ring.mixInto(pcm, positionUs(), volume)
        if (deviationUs != NO_AUDIO) slewUs += deviationUs / 16
    }

    fun togglePlay() {
        commands += {
            playing = !playing
            if (!playing) stopClock()
            _state.update { it.copy(playing = playing) }
        }
    }

    fun seekTo(positionMs: Long) {
        _state.update { it.copy(positionMs = positionMs) }
        commands += { seekRequestUs = positionMs * 1000 }
    }

    fun next() {
        commands += { skip = 1 }
    }

    fun previous() {
        commands += { skip = -1 }
    }

    fun release(): Boolean {
        running = false
        thread.interrupt()
        thread.join(1000)
        return !thread.isAlive
    }

    fun awaitStopped() = thread.join()

    private fun positionUs(): Long = if (clockRunning) baseUs + slewUs + (System.nanoTime() - startNs) / 1000 else baseUs

    private fun stopClock() {
        val position = positionUs()
        clockRunning = false
        baseUs = position
    }

    private fun startClock(now: Long) {
        slewUs = 0
        startNs = now
        clockRunning = true
    }

    private fun run() {
        var index = 0
        var failures = 0
        while (running) {
            val file = files[index]
            val step = try {
                play(file, index).also { failures = 0 }
            } catch (e: Exception) {
                if (!running) break
                Log.e(TAG, "Cannot play ${file.name}", e)
                onError(context.getString(R.string.error_cannot_play, file.name))
                failures++
                pause(if (failures >= files.size) 3000 else 300)
                1
            }
            index = (index + step + files.size) % files.size
        }
        clockRunning = false
        surface.release()
    }

    private fun pause(ms: Long) {
        val until = System.nanoTime() + ms * 1_000_000
        while (running && System.nanoTime() < until) {
            onIdle()
            try {
                Thread.sleep(50)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun track(extractor: MediaExtractor, kind: String): Int =
        (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true
        } ?: -1

    private fun decoder(extractor: MediaExtractor, track: Int, output: Surface?): MediaCodec {
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(format, output, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        return codec
    }

    private fun feed(codec: MediaCodec, extractor: MediaExtractor): Boolean? {
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return null
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    private fun play(file: VideoFile, index: Int): Int {
        val video = MediaExtractor()
        val audio = MediaExtractor()
        var videoCodec: MediaCodec? = null
        var audioCodec: MediaCodec? = null
        try {
            video.setDataSource(context, file.uri, null)
            val videoTrack = track(video, "video/")
            check(videoTrack >= 0) { "No video track" }
            val format = video.getTrackFormat(videoTrack)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0
            var attempt = 0
            while (videoCodec == null) {
                try {
                    videoCodec = decoder(video, videoTrack, surface)
                } catch (e: Exception) {
                    if (connected || ++attempt == 10 || !running) throw e
                    Thread.sleep(200)
                }
            }
            connected = true
            audio.setDataSource(context, file.uri, null)
            val audioTrack = track(audio, "audio/")
            if (audioTrack >= 0) {
                audioCodec = runCatching { decoder(audio, audioTrack, null) }
                    .onFailure { Log.w(TAG, "No audio decoder for ${file.name}", it) }
                    .getOrNull()
            }
            onFormat(
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT),
                if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0,
            )
            ring.clear()
            clockRunning = false
            baseUs = 0
            skip = 0
            seekRequestUs = -1
            val info = MediaCodec.BufferInfo()
            var converter: PcmConverter? = null
            var channels = 2
            var converted = ShortArray(0)
            var pendingIndex = -1
            var pendingPtsUs = 0L
            var seekTargetUs = 0L
            var prerolled = false
            var showOne = true
            var videoInputDone = false
            var videoDone = false
            var audioInputDone = false
            var audioDone = audioCodec == null
            var lastRenderNs = System.nanoTime()
            var lastStateNs = 0L
            while (running) {
                while (true) (commands.poll() ?: break)()
                if (skip != 0) return skip
                if (seekRequestUs >= 0) {
                    val target = if (durationUs > 0) seekRequestUs.coerceIn(0, durationUs) else seekRequestUs
                    seekRequestUs = -1
                    clockRunning = false
                    video.seekTo(target, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    videoCodec.flush()
                    if (audioCodec != null) {
                        audio.seekTo(target, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        audioCodec.flush()
                    }
                    ring.clear()
                    converter?.reset()
                    baseUs = target
                    seekTargetUs = target
                    pendingIndex = -1
                    prerolled = false
                    showOne = true
                    videoInputDone = false
                    videoDone = false
                    audioInputDone = false
                    audioDone = audioCodec == null
                }
                var worked = false
                if (!videoInputDone) {
                    feed(videoCodec, video)?.let {
                        videoInputDone = it
                        worked = true
                    }
                }
                if (pendingIndex < 0 && !videoDone) {
                    val output = videoCodec.dequeueOutputBuffer(info, 0)
                    if (output >= 0) {
                        worked = true
                        val last = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if ((last && info.size == 0) || info.presentationTimeUs < seekTargetUs) {
                            videoCodec.releaseOutputBuffer(output, false)
                        } else {
                            pendingIndex = output
                            pendingPtsUs = info.presentationTimeUs
                        }
                        videoDone = last
                    }
                }
                val now = System.nanoTime()
                if (pendingIndex >= 0 && !prerolled) {
                    prerolled = true
                    baseUs = pendingPtsUs
                }
                if (playing && !clockRunning && prerolled) startClock(now)
                if (pendingIndex >= 0) {
                    val lateUs = positionUs() - pendingPtsUs
                    if (showOne || (clockRunning && lateUs >= 0)) {
                        if (!showOne && lateUs > 150_000) {
                            videoCodec.releaseOutputBuffer(pendingIndex, false)
                        } else {
                            videoCodec.releaseOutputBuffer(
                                pendingIndex,
                                if (clockRunning) startNs + (pendingPtsUs - baseUs - slewUs) * 1000 else now,
                            )
                            lastRenderNs = now
                        }
                        showOne = false
                        pendingIndex = -1
                        worked = true
                    }
                }
                if (audioCodec != null && !audioDone && ring.bufferedUs() < 300_000) {
                    if (!audioInputDone) {
                        feed(audioCodec, audio)?.let {
                            audioInputDone = it
                            worked = true
                        }
                    }
                    val output = audioCodec.dequeueOutputBuffer(info, 0)
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val pcm = audioCodec.outputFormat
                        channels = pcm.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        converter = PcmConverter(pcm.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels)
                    } else if (output >= 0) {
                        worked = true
                        val active = converter
                        audioDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (info.size > 0 && active != null && info.presentationTimeUs + 50_000 >= seekTargetUs) {
                            val buffer = audioCodec.getOutputBuffer(output)!!
                            buffer.position(info.offset).limit(info.offset + info.size)
                            val frames = info.size / 2 / channels
                            if (converted.size < active.maxFrames(frames) * 2) converted = ShortArray(active.maxFrames(frames) * 2)
                            val written = active.convert(buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer(), frames, converted)
                            ring.write(converted, written, info.presentationTimeUs)
                        }
                        audioCodec.releaseOutputBuffer(output, false)
                    }
                }
                if (videoDone && pendingIndex < 0 && (!prerolled || durationUs <= 0 || positionUs() >= durationUs - 20_000)) return 1
                if (now - lastRenderNs >= 50_000_000) {
                    onIdle()
                    lastRenderNs = now
                }
                if (now - lastStateNs >= 250_000_000) {
                    lastStateNs = now
                    val position = positionUs() / 1000
                    _state.update { VideoState(index, file.name, position, durationUs / 1000, playing) }
                }
                if (!worked) Thread.sleep(3)
            }
            return 0
        } finally {
            clockRunning = false
            ring.clear()
            runCatching { videoCodec?.stop() }
            videoCodec?.release()
            runCatching { audioCodec?.stop() }
            audioCodec?.release()
            video.release()
            audio.release()
        }
    }

    private companion object {
        const val TAG = "VideoPlayer"
    }
}
