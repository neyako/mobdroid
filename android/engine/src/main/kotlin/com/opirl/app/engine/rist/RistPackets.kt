package com.opirl.app.engine.rist

import com.opirl.app.engine.srt.getInt
import com.opirl.app.engine.srt.getLong
import com.opirl.app.engine.srt.getShort
import com.opirl.app.engine.srt.putInt
import com.opirl.app.engine.srt.putLong
import com.opirl.app.engine.srt.putShort

internal const val RIST_RTP_HEADER_SIZE = 12
internal const val RIST_PAYLOAD_TYPE_MPEGTS = 33
internal const val RIST_HEADROOM = 16
internal const val RIST_MAXIMUM_PAYLOAD = 7 * 188
internal const val RIST_PACKET_SIZE = RIST_HEADROOM + RIST_RTP_HEADER_SIZE + RIST_MAXIMUM_PAYLOAD
internal const val RIST_IP_UDP_OVERHEAD = 28L
internal const val RIST_FIRST_VIRTUAL_SOURCE_PORT = 32768
internal const val RIST_MAXIMUM_NACK_SEQUENCES = 4096

private const val GRE_PROTOCOL_VSF = 0xCCE0
private const val GRE_FLAG_CHECKSUM = 0x80
private const val GRE_FLAG_KEY = 0x20
private const val GRE_FLAG_SEQUENCE = 0x10
private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L
private const val KEEPALIVE_CAPABILITIES_1 = 0x25
private const val KEEPALIVE_CAPABILITIES_2 = 0x20

internal const val RTCP_SENDER_REPORT = 200
internal const val RTCP_RECEIVER_REPORT = 201
internal const val RTCP_SOURCE_DESCRIPTION = 202
internal const val RTCP_APPLICATION = 204
internal const val RTCP_NACK_BITMASK = 205
internal const val RTCP_ECHO_REQUEST = 2
internal const val RTCP_ECHO_RESPONSE = 3
internal const val RTCP_NACK_RANGE = 0
internal const val RTCP_NACK_BITMASK_FORMAT = 1

private val RIST_NAME = byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'S'.code.toByte(), 'T'.code.toByte())

internal enum class GreFrame(val subtype: Int, val legacyProtocol: Int, val hasPorts: Boolean) {
    Data(0x0000, 0x88B6, true),
    Keepalive(0x8000, 0x88B5, false),
    BufferNegotiation(0x8002, 0, false),
}

internal class GreInbound(val version: Int, val frame: GreFrame?, val payloadOffset: Int)

internal fun greHeaderSize(version: Int, frame: GreFrame): Int =
    8 + (if (version >= 2) 4 else 0) + (if (frame.hasPorts) 4 else 0)

internal fun writeGreHeader(
    out: ByteArray,
    at: Int,
    version: Int,
    frame: GreFrame,
    sequence: Int,
    sourcePort: Int = 0,
    destinationPort: Int = 0,
): Int {
    out[at] = GRE_FLAG_SEQUENCE.toByte()
    out[at + 1] = (version shl 3).toByte()
    out.putShort(at + 2, if (version >= 2) GRE_PROTOCOL_VSF else frame.legacyProtocol)
    out.putInt(at + 4, sequence)
    var end = at + 8
    if (version >= 2) {
        out.putShort(end, 0)
        out.putShort(end + 2, frame.subtype)
        end += 4
    }
    if (frame.hasPorts) {
        out.putShort(end, sourcePort)
        out.putShort(end + 2, destinationPort)
        end += 4
    }
    return end
}

internal fun parseGre(buffer: ByteArray, length: Int): GreInbound? {
    if (length < 4) {
        return null
    }
    val flags1 = buffer[0].toInt() and 0xFF
    val flags2 = buffer[1].toInt() and 0xFF
    if ((flags1 and 0x40) != 0 || (flags2 and 0x07) != 0 || (flags1 and GRE_FLAG_KEY) != 0) {
        return null
    }
    var offset = 4
    if ((flags1 and GRE_FLAG_CHECKSUM) != 0) {
        offset += 4
    }
    if ((flags1 and GRE_FLAG_SEQUENCE) != 0) {
        offset += 4
    }
    if (length < offset) {
        return null
    }
    val protocol = buffer.getShort(2)
    val frame = if (protocol == GRE_PROTOCOL_VSF) {
        if (length < offset + 4 || buffer.getShort(offset) != 0) {
            return null
        }
        val subtype = buffer.getShort(offset + 2)
        offset += 4
        GreFrame.entries.firstOrNull { it.subtype == subtype }
    } else {
        GreFrame.entries.firstOrNull { it.legacyProtocol == protocol && protocol != 0 }
    }
    if (frame?.hasPorts == true) {
        if (length < offset + 4) {
            return null
        }
        offset += 4
    }
    return GreInbound((flags2 ushr 3) and 7, frame, offset)
}

internal fun writeRtpHeader(out: ByteArray, at: Int, sequence: Int, timestamp: Int, ssrc: Int) {
    out[at] = 0x80.toByte()
    out[at + 1] = RIST_PAYLOAD_TYPE_MPEGTS.toByte()
    out.putShort(at + 2, sequence)
    out.putInt(at + 4, timestamp)
    out.putInt(at + 8, ssrc)
}

internal fun writeKeepalive(out: ByteArray, at: Int, mac: ByteArray): Int {
    System.arraycopy(mac, 0, out, at, 6)
    out[at + 6] = KEEPALIVE_CAPABILITIES_1.toByte()
    out[at + 7] = KEEPALIVE_CAPABILITIES_2.toByte()
    return at + 8
}

internal fun writeBufferNegotiation(out: ByteArray, at: Int, senderMaximumMs: Int): Int {
    out.putShort(at, senderMaximumMs)
    out.putShort(at + 2, 0)
    out.putShort(at + 4, 0)
    return at + 6
}

internal fun writeSenderReport(out: ByteArray, at: Int, ssrc: Int, ntp: Long, timestamp: Int, packets: Int, octets: Int): Int {
    out[at] = 0x80.toByte()
    out[at + 1] = RTCP_SENDER_REPORT.toByte()
    out.putShort(at + 2, 6)
    out.putInt(at + 4, ssrc)
    out.putLong(at + 8, ntp)
    out.putInt(at + 16, timestamp)
    out.putInt(at + 20, packets)
    out.putInt(at + 24, octets)
    return at + 28
}

internal fun writeEmptyReceiverReport(out: ByteArray, at: Int, ssrc: Int): Int {
    out[at] = 0x80.toByte()
    out[at + 1] = RTCP_RECEIVER_REPORT.toByte()
    out.putShort(at + 2, 1)
    out.putInt(at + 4, ssrc)
    return at + 8
}

internal fun writeSourceDescription(out: ByteArray, at: Int, ssrc: Int, cname: ByteArray): Int {
    val size = (10 + cname.size + 1 + 3) and 3.inv()
    out[at] = 0x81.toByte()
    out[at + 1] = RTCP_SOURCE_DESCRIPTION.toByte()
    out.putShort(at + 2, (size - 1) / 4)
    out.putInt(at + 4, ssrc)
    out[at + 8] = 1
    out[at + 9] = cname.size.toByte()
    System.arraycopy(cname, 0, out, at + 10, cname.size)
    out.fill(0, at + 10 + cname.size, at + size)
    return at + size
}

private fun writeEcho(out: ByteArray, at: Int, format: Int, ssrc: Int, ntp: Long): Int {
    out[at] = (0x80 or format).toByte()
    out[at + 1] = RTCP_APPLICATION.toByte()
    out.putShort(at + 2, 5)
    out.putInt(at + 4, ssrc)
    System.arraycopy(RIST_NAME, 0, out, at + 8, 4)
    out.putLong(at + 12, ntp)
    out.putInt(at + 20, 0)
    return at + 24
}

internal fun writeEchoRequest(out: ByteArray, at: Int, ssrc: Int, ntp: Long): Int =
    writeEcho(out, at, RTCP_ECHO_REQUEST, ssrc, ntp)

internal fun writeEchoResponse(out: ByteArray, at: Int, ssrc: Int, ntp: Long): Int =
    writeEcho(out, at, RTCP_ECHO_RESPONSE, ssrc, ntp)

internal sealed interface RtcpMessage

internal object SourceDescription : RtcpMessage

internal class ReceiverReport(val fractionLost: Int, val lastSenderReport: Int, val delaySinceLastSenderReport: Int) : RtcpMessage

internal class EchoRequest(val ssrc: Int, val ntp: Long) : RtcpMessage

internal class EchoResponse(val ssrc: Int, val ntp: Long, val delayMicros: Int) : RtcpMessage

internal class Nack(val sequences: IntArray) : RtcpMessage

private class SequenceList {
    var items = IntArray(16)
    var size = 0

    fun add(sequence: Int): Boolean {
        if (size >= RIST_MAXIMUM_NACK_SEQUENCES) {
            return false
        }
        if (size == items.size) {
            items = items.copyOf(size * 2)
        }
        items[size++] = sequence and 0xFFFF
        return true
    }
}

private fun isRistName(buffer: ByteArray, at: Int): Boolean =
    (0 until 4).all { buffer[at + it] == RIST_NAME[it] }

private fun rangeNack(buffer: ByteArray, from: Int, end: Int): Nack {
    val list = SequenceList()
    var at = from
    outer@ while (end - at >= 4) {
        val first = buffer.getShort(at)
        val extra = buffer.getShort(at + 2)
        for (offset in 0..extra) {
            if (!list.add(first + offset)) {
                break@outer
            }
        }
        at += 4
    }
    return Nack(list.items.copyOf(list.size))
}

private fun bitmaskNack(buffer: ByteArray, from: Int, end: Int): Nack {
    val list = SequenceList()
    var at = from
    outer@ while (end - at >= 4) {
        val first = buffer.getShort(at)
        val mask = buffer.getShort(at + 2)
        if (!list.add(first)) {
            break
        }
        for (bit in 0 until 16) {
            if ((mask and (1 shl bit)) != 0 && !list.add(first + bit + 1)) {
                break@outer
            }
        }
        at += 4
    }
    return Nack(list.items.copyOf(list.size))
}

internal fun parseRtcp(buffer: ByteArray, offset: Int, length: Int): List<RtcpMessage> {
    val messages = ArrayList<RtcpMessage>(4)
    val end = offset + length
    var at = offset
    while (end - at >= 4) {
        val first = buffer[at].toInt() and 0xFF
        if ((first and 0xC0) != 0x80) {
            break
        }
        val size = 4 * (buffer.getShort(at + 2) + 1)
        if (size > end - at) {
            break
        }
        val format = first and 0x1F
        when (buffer[at + 1].toInt() and 0xFF) {
            RTCP_RECEIVER_REPORT -> if (size >= 32) {
                messages.add(ReceiverReport(buffer[at + 12].toInt() and 0xFF, buffer.getInt(at + 24), buffer.getInt(at + 28)))
            }
            RTCP_SOURCE_DESCRIPTION -> messages.add(SourceDescription)
            RTCP_APPLICATION -> if (size >= 12 && isRistName(buffer, at + 8)) {
                when (format) {
                    RTCP_ECHO_REQUEST -> if (size >= 24) {
                        messages.add(EchoRequest(buffer.getInt(at + 4), buffer.getLong(at + 12)))
                    }
                    RTCP_ECHO_RESPONSE -> if (size >= 24) {
                        messages.add(EchoResponse(buffer.getInt(at + 4), buffer.getLong(at + 12), buffer.getInt(at + 20)))
                    }
                    RTCP_NACK_RANGE -> messages.add(rangeNack(buffer, at + 12, at + size))
                }
            }
            RTCP_NACK_BITMASK -> if (format == RTCP_NACK_BITMASK_FORMAT && size >= 12) {
                messages.add(bitmaskNack(buffer, at + 12, at + size))
            }
        }
        at += size
    }
    return messages
}

internal fun ntpOf(nanos: Long): Long {
    val seconds = Math.floorDiv(nanos, 1_000_000_000L)
    val fraction = (Math.floorMod(nanos, 1_000_000_000L) shl 32) / 1_000_000_000L
    return ((seconds + NTP_UNIX_OFFSET_SECONDS) shl 32) or fraction
}

internal fun nanosOf(ntp: Long): Long =
    ((ntp ushr 32) - NTP_UNIX_OFFSET_SECONDS) * 1_000_000_000L + (((ntp and 0xFFFFFFFFL) * 1_000_000_000L) ushr 32)

internal fun rtpTimestampOf(elapsedNanos: Long): Int = (elapsedNanos / 1000 * 9 / 100).toInt()
