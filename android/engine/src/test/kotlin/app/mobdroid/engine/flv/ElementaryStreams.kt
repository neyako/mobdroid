package app.mobdroid.engine.flv

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GeneratedVideo(val format: VideoFormat, val frames: List<EncodedFrame>) {
    val width get() = format.width
    val height get() = format.height
}

class GeneratedAudio(val format: AudioFormat, val frames: List<EncodedFrame>)

object ElementaryStreams {
    const val WIDTH = 320
    const val HEIGHT = 240
    const val FPS = 30
    const val VIDEO_FRAMES = 60
    private const val AUDIO_SECONDS = 2

    private val cache = ConcurrentHashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> cached(key: String, create: () -> T): T = cache.getOrPut(key, create) as T

    fun h264(repeatHeaders: Boolean): GeneratedVideo = cached("h264-$repeatHeaders") {
        val raw = encode(
            "h264.annexb",
            listOf("-c:v", "libx264", "-preset", "ultrafast", "-bf", "0", "-g", "30", "-sc_threshold", "0"),
            "h264",
        )
        annexBVideo(VideoCodec.H264, raw, repeatHeaders)
    }

    fun hevc(repeatHeaders: Boolean): GeneratedVideo = cached("hevc-$repeatHeaders") {
        val raw = encode(
            "hevc.annexb",
            listOf(
                "-c:v", "libx265", "-preset", "ultrafast", "-x265-params",
                "bframes=0:keyint=30:min-keyint=30:scenecut=0:repeat-headers=1:log-level=error",
            ),
            "hevc",
        )
        annexBVideo(VideoCodec.HEVC, raw, repeatHeaders)
    }

    fun av1(mediaCodecStyle: Boolean): GeneratedVideo = cached("av1-$mediaCodecStyle") {
        val raw = encode(
            "av1.obu",
            listOf("-c:v", "libsvtav1", "-preset", "12", "-g", "30", "-svtav1-params", "pred-struct=1:lookahead=0:scd=0"),
            "obu",
        )
        av1Video(raw, mediaCodecStyle)
    }

    fun aac(): GeneratedAudio = cached("aac") {
        val output = FfmpegTools.file("aac.adts")
        val result = FfmpegTools.run(
            listOf(
                "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "$AUDIO_SECONDS", "-ac", "2", "-c:a", "aac", "-b:a", "96k", "-f", "adts", output.path,
            ),
            60,
        )
        check(result.exitCode == 0) { "ffmpeg failed: ${result.output}" }
        adtsAudio(output.readBytes())
    }

    fun feed(sink: MediaSink, video: GeneratedVideo?, audio: GeneratedAudio?, frameCount: Int = Int.MAX_VALUE) {
        video?.let { sink.setVideoFormat(it.format) }
        audio?.let { sink.setAudioFormat(it.format) }
        val videoFrames = video?.frames?.take(frameCount).orEmpty()
        val endUs = videoFrames.lastOrNull()?.ptsUs ?: Long.MAX_VALUE
        val audioFrames = audio?.frames.orEmpty().filter { video == null || it.ptsUs <= endUs }
        var v = 0
        var a = 0
        while (v < videoFrames.size || a < audioFrames.size) {
            val takeVideo = a >= audioFrames.size || (v < videoFrames.size && videoFrames[v].ptsUs <= audioFrames[a].ptsUs)
            if (takeVideo) sink.writeVideo(videoFrames[v++]) else sink.writeAudio(audioFrames[a++])
        }
    }

    private fun encode(name: String, codecArguments: List<String>, format: String): ByteArray {
        val output = FfmpegTools.file(name)
        val command = mutableListOf(
            "ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=${WIDTH}x$HEIGHT:rate=$FPS",
            "-frames:v", "$VIDEO_FRAMES",
        )
        command.addAll(codecArguments)
        command.addAll(listOf("-pix_fmt", "yuv420p", "-f", format, output.path))
        val result = FfmpegTools.run(command, 120)
        check(result.exitCode == 0) { "ffmpeg failed: ${result.output}" }
        return output.readBytes()
    }

    private fun ptsUs(index: Int): Long = index * 1_000_000L / FPS

    private class Nalu(val type: Int, val bytes: ByteArray)

    private fun naluTypeOf(codec: VideoCodec, header: Byte): Int =
        if (codec == VideoCodec.H264) header.toInt() and 0x1F else (header.toInt() shr 1) and 0x3F

    private fun splitNalus(data: ByteArray, codec: VideoCodec): List<Nalu> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
            while (end > start && data[end - 1].toInt() == 0) end--
            Nalu(naluTypeOf(codec, data[start]), data.copyOfRange(start, end))
        }
    }

    private fun isVcl(codec: VideoCodec, type: Int) = if (codec == VideoCodec.H264) type in 1..5 else type in 0..31

    private fun startsPicture(codec: VideoCodec, nalu: Nalu): Boolean =
        if (codec == VideoCodec.H264) {
            (nalu.bytes[1].toInt() and 0x80) != 0
        } else {
            (nalu.bytes[2].toInt() and 0x80) != 0
        }

    private fun isKey(codec: VideoCodec, type: Int) = if (codec == VideoCodec.H264) type == 5 else type in 16..21

    private fun annexBVideo(codec: VideoCodec, raw: ByteArray, repeatHeaders: Boolean): GeneratedVideo {
        val nalus = splitNalus(raw, codec)
        val units = ArrayList<MutableList<Nalu>>()
        var previousWasVcl = false
        for (nalu in nalus) {
            val vcl = isVcl(codec, nalu.type)
            val startsUnit = units.isEmpty() || (previousWasVcl && (!vcl || startsPicture(codec, nalu)))
            if (startsUnit) {
                units.add(ArrayList())
            }
            units.last().add(nalu)
            previousWasVcl = vcl
        }
        val parameterTypes = if (codec == VideoCodec.H264) setOf(7, 8) else setOf(32, 33, 34)
        val config = ByteArrayOutputStream()
        val seenTypes = HashSet<Int>()
        for (unit in units) {
            for (nalu in unit) {
                if (nalu.type in parameterTypes && seenTypes.add(nalu.type)) {
                    config.write(byteArrayOf(0, 0, 0, 1))
                    config.write(nalu.bytes)
                }
            }
        }
        val frames = units.mapIndexed { index, unit ->
            val out = ByteArrayOutputStream()
            for (nalu in unit) {
                if (!repeatHeaders && nalu.type in parameterTypes) continue
                out.write(byteArrayOf(0, 0, 0, 1))
                out.write(nalu.bytes)
            }
            EncodedFrame(out.toByteArray(), ptsUs(index), unit.any { isKey(codec, it.type) })
        }
        return GeneratedVideo(VideoFormat(codec, WIDTH, HEIGHT, FPS, config.toByteArray()), frames)
    }

    private class Obu(val type: Int, val bytes: ByteArray)

    private fun splitObus(data: ByteArray): List<Obu> {
        val result = ArrayList<Obu>()
        var i = 0
        while (i < data.size) {
            val header = data[i].toInt()
            val type = (header shr 3) and 0xF
            var p = i + 1 + ((header shr 2) and 1)
            var size = 0
            var shift = 0
            while (true) {
                val b = data[p++].toInt() and 0xFF
                size = size or ((b and 0x7F) shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val end = p + size
            result.add(Obu(type, data.copyOfRange(i, end)))
            i = end
        }
        return result
    }

    private fun av1Video(raw: ByteArray, mediaCodecStyle: Boolean): GeneratedVideo {
        val units = ArrayList<MutableList<Obu>>()
        for (obu in splitObus(raw)) {
            if (obu.type == 2) units.add(ArrayList())
            units.last().add(obu)
        }
        val sequenceHeader = units.first().first { it.type == 1 }.bytes
        val frames = units.mapIndexed { index, unit ->
            val out = ByteArrayOutputStream()
            for (obu in unit) {
                if (mediaCodecStyle && (obu.type == 2 || obu.type == 1)) continue
                out.write(obu.bytes)
            }
            EncodedFrame(out.toByteArray(), ptsUs(index), unit.any { it.type == 1 })
        }
        return GeneratedVideo(VideoFormat(VideoCodec.AV1, WIDTH, HEIGHT, FPS, sequenceHeader), frames)
    }

    private fun adtsAudio(raw: ByteArray): GeneratedAudio {
        val frames = ArrayList<EncodedFrame>()
        var i = 0
        var config = ByteArray(0)
        var sampleRate = 48000
        var channels = 2
        val rates = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)
        while (i + 7 <= raw.size) {
            val length = ((raw[i + 3].toInt() and 3) shl 11) or ((raw[i + 4].toInt() and 0xFF) shl 3) or
                ((raw[i + 5].toInt() and 0xFF) ushr 5)
            if (config.isEmpty()) {
                val objectType = ((raw[i + 2].toInt() and 0xFF) ushr 6) + 1
                val rateIndex = (raw[i + 2].toInt() ushr 2) and 0xF
                channels = ((raw[i + 2].toInt() and 1) shl 2) or ((raw[i + 3].toInt() and 0xFF) ushr 6)
                sampleRate = rates[rateIndex]
                val value = (objectType shl 11) or (rateIndex shl 7) or (channels shl 3)
                config = byteArrayOf((value shr 8).toByte(), value.toByte())
            }
            val index = frames.size
            frames.add(EncodedFrame(raw.copyOfRange(i + 7, i + length), index * 1024L * 1_000_000L / sampleRate, true))
            i += length
        }
        return GeneratedAudio(AudioFormat(sampleRate, channels, config), frames)
    }

    fun temporaryFile(name: String): File = FfmpegTools.file(name)
}
