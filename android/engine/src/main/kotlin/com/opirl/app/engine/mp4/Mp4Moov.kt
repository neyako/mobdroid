package com.opirl.app.engine.mp4

import com.opirl.app.engine.flv.Av1Config
import com.opirl.app.engine.flv.AvcConfig
import com.opirl.app.engine.flv.ByteWriter
import com.opirl.app.engine.flv.HevcConfig
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat
import kotlin.math.abs

private const val MOVIE_TIMESCALE = 1000
private const val VIDEO_TIMESCALE = 90_000
private const val AAC_FRAME_SAMPLES = 1024

private class Timing(val deltas: IntArray, val duration: Long)

internal class Moov(val bytes: ByteWriter, val durationMs: Long)

private inline fun ByteWriter.box(type: String, content: ByteWriter.() -> Unit) {
    val at = reserve(4)
    for (c in type) u8(c.code)
    content()
    setU32(at, size - at)
}

private inline fun ByteWriter.fullBox(type: String, version: Int, flags: Int, content: ByteWriter.() -> Unit) = box(type) {
    u32((version shl 24) or flags)
    content()
}

private fun ByteWriter.matrix() {
    u32(0x10000)
    fill(0, 12)
    u32(0x10000)
    fill(0, 12)
    u32(0x40000000)
}

private fun timing(track: Mp4Track, timescale: Int, nominal: Int, fallback: Int): Timing {
    val deltas = IntArray(track.count)
    var time = 0L
    for (i in 0 until track.count - 1) {
        val target = ((track.pts[i + 1] - track.pts[0]) * timescale + 500_000) / 1_000_000
        val delta = if (nominal > 0 && abs(target - time - nominal) <= nominal / 2) {
            nominal.toLong()
        } else {
            (target - time).coerceIn(1, Int.MAX_VALUE.toLong())
        }
        deltas[i] = delta.toInt()
        time += delta
    }
    val last = when {
        nominal > 0 -> nominal
        track.count > 1 -> deltas[track.count - 2]
        else -> fallback
    }
    deltas[track.count - 1] = last
    return Timing(deltas, time + last)
}

internal fun buildMoov(index: Mp4Index, nowMs: Long): Moov {
    val videoFormat = requireNotNull(index.videoFormat) { "No video format" }
    val audioFormat = index.audioFormat?.takeIf { index.audio.count > 0 }
    val video = index.video
    val audio = index.audio
    val startUs = if (audioFormat != null) minOf(video.pts[0], audio.pts[0]) else video.pts[0]
    val videoTiming = timing(video, VIDEO_TIMESCALE, 0, VIDEO_TIMESCALE / videoFormat.fps.coerceAtLeast(1))
    val audioTiming = audioFormat?.let { timing(audio, it.sampleRate, AAC_FRAME_SAMPLES, AAC_FRAME_SAMPLES) }
    val videoOffset = (video.pts[0] - startUs) / 1000
    val audioOffset = if (audioFormat != null) (audio.pts[0] - startUs) / 1000 else 0
    val videoLength = videoTiming.duration * MOVIE_TIMESCALE / VIDEO_TIMESCALE
    val audioLength = if (audioFormat != null) audioTiming!!.duration * MOVIE_TIMESCALE / audioFormat.sampleRate else 0
    val length = maxOf(videoOffset + videoLength, audioOffset + audioLength)
    val created = nowMs / 1000 + 2_082_844_800L
    val out = ByteWriter(1 shl 16)
    out.box("moov") {
        fullBox("mvhd", 0, 0) {
            u32(created.toInt())
            u32(created.toInt())
            u32(MOVIE_TIMESCALE)
            u32(length.toInt())
            u32(0x10000)
            u16(0x100)
            fill(0, 10)
            matrix()
            fill(0, 24)
            u32(3)
        }
        track(1, created, videoOffset, videoLength, VIDEO_TIMESCALE, videoTiming, video, "vide", videoFormat.width, videoFormat.height) {
            videoEntry(videoFormat)
        }
        if (audioFormat != null) {
            track(2, created, audioOffset, audioLength, audioFormat.sampleRate, audioTiming!!, audio, "soun", 0, 0) {
                audioEntry(audioFormat, audio.bytes * 8 * audioFormat.sampleRate / audioTiming.duration.coerceAtLeast(1))
            }
        }
    }
    return Moov(out, length)
}

private inline fun ByteWriter.track(
    id: Int,
    created: Long,
    offset: Long,
    length: Long,
    timescale: Int,
    timing: Timing,
    samples: Mp4Track,
    handler: String,
    width: Int,
    height: Int,
    entry: ByteWriter.() -> Unit,
) = box("trak") {
    val isVideo = handler == "vide"
    fullBox("tkhd", 0, 3) {
        u32(created.toInt())
        u32(created.toInt())
        u32(id)
        u32(0)
        u32((offset + length).toInt())
        fill(0, 12)
        u16(if (isVideo) 0 else 0x100)
        u16(0)
        matrix()
        u32(width shl 16)
        u32(height shl 16)
    }
    if (offset > 0) {
        box("edts") {
            fullBox("elst", 0, 0) {
                u32(2)
                u32(offset.toInt())
                u32(-1)
                u32(0x10000)
                u32(length.toInt())
                u32(0)
                u32(0x10000)
            }
        }
    }
    box("mdia") {
        val wide = timing.duration > 0xFFFFFFFFL
        fullBox("mdhd", if (wide) 1 else 0, 0) {
            if (wide) {
                u64(created)
                u64(created)
                u32(timescale)
                u64(timing.duration)
            } else {
                u32(created.toInt())
                u32(created.toInt())
                u32(timescale)
                u32(timing.duration.toInt())
            }
            u16(0x55C4)
            u16(0)
        }
        fullBox("hdlr", 0, 0) {
            u32(0)
            for (c in handler) u8(c.code)
            fill(0, 12)
            for (c in if (isVideo) "VideoHandler" else "SoundHandler") u8(c.code)
            u8(0)
        }
        box("minf") {
            if (isVideo) {
                fullBox("vmhd", 0, 1) { fill(0, 8) }
            } else {
                fullBox("smhd", 0, 0) { fill(0, 4) }
            }
            box("dinf") {
                fullBox("dref", 0, 0) {
                    u32(1)
                    fullBox("url ", 0, 1) {}
                }
            }
            box("stbl") {
                fullBox("stsd", 0, 0) {
                    u32(1)
                    entry()
                }
                sampleTables(samples, timing, isVideo)
            }
        }
    }
}

private fun ByteWriter.sampleTables(samples: Mp4Track, timing: Timing, isVideo: Boolean) {
    fullBox("stts", 0, 0) {
        val countAt = reserve(4)
        var entries = 0
        var run = 0
        for (i in 0 until samples.count) {
            run++
            if (i == samples.count - 1 || timing.deltas[i + 1] != timing.deltas[i]) {
                u32(run)
                u32(timing.deltas[i])
                entries++
                run = 0
            }
        }
        setU32(countAt, entries)
    }
    if (isVideo) {
        fullBox("stss", 0, 0) {
            u32(samples.keyCount)
            for (i in 0 until samples.keyCount) u32(samples.keys[i])
        }
    }
    fullBox("stsc", 0, 0) {
        val countAt = reserve(4)
        var entries = 0
        for (i in 0 until samples.chunkCount) {
            if (i == 0 || samples.chunkSamples[i] != samples.chunkSamples[i - 1]) {
                u32(i + 1)
                u32(samples.chunkSamples[i])
                u32(1)
                entries++
            }
        }
        setU32(countAt, entries)
    }
    fullBox("stsz", 0, 0) {
        u32(0)
        u32(samples.count)
        for (i in 0 until samples.count) u32(samples.sizes[i])
    }
    fullBox("co64", 0, 0) {
        u32(samples.chunkCount)
        for (i in 0 until samples.chunkCount) u64(samples.chunkOffsets[i])
    }
}

private fun ByteWriter.videoEntry(format: VideoFormat) {
    val (type, configType, record) = when (format.codec) {
        VideoCodec.H264 -> Triple("avc1", "avcC", AvcConfig.record(format.config))
        VideoCodec.HEVC -> Triple("hvc1", "hvcC", HevcConfig.record(format.config))
        VideoCodec.AV1 -> Triple("av01", "av1C", Av1Config.from(format.config).record)
    }
    box(type) {
        fill(0, 6)
        u16(1)
        fill(0, 16)
        u16(format.width)
        u16(format.height)
        u32(0x480000)
        u32(0x480000)
        u32(0)
        u16(1)
        fill(0, 32)
        u16(0x18)
        u16(0xFFFF)
        box(configType) { put(record) }
    }
}

private fun ByteWriter.audioEntry(format: AudioFormat, bitrate: Long) {
    val config = format.config
    require(config.size in 2..64) { "Unsupported AAC config" }
    box("mp4a") {
        fill(0, 6)
        u16(1)
        fill(0, 8)
        u16(format.channels)
        u16(16)
        u32(0)
        u32(format.sampleRate shl 16)
        fullBox("esds", 0, 0) {
            u8(3)
            u8(3 + 2 + 13 + 2 + config.size + 3)
            u16(0)
            u8(0)
            u8(4)
            u8(13 + 2 + config.size)
            u8(0x40)
            u8(0x15)
            u24(0)
            u32(bitrate.toInt())
            u32(bitrate.toInt())
            u8(5)
            u8(config.size)
            put(config)
            u8(6)
            u8(1)
            u8(2)
        }
    }
}
