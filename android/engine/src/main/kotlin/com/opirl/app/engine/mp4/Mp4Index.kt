package com.opirl.app.engine.mp4

import com.opirl.app.engine.flv.ByteWriter
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat

internal const val BLOCK_MAGIC = 0x6D647262
internal const val BLOCK_HEADER_SIZE = 16
internal const val MAX_RECORDS_LENGTH = 16 shl 20
internal const val FILE_HEADER_SIZE = 28
internal const val MDAT_HEADER_SIZE = 16
internal const val BOX_FTYP = 0x66747970
internal const val BOX_MDAT = 0x6D646174

private const val RECORD_VIDEO_FORMAT = 1
private const val RECORD_AUDIO_FORMAT = 2
private const val RECORD_VIDEO = 3
private const val RECORD_VIDEO_KEY = 4
private const val RECORD_AUDIO = 5
private const val SAMPLE_RECORD_SIZE = 13

internal fun writeFileHeader(out: ByteWriter) {
    out.u32(FILE_HEADER_SIZE)
    out.u32(BOX_FTYP)
    out.u32(0x69736F6D)
    out.u32(0x200)
    out.u32(0x69736F6D)
    out.u32(0x69736F32)
    out.u32(0x6D703431)
    out.u32(1)
    out.u32(BOX_MDAT)
    out.u64(MDAT_HEADER_SIZE.toLong())
}

internal fun writeVideoFormatRecord(out: ByteWriter, format: VideoFormat) {
    out.u8(RECORD_VIDEO_FORMAT)
    out.u8(format.codec.ordinal)
    out.u16(format.width)
    out.u16(format.height)
    out.u16(format.fps)
    out.u16(format.config.size)
    out.put(format.config)
}

internal fun writeAudioFormatRecord(out: ByteWriter, format: AudioFormat) {
    out.u8(RECORD_AUDIO_FORMAT)
    out.u32(format.sampleRate)
    out.u8(format.channels)
    out.u16(format.config.size)
    out.put(format.config)
}

internal fun writeVideoRecord(out: ByteWriter, size: Int, ptsUs: Long, keyFrame: Boolean) {
    out.u8(if (keyFrame) RECORD_VIDEO_KEY else RECORD_VIDEO)
    out.u32(size)
    out.u64(ptsUs)
}

internal fun writeAudioRecord(out: ByteWriter, size: Int, ptsUs: Long) {
    out.u8(RECORD_AUDIO)
    out.u32(size)
    out.u64(ptsUs)
}

internal class Mp4Track {
    var count = 0
        private set
    var sizes = IntArray(1024)
        private set
    var pts = LongArray(1024)
        private set
    var keyCount = 0
        private set
    var keys = IntArray(64)
        private set
    var chunkCount = 0
        private set
    var chunkOffsets = LongArray(256)
        private set
    var chunkSamples = IntArray(256)
        private set
    var bytes = 0L
        private set

    fun add(size: Int, ptsUs: Long, keyFrame: Boolean, offset: Long, newChunk: Boolean) {
        if (count == sizes.size) {
            sizes = sizes.copyOf(count * 2)
            pts = pts.copyOf(count * 2)
        }
        sizes[count] = size
        pts[count] = ptsUs
        count++
        bytes += size
        if (keyFrame) {
            if (keyCount == keys.size) keys = keys.copyOf(keyCount * 2)
            keys[keyCount++] = count
        }
        if (newChunk) {
            if (chunkCount == chunkOffsets.size) {
                chunkOffsets = chunkOffsets.copyOf(chunkCount * 2)
                chunkSamples = chunkSamples.copyOf(chunkCount * 2)
            }
            chunkOffsets[chunkCount] = offset
            chunkSamples[chunkCount] = 0
            chunkCount++
        }
        chunkSamples[chunkCount - 1]++
    }
}

internal class Mp4Index {
    val video = Mp4Track()
    val audio = Mp4Track()
    var videoFormat: VideoFormat? = null
        private set
    var audioFormat: AudioFormat? = null
        private set

    fun addBlock(records: ByteArray, length: Int, dataOffset: Long, dataLength: Long): Boolean {
        if (!isValid(records, length, dataLength)) return false
        var at = 0
        var offset = dataOffset
        var previous: Mp4Track? = null
        while (at < length) {
            val tag = records[at].toInt()
            when (tag) {
                RECORD_VIDEO_FORMAT -> {
                    val size = u16(records, at + 8)
                    if (videoFormat == null) {
                        videoFormat = VideoFormat(
                            VideoCodec.entries[records[at + 1].toInt()],
                            u16(records, at + 2),
                            u16(records, at + 4),
                            u16(records, at + 6),
                            records.copyOfRange(at + 10, at + 10 + size),
                        )
                    }
                    at += 10 + size
                }
                RECORD_AUDIO_FORMAT -> {
                    val size = u16(records, at + 6)
                    if (audioFormat == null) {
                        audioFormat = AudioFormat(
                            u32(records, at + 1).toInt(),
                            records[at + 5].toInt(),
                            records.copyOfRange(at + 8, at + 8 + size),
                        )
                    }
                    at += 8 + size
                }
                else -> {
                    val size = u32(records, at + 1).toInt()
                    val ptsUs = (u32(records, at + 5) shl 32) or u32(records, at + 9)
                    val track = if (tag == RECORD_AUDIO) audio else video
                    track.add(size, ptsUs, tag == RECORD_VIDEO_KEY, offset, track !== previous)
                    previous = track
                    offset += size
                    at += SAMPLE_RECORD_SIZE
                }
            }
        }
        return true
    }

    private fun isValid(records: ByteArray, length: Int, dataLength: Long): Boolean {
        var at = 0
        var bytes = 0L
        while (at < length) {
            when (records[at].toInt()) {
                RECORD_VIDEO_FORMAT -> {
                    if (at + 10 > length || records[at + 1].toInt() !in VideoCodec.entries.indices) return false
                    at += 10 + u16(records, at + 8)
                }
                RECORD_AUDIO_FORMAT -> {
                    if (at + 8 > length) return false
                    at += 8 + u16(records, at + 6)
                }
                RECORD_VIDEO, RECORD_VIDEO_KEY, RECORD_AUDIO -> {
                    if (at + SAMPLE_RECORD_SIZE > length) return false
                    bytes += u32(records, at + 1)
                    at += SAMPLE_RECORD_SIZE
                }
                else -> return false
            }
        }
        return at == length && bytes == dataLength
    }

    private fun u16(data: ByteArray, at: Int): Int = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, at: Int): Long = (u16(data, at).toLong() shl 16) or u16(data, at + 2).toLong()
}
