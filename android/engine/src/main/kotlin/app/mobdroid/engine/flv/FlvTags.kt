package app.mobdroid.engine.flv

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat

internal const val FOURCC_HEVC = 0x68766331
internal const val FOURCC_AV1 = 0x61763031

private const val AAC_TAG_HEADER = 0xAF
private const val EX_HEADER = 0x80
private const val PACKET_SEQUENCE_START = 0
private const val PACKET_CODED_FRAMES = 1
private const val PACKET_CODED_FRAMES_X = 3
private const val FRAME_KEY = 1
private const val FRAME_INTER = 2

internal class FlvVideoTags(format: VideoFormat) {
    val codec = format.codec

    val fourCc: Int =
        when (format.codec) {
            VideoCodec.H264 -> 0
            VideoCodec.HEVC -> FOURCC_HEVC
            VideoCodec.AV1 -> FOURCC_AV1
        }

    val fourCcName: String? =
        when (format.codec) {
            VideoCodec.H264 -> null
            VideoCodec.HEVC -> "hvc1"
            VideoCodec.AV1 -> "av01"
        }

    private val av1: Av1Config?
    private val record: ByteArray

    init {
        try {
            av1 = if (format.codec == VideoCodec.AV1) Av1Config.from(format.config) else null
            record =
                when (format.codec) {
                    VideoCodec.H264 -> AvcConfig.record(format.config)
                    VideoCodec.HEVC -> HevcConfig.record(format.config)
                    VideoCodec.AV1 -> requireNotNull(av1).record
                }
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid ${format.codec} config: ${error.message}", error)
        }
    }

    fun writeSequenceHeader(out: ByteWriter) {
        when (codec) {
            VideoCodec.H264 -> {
                out.u8((FRAME_KEY shl 4) or LEGACY_AVC_CODEC_ID)
                out.u8(0)
                out.u24(0)
            }
            else -> {
                out.u8(EX_HEADER or (FRAME_KEY shl 4) or PACKET_SEQUENCE_START)
                out.u32(fourCc)
            }
        }
        out.put(record)
    }

    fun writeFrame(out: ByteWriter, frame: EncodedFrame) {
        val frameType = if (frame.isKeyFrame) FRAME_KEY else FRAME_INTER
        when (codec) {
            VideoCodec.H264 -> {
                out.u8((frameType shl 4) or LEGACY_AVC_CODEC_ID)
                out.u8(1)
                out.u24(0)
                annexBToLengthPrefixed(frame.data, 0, frame.data.size, out)
            }
            VideoCodec.HEVC -> {
                out.u8(EX_HEADER or (frameType shl 4) or PACKET_CODED_FRAMES_X)
                out.u32(FOURCC_HEVC)
                annexBToLengthPrefixed(frame.data, 0, frame.data.size, out)
            }
            VideoCodec.AV1 -> {
                out.u8(EX_HEADER or (frameType shl 4) or PACKET_CODED_FRAMES)
                out.u32(FOURCC_AV1)
                val sequenceHeader = av1?.sequenceHeaderObu
                if (frame.isKeyFrame && sequenceHeader != null && !containsObuOfType(frame.data, OBU_SEQUENCE_HEADER)) {
                    out.put(sequenceHeader)
                }
                forEachObu(frame.data, 0, frame.data.size) { type, start, payloadStart, end ->
                    if (type != OBU_TEMPORAL_DELIMITER) {
                        writeObuWithSizeField(out, frame.data, start, payloadStart, end)
                    }
                }
            }
        }
    }

    private companion object {
        const val LEGACY_AVC_CODEC_ID = 7
    }
}

internal class FlvAudioTags(format: AudioFormat) {
    private val config = format.config.copyOf()

    fun writeSequenceHeader(out: ByteWriter) {
        out.u8(AAC_TAG_HEADER)
        out.u8(0)
        out.put(config)
    }

    fun writeFrame(out: ByteWriter, frame: EncodedFrame) {
        out.u8(AAC_TAG_HEADER)
        out.u8(1)
        out.put(frame.data)
    }
}
