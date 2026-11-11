package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.H264_PPS
import com.opirl.app.engine.flv.H264_SPS
import com.opirl.app.engine.flv.HEVC_PPS
import com.opirl.app.engine.flv.HEVC_SPS
import com.opirl.app.engine.flv.HEVC_VPS
import com.opirl.app.engine.flv.forEachNalu
import com.opirl.app.engine.flv.h264NaluType
import com.opirl.app.engine.flv.hevcNaluType
import com.opirl.app.engine.flv.leadingNaluTypes
import com.opirl.app.engine.flv.typeMask

internal const val RTP_HEADER_SIZE = 12
internal const val RTP_MAX_PAYLOAD = 1180
internal const val RTP_PACKET_CAPACITY = RTP_HEADER_SIZE + RTP_MAX_PAYLOAD + 40

private const val H264_STAP_A = 24
private const val H264_FU_A = 28
private const val H264_LAST_VCL = 5
private const val HEVC_AP = 48
private const val HEVC_FU = 49
private const val HEVC_LAST_VCL = 31

internal fun interface PacketSink {
    fun packet(buf: ByteArray, payloadLength: Int, marker: Boolean)
}

internal class NalList {
    var count = 0
        private set
    private var sources = arrayOfNulls<ByteArray>(16)
    private var starts = IntArray(16)
    private var ends = IntArray(16)

    fun clear() {
        for (i in 0 until count) {
            sources[i] = null
        }
        count = 0
    }

    fun add(source: ByteArray, start: Int, end: Int) {
        if (count == sources.size) {
            sources = sources.copyOf(count * 2)
            starts = starts.copyOf(count * 2)
            ends = ends.copyOf(count * 2)
        }
        sources[count] = source
        starts[count] = start
        ends[count] = end
        count++
    }

    fun source(index: Int): ByteArray = sources[index]!!

    fun start(index: Int): Int = starts[index]

    fun size(index: Int): Int = ends[index] - starts[index]
}

internal abstract class VideoPacketizer(protected val maxPayload: Int) {
    private val nals = NalList()
    protected val packet = ByteArray(RTP_HEADER_SIZE + maxPayload + 40)

    protected abstract val lastVclType: Int
    protected abstract val parameterTypeMask: Long

    protected abstract fun typeOf(header: Byte): Int

    protected abstract fun emitUnit(nals: NalList, sink: PacketSink)

    fun packetize(frame: ByteArray, config: ByteArray?, isKeyFrame: Boolean, sink: PacketSink) {
        nals.clear()
        if (isKeyFrame && config != null && config.isNotEmpty()) {
            val present = leadingNaluTypes(frame, lastVclType) { typeOf(it) }
            forEachNalu(config, 0, config.size) { start, end ->
                val type = typeOf(config[start])
                if ((parameterTypeMask shr type) and 1L != 0L && (present shr type) and 1L == 0L) {
                    nals.add(config, start, end)
                }
            }
        }
        forEachNalu(frame, 0, frame.size) { start, end -> nals.add(frame, start, end) }
        if (nals.count > 0) {
            emitUnit(nals, sink)
        }
    }
}

internal class H264Packetizer(maxPayload: Int = RTP_MAX_PAYLOAD) : VideoPacketizer(maxPayload) {
    override val lastVclType: Int = H264_LAST_VCL
    override val parameterTypeMask: Long = typeMask(H264_SPS, H264_PPS)

    override fun typeOf(header: Byte): Int = h264NaluType(header)

    override fun emitUnit(nals: NalList, sink: PacketSink) {
        var i = 0
        val last = nals.count - 1
        while (i < nals.count) {
            val size = nals.size(i)
            if (size > maxPayload) {
                fragment(nals, i, i == last, sink)
                i++
                continue
            }
            var total = 1 + 2 + size
            var j = i + 1
            while (j < nals.count && total + 2 + nals.size(j) <= maxPayload) {
                total += 2 + nals.size(j)
                j++
            }
            if (j == i + 1) {
                System.arraycopy(nals.source(i), nals.start(i), packet, RTP_HEADER_SIZE, size)
                sink.packet(packet, size, i == last)
            } else {
                aggregate(nals, i, j, sink, j - 1 == last)
            }
            i = j
        }
    }

    private fun aggregate(nals: NalList, from: Int, to: Int, sink: PacketSink, marker: Boolean) {
        var position = RTP_HEADER_SIZE + 1
        var forbidden = 0
        var reference = 0
        for (k in from until to) {
            val size = nals.size(k)
            val header = nals.source(k)[nals.start(k)].toInt()
            forbidden = forbidden or (header and 0x80)
            reference = maxOf(reference, header and 0x60)
            writeU16(packet, position, size)
            System.arraycopy(nals.source(k), nals.start(k), packet, position + 2, size)
            position += 2 + size
        }
        packet[RTP_HEADER_SIZE] = (forbidden or reference or H264_STAP_A).toByte()
        sink.packet(packet, position - RTP_HEADER_SIZE, marker)
    }

    private fun fragment(nals: NalList, index: Int, marker: Boolean, sink: PacketSink) {
        val source = nals.source(index)
        val start = nals.start(index)
        val header = source[start].toInt()
        val indicator = (header and 0xE0) or H264_FU_A
        val type = header and 0x1F
        var offset = start + 1
        val end = start + nals.size(index)
        val chunk = maxPayload - 2
        while (offset < end) {
            val length = minOf(chunk, end - offset)
            val first = offset == start + 1
            val finished = offset + length == end
            packet[RTP_HEADER_SIZE] = indicator.toByte()
            packet[RTP_HEADER_SIZE + 1] = ((if (first) 0x80 else 0) or (if (finished) 0x40 else 0) or type).toByte()
            System.arraycopy(source, offset, packet, RTP_HEADER_SIZE + 2, length)
            sink.packet(packet, 2 + length, marker && finished)
            offset += length
        }
    }
}

internal class HevcPacketizer(maxPayload: Int = RTP_MAX_PAYLOAD) : VideoPacketizer(maxPayload) {
    override val lastVclType: Int = HEVC_LAST_VCL
    override val parameterTypeMask: Long = typeMask(HEVC_VPS, HEVC_SPS, HEVC_PPS)

    override fun typeOf(header: Byte): Int = hevcNaluType(header)

    override fun emitUnit(nals: NalList, sink: PacketSink) {
        var i = 0
        val last = nals.count - 1
        while (i < nals.count) {
            val size = nals.size(i)
            if (size < 3) {
                i++
                continue
            }
            if (size > maxPayload) {
                fragment(nals, i, i == last, sink)
                i++
                continue
            }
            var total = 2 + 2 + size
            var j = i + 1
            while (j < nals.count && nals.size(j) >= 3 && total + 2 + nals.size(j) <= maxPayload) {
                total += 2 + nals.size(j)
                j++
            }
            if (j == i + 1) {
                System.arraycopy(nals.source(i), nals.start(i), packet, RTP_HEADER_SIZE, size)
                sink.packet(packet, size, i == last)
            } else {
                aggregate(nals, i, j, sink, j - 1 == last)
            }
            i = j
        }
    }

    private fun aggregate(nals: NalList, from: Int, to: Int, sink: PacketSink, marker: Boolean) {
        var position = RTP_HEADER_SIZE + 2
        var forbidden = 0
        var layer = 63
        var temporal = 7
        for (k in from until to) {
            val size = nals.size(k)
            val source = nals.source(k)
            val start = nals.start(k)
            forbidden = forbidden or (source[start].toInt() and 0x80)
            layer = minOf(layer, ((source[start].toInt() and 1) shl 5) or ((source[start + 1].toInt() and 0xFF) ushr 3))
            temporal = minOf(temporal, source[start + 1].toInt() and 7)
            writeU16(packet, position, size)
            System.arraycopy(source, start, packet, position + 2, size)
            position += 2 + size
        }
        packet[RTP_HEADER_SIZE] = (forbidden or (HEVC_AP shl 1) or (layer ushr 5)).toByte()
        packet[RTP_HEADER_SIZE + 1] = (((layer and 0x1F) shl 3) or temporal).toByte()
        sink.packet(packet, position - RTP_HEADER_SIZE, marker)
    }

    private fun fragment(nals: NalList, index: Int, marker: Boolean, sink: PacketSink) {
        val source = nals.source(index)
        val start = nals.start(index)
        val first = source[start].toInt()
        val second = source[start + 1].toInt()
        val type = (first ushr 1) and 0x3F
        var offset = start + 2
        val end = start + nals.size(index)
        val chunk = maxPayload - 3
        while (offset < end) {
            val length = minOf(chunk, end - offset)
            val begin = offset == start + 2
            val finished = offset + length == end
            packet[RTP_HEADER_SIZE] = ((first and 0x81) or (HEVC_FU shl 1)).toByte()
            packet[RTP_HEADER_SIZE + 1] = second.toByte()
            packet[RTP_HEADER_SIZE + 2] = ((if (begin) 0x80 else 0) or (if (finished) 0x40 else 0) or type).toByte()
            System.arraycopy(source, offset, packet, RTP_HEADER_SIZE + 3, length)
            sink.packet(packet, 3 + length, marker && finished)
            offset += length
        }
    }
}
