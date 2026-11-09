package app.mobdroid.engine.ts

import app.mobdroid.engine.flv.AacConfig
import app.mobdroid.engine.flv.Av1Config
import app.mobdroid.engine.flv.ByteWriter
import app.mobdroid.engine.flv.H264_PPS
import app.mobdroid.engine.flv.H264_SPS
import app.mobdroid.engine.flv.HEVC_PPS
import app.mobdroid.engine.flv.HEVC_SPS
import app.mobdroid.engine.flv.HEVC_VPS
import app.mobdroid.engine.flv.OBU_SEQUENCE_HEADER
import app.mobdroid.engine.flv.OBU_TEMPORAL_DELIMITER
import app.mobdroid.engine.flv.annexBParameterSets
import app.mobdroid.engine.flv.containsObuOfType
import app.mobdroid.engine.flv.forEachObu
import app.mobdroid.engine.flv.h264NaluType
import app.mobdroid.engine.flv.hevcNaluType
import app.mobdroid.engine.flv.leadingNaluTypes
import app.mobdroid.engine.flv.typeMask
import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat

private const val PACKETS_PER_OUTPUT = 7
private const val PTS_DELAY_TICKS = 45_000L
private const val TIMELINE_MARGIN_TICKS = 18_000L
private const val PSI_INTERVAL_TICKS = 9_000L
private const val PCR_INTERVAL_TICKS = 1_800L
private const val TIMESTAMP_MASK = 0x1_FFFF_FFFFL
private const val VIDEO_STREAM_ID = 0xE0
private const val PRIVATE_STREAM_1_ID = 0xBD
private const val AUDIO_STREAM_ID = 0xC0
private const val STREAM_TYPE_H264 = 0x1B
private const val STREAM_TYPE_HEVC = 0x24
private const val STREAM_TYPE_PRIVATE_PES = 0x06
private const val STREAM_TYPE_AAC_ADTS = 0x0F
private const val H264_LAST_VCL = 5
private const val HEVC_LAST_VCL = 31
private const val FLAG_RANDOM_ACCESS = 0x40
private const val FLAG_PRIORITY = 0x20
private const val FLAG_PCR = 0x10
private val EMPTY = ByteArray(0)

private class VideoStream(val format: VideoFormat) {
    val codec = format.codec

    val streamType =
        when (codec) {
            VideoCodec.H264 -> STREAM_TYPE_H264
            VideoCodec.HEVC -> STREAM_TYPE_HEVC
            VideoCodec.AV1 -> STREAM_TYPE_PRIVATE_PES
        }

    val streamId = if (codec == VideoCodec.AV1) PRIVATE_STREAM_1_ID else VIDEO_STREAM_ID

    val av1: Av1Config? =
        if (codec == VideoCodec.AV1) runCatching { Av1Config.from(format.config) }.getOrNull() else null

    val parameterSets: ByteArray =
        when (codec) {
            VideoCodec.H264 -> annexBParameterSets(format.config, intArrayOf(H264_SPS, H264_PPS), ::h264NaluType)
            VideoCodec.HEVC ->
                annexBParameterSets(format.config, intArrayOf(HEVC_VPS, HEVC_SPS, HEVC_PPS), ::hevcNaluType)
            VideoCodec.AV1 -> EMPTY
        }

    val requiredTypes: Long =
        when (codec) {
            VideoCodec.H264 -> typeMask(H264_SPS, H264_PPS)
            VideoCodec.HEVC -> typeMask(HEVC_VPS, HEVC_SPS, HEVC_PPS)
            VideoCodec.AV1 -> 0L
        }

    val descriptors: ByteArray = buildDescriptors()

    private fun buildDescriptors(): ByteArray {
        if (codec != VideoCodec.AV1) {
            return EMPTY
        }
        val out = ByteWriter(16)
        out.u8(0x05)
        out.u8(4)
        out.put("AV01".toByteArray(Charsets.US_ASCII))
        av1?.let {
            out.u8(0x80)
            out.u8(4)
            out.put(it.descriptor)
        }
        return out.toByteArray()
    }

    fun lacksParameterSets(data: ByteArray): Boolean {
        if (parameterSets.isEmpty()) {
            return false
        }
        val seen =
            if (codec == VideoCodec.H264) {
                leadingNaluTypes(data, H264_LAST_VCL, ::h264NaluType)
            } else {
                leadingNaluTypes(data, HEVC_LAST_VCL, ::hevcNaluType)
            }
        return seen and requiredTypes != requiredTypes
    }
}

private class AudioStream(format: AudioFormat) {
    val aac = AacConfig.from(format)
}

class MpegTsMuxer(
    private val output: (buffer: ByteArray, offset: Int, length: Int) -> Unit,
) : MediaSink {
    private val packets = ByteArray(TS_PACKET_SIZE * PACKETS_PER_OUTPUT)
    private var packetCount = 0
    private val continuity = IntArray(4)
    private var video: VideoStream? = null
    private var audio: AudioStream? = null
    private var tablesDirty = true
    private var tablesVersion = -1
    private var pat = EMPTY
    private var pmt = EMPTY
    private var baseUs = Long.MIN_VALUE
    private var lastPsiTicks = 0L
    private var lastPcrTicks = Long.MIN_VALUE
    private val pesHeader = ByteArray(19)
    private var pesHeaderSize = 0
    private var prefix = EMPTY
    private var prefixSize = 0
    private var body = EMPTY
    private var bodySize = 0
    private var cursor = 0
    private val adts = ByteArray(AacConfig.ADTS_HEADER_SIZE)
    private val av1Payload = ByteWriter(1 shl 16)

    override fun setVideoFormat(format: VideoFormat) {
        video = VideoStream(format)
        tablesDirty = true
    }

    override fun setAudioFormat(format: AudioFormat) {
        audio = AudioStream(format)
        tablesDirty = true
    }

    override fun writeVideo(frame: EncodedFrame) {
        val stream = video ?: return
        if (frame.data.isEmpty()) {
            return
        }
        val ticks = ticksOf(frame.ptsUs)
        writeTablesIfDue(ticks, frame.isKeyFrame)
        val pcr = pcrFor(true, ticks)
        when (stream.codec) {
            VideoCodec.AV1 -> {
                buildAv1Payload(stream, frame)
                setSegments(EMPTY, 0, av1Payload.array, av1Payload.size)
            }
            else -> {
                if (frame.isKeyFrame && stream.lacksParameterSets(frame.data)) {
                    setSegments(stream.parameterSets, stream.parameterSets.size, frame.data, frame.data.size)
                } else {
                    setSegments(EMPTY, 0, frame.data, frame.data.size)
                }
            }
        }
        val priority = stream.codec == VideoCodec.AV1 && frame.isKeyFrame
        writePes(VIDEO_PID, 2, stream.streamId, ticks, true, frame.isKeyFrame, priority, pcr)
        flush()
    }

    override fun writeAudio(frame: EncodedFrame) {
        val stream = audio ?: return
        if (frame.data.isEmpty() || frame.data.size > AacConfig.MAX_RAW_LENGTH) {
            return
        }
        val ticks = ticksOf(frame.ptsUs)
        writeTablesIfDue(ticks, false)
        val pcr = pcrFor(video == null, ticks)
        stream.aac.writeAdtsHeader(adts, 0, frame.data.size)
        setSegments(adts, adts.size, frame.data, frame.data.size)
        writePes(AUDIO_PID, 3, AUDIO_STREAM_ID, ticks, false, false, false, pcr)
        flush()
    }

    private fun setSegments(prefix: ByteArray, prefixSize: Int, body: ByteArray, bodySize: Int) {
        this.prefix = prefix
        this.prefixSize = prefixSize
        this.body = body
        this.bodySize = bodySize
    }

    private fun ticksOf(ptsUs: Long): Long {
        if (baseUs == Long.MIN_VALUE) {
            baseUs = ptsUs
        }
        return maxOf((ptsUs - baseUs) * 9 / 100 + TIMELINE_MARGIN_TICKS, 0L)
    }

    private fun pcrFor(isPcrStream: Boolean, ticks: Long): Long {
        if (!isPcrStream) {
            return -1
        }
        if (lastPcrTicks != Long.MIN_VALUE && ticks - lastPcrTicks < PCR_INTERVAL_TICKS) {
            return -1
        }
        lastPcrTicks = maxOf(ticks, lastPcrTicks)
        return lastPcrTicks
    }

    private fun writeTablesIfDue(ticks: Long, videoKeyFrame: Boolean) {
        if (tablesDirty) {
            rebuildTables()
        } else if (!videoKeyFrame && ticks - lastPsiTicks < PSI_INTERVAL_TICKS) {
            return
        }
        writeSection(PAT_PID, 0, pat)
        writeSection(PMT_PID, 1, pmt)
        lastPsiTicks = ticks
    }

    private fun rebuildTables() {
        val streams = ArrayList<ElementaryStreamEntry>(2)
        video?.let { streams.add(ElementaryStreamEntry(VIDEO_PID, it.streamType, it.descriptors)) }
        audio?.let { streams.add(ElementaryStreamEntry(AUDIO_PID, STREAM_TYPE_AAC_ADTS, EMPTY)) }
        tablesVersion = (tablesVersion + 1) and 0x1F
        pat = TsTables.pat()
        pmt = TsTables.pmt(tablesVersion, if (video != null) VIDEO_PID else AUDIO_PID, streams)
        tablesDirty = false
    }

    private fun writeSection(pid: Int, continuitySlot: Int, section: ByteArray) {
        val at = nextPacket()
        packets[at] = 0x47
        packets[at + 1] = (0x40 or (pid ushr 8)).toByte()
        packets[at + 2] = pid.toByte()
        packets[at + 3] = (0x10 or nextContinuity(continuitySlot)).toByte()
        packets[at + 4] = 0
        System.arraycopy(section, 0, packets, at + 5, section.size)
        packets.fill(0xFF.toByte(), at + 5 + section.size, at + TS_PACKET_SIZE)
    }

    private fun nextContinuity(slot: Int): Int {
        val value = continuity[slot]
        continuity[slot] = (value + 1) and 0xF
        return value
    }

    private fun nextPacket(): Int {
        if (packetCount == PACKETS_PER_OUTPUT) {
            flush()
        }
        return TS_PACKET_SIZE * packetCount++
    }

    private fun flush() {
        if (packetCount == 0) {
            return
        }
        val length = packetCount * TS_PACKET_SIZE
        packetCount = 0
        output(packets, 0, length)
    }

    private fun buildAv1Payload(stream: VideoStream, frame: EncodedFrame) {
        val out = av1Payload
        out.clear()
        if (frame.isKeyFrame) {
            val sequenceHeader = stream.av1?.sequenceHeaderObu
            if (sequenceHeader != null && !containsObuOfType(frame.data, OBU_SEQUENCE_HEADER)) {
                writeObu(out, sequenceHeader, 0, sequenceHeader.size)
            }
        }
        forEachObu(frame.data, 0, frame.data.size) { type, start, _, end ->
            if (type != OBU_TEMPORAL_DELIMITER) {
                writeObu(out, frame.data, start, end - start)
            }
        }
    }

    private fun writeObu(out: ByteWriter, data: ByteArray, offset: Int, length: Int) {
        out.u24(1)
        out.putEscaped(data, offset, length)
    }

    private fun writePes(
        pid: Int,
        continuitySlot: Int,
        streamId: Int,
        ticks: Long,
        withDts: Boolean,
        randomAccess: Boolean,
        priority: Boolean,
        pcr: Long,
    ) {
        pesHeaderSize = buildPesHeader(streamId, ticks, withDts, prefixSize + bodySize)
        cursor = 0
        var remaining = pesHeaderSize + prefixSize + bodySize
        var first = true
        while (remaining > 0) {
            val at = nextPacket()
            var flags = 0
            if (first) {
                if (randomAccess) flags = flags or FLAG_RANDOM_ACCESS
                if (priority) flags = flags or FLAG_PRIORITY
                if (pcr >= 0) flags = flags or FLAG_PCR
            }
            var adaptationSize = if (flags != 0) 2 + (if (pcr >= 0 && first) 6 else 0) else 0
            var room = TS_PACKET_SIZE - 4 - adaptationSize
            if (remaining < room) {
                adaptationSize += room - remaining
                room = remaining
            }
            packets[at] = 0x47
            packets[at + 1] = (((if (first) 0x40 else 0) or (pid ushr 8))).toByte()
            packets[at + 2] = pid.toByte()
            packets[at + 3] = ((if (adaptationSize > 0) 0x30 else 0x10) or nextContinuity(continuitySlot)).toByte()
            var offset = at + 4
            if (adaptationSize > 0) {
                packets[offset++] = (adaptationSize - 1).toByte()
                if (adaptationSize > 1) {
                    packets[offset++] = flags.toByte()
                    if (flags and FLAG_PCR != 0) {
                        writePcr(packets, offset, pcr)
                        offset += 6
                    }
                    val end = at + 4 + adaptationSize
                    packets.fill(0xFF.toByte(), offset, end)
                    offset = end
                }
            }
            copyPayload(packets, offset, room)
            remaining -= room
            first = false
        }
    }

    private fun writePcr(out: ByteArray, offset: Int, ticks: Long) {
        val base = ticks and TIMESTAMP_MASK
        out[offset] = (base ushr 25).toByte()
        out[offset + 1] = (base ushr 17).toByte()
        out[offset + 2] = (base ushr 9).toByte()
        out[offset + 3] = (base ushr 1).toByte()
        out[offset + 4] = (((base and 1L).toInt() shl 7) or 0x7E).toByte()
        out[offset + 5] = 0
    }

    private fun buildPesHeader(streamId: Int, ticks: Long, withDts: Boolean, payloadSize: Int): Int {
        val timestampBytes = if (withDts) 10 else 5
        val length = 3 + timestampBytes + payloadSize
        pesHeader[0] = 0
        pesHeader[1] = 0
        pesHeader[2] = 1
        pesHeader[3] = streamId.toByte()
        val declared = if (length > 0xFFFF) 0 else length
        pesHeader[4] = (declared ushr 8).toByte()
        pesHeader[5] = declared.toByte()
        pesHeader[6] = 0x84.toByte()
        pesHeader[7] = (if (withDts) 0xC0 else 0x80).toByte()
        pesHeader[8] = timestampBytes.toByte()
        val timestamp = ticks + PTS_DELAY_TICKS
        writeTimestamp(pesHeader, 9, if (withDts) 3 else 2, timestamp)
        if (withDts) {
            writeTimestamp(pesHeader, 14, 1, timestamp)
        }
        return 9 + timestampBytes
    }

    private fun writeTimestamp(out: ByteArray, offset: Int, marker: Int, ticks: Long) {
        val value = ticks and TIMESTAMP_MASK
        out[offset] = ((marker shl 4) or ((value ushr 29).toInt() and 0x0E) or 1).toByte()
        out[offset + 1] = (value ushr 22).toByte()
        out[offset + 2] = (((value ushr 14).toInt() and 0xFE) or 1).toByte()
        out[offset + 3] = (value ushr 7).toByte()
        out[offset + 4] = (((value shl 1).toInt() and 0xFE) or 1).toByte()
    }

    private fun copyPayload(target: ByteArray, targetOffset: Int, count: Int) {
        var remaining = count
        var out = targetOffset
        while (remaining > 0) {
            val copied: Int
            if (cursor < pesHeaderSize) {
                copied = minOf(remaining, pesHeaderSize - cursor)
                System.arraycopy(pesHeader, cursor, target, out, copied)
            } else if (cursor < pesHeaderSize + prefixSize) {
                copied = minOf(remaining, pesHeaderSize + prefixSize - cursor)
                System.arraycopy(prefix, cursor - pesHeaderSize, target, out, copied)
            } else {
                copied = remaining
                System.arraycopy(body, cursor - pesHeaderSize - prefixSize, target, out, copied)
            }
            cursor += copied
            out += copied
            remaining -= copied
        }
    }
}
