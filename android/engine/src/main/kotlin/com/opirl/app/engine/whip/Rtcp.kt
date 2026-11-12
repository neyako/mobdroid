package com.opirl.app.engine.whip

private const val RTCP_SR = 200
private const val RTCP_RR = 201
private const val RTCP_SDES = 202
private const val RTCP_BYE = 203
private const val RTCP_RTPFB = 205
private const val RTCP_PSFB = 206
private const val FMT_NACK = 1
private const val FMT_PLI = 1
private const val FMT_FIR = 4
private const val FMT_APP_FEEDBACK = 15
private const val REMB_ID = 0x52454D42

internal class ReportBlock(
    val ssrc: Int,
    val fractionLost: Int,
    val cumulativeLost: Int,
    val extendedHighestSequence: Long,
    val jitter: Long,
    val lastSenderReport: Long,
    val delaySinceLastSenderReport: Long,
)

internal sealed class RtcpPacket {
    class SenderReport(
        val ssrc: Int,
        val ntp: Long,
        val rtpTimestamp: Int,
        val packetCount: Long,
        val octetCount: Long,
        val blocks: List<ReportBlock>,
    ) : RtcpPacket()

    class ReceiverReport(val ssrc: Int, val blocks: List<ReportBlock>) : RtcpPacket()

    class Nack(val senderSsrc: Int, val mediaSsrc: Int, val sequences: IntArray) : RtcpPacket()

    class Pli(val senderSsrc: Int, val mediaSsrc: Int) : RtcpPacket()

    class Fir(val senderSsrc: Int, val mediaSsrcs: IntArray) : RtcpPacket()

    class Remb(val senderSsrc: Int, val bitrateBps: Long, val ssrcs: IntArray) : RtcpPacket()

    class TransportFeedback(val senderSsrc: Int, val mediaSsrc: Int, val payload: ByteArray) : RtcpPacket()

    class Bye(val ssrcs: IntArray) : RtcpPacket()
}

internal fun parseRtcp(buf: ByteArray, offset: Int, length: Int): List<RtcpPacket> {
    val result = ArrayList<RtcpPacket>(2)
    var position = offset
    val end = offset + length
    while (position + 4 <= end) {
        if ((buf[position].toInt() and 0xC0) != 0x80) {
            break
        }
        val count = buf[position].toInt() and 0x1F
        val type = buf[position + 1].toInt() and 0xFF
        val size = (readU16(buf, position + 2) + 1) * 4
        if (position + size > end) {
            break
        }
        val body = position + 4
        val bodyEnd = position + size
        when (type) {
            RTCP_SR -> parseSenderReport(buf, body, bodyEnd, count)?.let { result.add(it) }
            RTCP_RR -> parseReceiverReport(buf, body, bodyEnd, count)?.let { result.add(it) }
            RTCP_BYE -> result.add(RtcpPacket.Bye(IntArray(minOf(count, (bodyEnd - body) / 4)) { readU32(buf, body + it * 4).toInt() }))
            RTCP_RTPFB -> parseTransportFeedback(buf, body, bodyEnd, count)?.let { result.add(it) }
            RTCP_PSFB -> parsePayloadFeedback(buf, body, bodyEnd, count)?.let { result.add(it) }
        }
        position += size
    }
    return result
}

private fun parseBlocks(buf: ByteArray, start: Int, end: Int, count: Int): List<ReportBlock> {
    val blocks = ArrayList<ReportBlock>(count)
    var position = start
    for (i in 0 until count) {
        if (position + 24 > end) {
            break
        }
        val lost = (readU32(buf, position + 4) and 0xFFFFFF).toInt()
        blocks.add(
            ReportBlock(
                ssrc = readU32(buf, position).toInt(),
                fractionLost = buf[position + 4].toInt() and 0xFF,
                cumulativeLost = if (lost and 0x800000 != 0) lost or -0x1000000 else lost,
                extendedHighestSequence = readU32(buf, position + 8),
                jitter = readU32(buf, position + 12),
                lastSenderReport = readU32(buf, position + 16),
                delaySinceLastSenderReport = readU32(buf, position + 20),
            ),
        )
        position += 24
    }
    return blocks
}

private fun parseSenderReport(buf: ByteArray, body: Int, end: Int, count: Int): RtcpPacket? {
    if (body + 24 > end) {
        return null
    }
    return RtcpPacket.SenderReport(
        ssrc = readU32(buf, body).toInt(),
        ntp = readU64(buf, body + 4),
        rtpTimestamp = readU32(buf, body + 12).toInt(),
        packetCount = readU32(buf, body + 16),
        octetCount = readU32(buf, body + 20),
        blocks = parseBlocks(buf, body + 24, end, count),
    )
}

private fun parseReceiverReport(buf: ByteArray, body: Int, end: Int, count: Int): RtcpPacket? {
    if (body + 4 > end) {
        return null
    }
    return RtcpPacket.ReceiverReport(readU32(buf, body).toInt(), parseBlocks(buf, body + 4, end, count))
}

private fun parseTransportFeedback(buf: ByteArray, body: Int, end: Int, format: Int): RtcpPacket? {
    if (body + 8 > end) {
        return null
    }
    val sender = readU32(buf, body).toInt()
    val media = readU32(buf, body + 4).toInt()
    return when (format) {
        FMT_NACK -> {
            val sequences = ArrayList<Int>()
            var position = body + 8
            while (position + 4 <= end) {
                val pid = readU16(buf, position)
                val mask = readU16(buf, position + 2)
                sequences.add(pid)
                for (bit in 0 until 16) {
                    if (mask and (1 shl bit) != 0) {
                        sequences.add((pid + bit + 1) and 0xFFFF)
                    }
                }
                position += 4
            }
            RtcpPacket.Nack(sender, media, sequences.toIntArray())
        }
        else -> RtcpPacket.TransportFeedback(sender, media, buf.copyOfRange(body + 8, end))
    }
}

private fun parsePayloadFeedback(buf: ByteArray, body: Int, end: Int, format: Int): RtcpPacket? {
    if (body + 8 > end) {
        return null
    }
    val sender = readU32(buf, body).toInt()
    val media = readU32(buf, body + 4).toInt()
    return when (format) {
        FMT_PLI -> RtcpPacket.Pli(sender, media)
        FMT_FIR -> {
            val ssrcs = ArrayList<Int>()
            var position = body + 8
            while (position + 8 <= end) {
                ssrcs.add(readU32(buf, position).toInt())
                position += 8
            }
            RtcpPacket.Fir(sender, ssrcs.toIntArray())
        }
        FMT_APP_FEEDBACK -> parseRemb(buf, body, end, sender)
        else -> null
    }
}

private fun parseRemb(buf: ByteArray, body: Int, end: Int, sender: Int): RtcpPacket? {
    val fci = body + 8
    if (fci + 8 > end || readU32(buf, fci).toInt() != REMB_ID) {
        return null
    }
    val count = buf[fci + 4].toInt() and 0xFF
    val exponent = (buf[fci + 5].toInt() and 0xFF) ushr 2
    val mantissa = ((buf[fci + 5].toLong() and 3) shl 16) or ((buf[fci + 6].toLong() and 0xFF) shl 8) or (buf[fci + 7].toLong() and 0xFF)
    val bitrate = if (exponent >= 46) Long.MAX_VALUE else mantissa shl exponent
    val ssrcs = IntArray(minOf(count, (end - fci - 8) / 4)) { readU32(buf, fci + 8 + it * 4).toInt() }
    return RtcpPacket.Remb(sender, bitrate, ssrcs)
}

internal class RtcpCompound(val buf: ByteArray) {
    var length = 0
        private set

    fun clear() {
        length = 0
    }

    fun senderReport(ssrc: Int, ntp: Long, rtpTimestamp: Int, packetCount: Long, octetCount: Long) {
        header(0, RTCP_SR, 6)
        writeU32(buf, length, ssrc)
        writeU64(buf, ntp, length + 4)
        writeU32(buf, length + 12, rtpTimestamp)
        writeU32(buf, length + 16, packetCount.toInt())
        writeU32(buf, length + 20, octetCount.toInt())
        length += 24
    }

    fun receiverReport(ssrc: Int) {
        header(0, RTCP_RR, 1)
        writeU32(buf, length, ssrc)
        length += 4
    }

    fun sdes(ssrcs: IntArray, cname: ByteArray) {
        val chunk = (4 + 2 + cname.size + 1 + 3) and 3.inv()
        header(ssrcs.size, RTCP_SDES, ssrcs.size * chunk / 4)
        for (ssrc in ssrcs) {
            buf.fill(0, length, length + chunk)
            writeU32(buf, length, ssrc)
            buf[length + 4] = 1
            buf[length + 5] = cname.size.toByte()
            System.arraycopy(cname, 0, buf, length + 6, cname.size)
            length += chunk
        }
    }

    fun bye(ssrcs: IntArray) {
        header(ssrcs.size, RTCP_BYE, ssrcs.size)
        for (ssrc in ssrcs) {
            writeU32(buf, length, ssrc)
            length += 4
        }
    }

    fun nack(senderSsrc: Int, mediaSsrc: Int, sequences: IntArray) {
        val entries = ArrayList<Int>()
        var i = 0
        while (i < sequences.size) {
            val pid = sequences[i]
            var mask = 0
            var j = i + 1
            while (j < sequences.size) {
                val delta = (sequences[j] - pid) and 0xFFFF
                if (delta in 1..16) {
                    mask = mask or (1 shl (delta - 1))
                    j++
                } else {
                    break
                }
            }
            entries.add((pid shl 16) or mask)
            i = j
        }
        header(FMT_NACK, RTCP_RTPFB, 2 + entries.size)
        writeU32(buf, length, senderSsrc)
        writeU32(buf, length + 4, mediaSsrc)
        length += 8
        for (entry in entries) {
            writeU32(buf, length, entry)
            length += 4
        }
    }

    fun pli(senderSsrc: Int, mediaSsrc: Int) {
        header(FMT_PLI, RTCP_PSFB, 2)
        writeU32(buf, length, senderSsrc)
        writeU32(buf, length + 4, mediaSsrc)
        length += 8
    }

    fun fir(senderSsrc: Int, mediaSsrc: Int, sequenceNumber: Int) {
        header(FMT_FIR, RTCP_PSFB, 4)
        writeU32(buf, length, senderSsrc)
        writeU32(buf, length + 4, 0)
        writeU32(buf, length + 8, mediaSsrc)
        writeU32(buf, length + 12, (sequenceNumber and 0xFF) shl 24)
        length += 16
    }

    fun remb(senderSsrc: Int, bitrateBps: Long, ssrcs: IntArray) {
        var exponent = 0
        var mantissa = bitrateBps
        while (mantissa > 0x3FFFF) {
            mantissa = mantissa shr 1
            exponent++
        }
        header(FMT_APP_FEEDBACK, RTCP_PSFB, 4 + ssrcs.size)
        writeU32(buf, length, senderSsrc)
        writeU32(buf, length + 4, 0)
        writeU32(buf, length + 8, REMB_ID)
        buf[length + 12] = ssrcs.size.toByte()
        buf[length + 13] = ((exponent shl 2) or (mantissa ushr 16).toInt()).toByte()
        buf[length + 14] = (mantissa ushr 8).toByte()
        buf[length + 15] = mantissa.toByte()
        length += 16
        for (ssrc in ssrcs) {
            writeU32(buf, length, ssrc)
            length += 4
        }
    }

    private fun header(count: Int, type: Int, words: Int) {
        buf[length] = (0x80 or count).toByte()
        buf[length + 1] = type.toByte()
        writeU16(buf, length + 2, words)
        length += 4
    }
}

internal class StreamReceiverStats {
    @Volatile var lostPercent = 0.0
    @Volatile var rttMs = 0.0
    @Volatile var lastReportNanos = 0L
}

internal fun roundTripMs(block: ReportBlock, nowNtp: Long): Double? {
    if (block.lastSenderReport == 0L) {
        return null
    }
    val elapsed = (ntpMiddle32(nowNtp) - block.lastSenderReport - block.delaySinceLastSenderReport) and 0xFFFFFFFFL
    if (elapsed > 0x7FFFFFFFL) {
        return null
    }
    val ms = elapsed * 1000.0 / 65536.0
    return if (ms > 30_000.0) null else ms
}
