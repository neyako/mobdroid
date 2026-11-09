package app.mobdroid.engine.srt

internal const val SRT_HEADER_SIZE = 16
internal const val SRT_IP_UDP_HEADER_SIZE = 28L
internal const val SRT_DEFAULT_PAYLOAD_SIZE = 1316
internal const val SRT_SEQUENCE_MASK = 0x7FFFFFFF
internal const val SRT_MAX_STREAM_ID_BYTES = 512
internal const val SRT_MAX_FLOW_WINDOW_PACKETS = 8192

internal const val SRT_TYPE_HANDSHAKE = 0
internal const val SRT_TYPE_KEEPALIVE = 1
internal const val SRT_TYPE_ACK = 2
internal const val SRT_TYPE_NAK = 3
internal const val SRT_TYPE_SHUTDOWN = 5
internal const val SRT_TYPE_ACKACK = 6

internal const val SRT_HANDSHAKE_INDUCTION = 1
internal const val SRT_HANDSHAKE_CONCLUSION = -1
internal const val SRT_HANDSHAKE_REJECT_MINIMUM = 1000

internal fun ByteArray.putShort(offset: Int, value: Int) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}

internal fun ByteArray.getShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

internal fun ByteArray.putInt(offset: Int, value: Int) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}

internal fun ByteArray.getInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal fun ByteArray.putLong(offset: Int, value: Long) {
    putInt(offset, (value ushr 32).toInt())
    putInt(offset + 4, value.toInt())
}

internal fun ByteArray.getLong(offset: Int): Long =
    (getInt(offset).toLong() shl 32) or (getInt(offset + 4).toLong() and 0xFFFFFFFFL)

internal fun isSrtDataPacket(packet: ByteArray, length: Int): Boolean =
    length > 0 && (packet[0].toInt() and 0x80) == 0

internal fun isSrtSnRange(sn: Int): Boolean = sn < 0

internal fun isSrtSnAcked(sn: Int, ackSn: Int): Boolean =
    if (sn < ackSn) {
        ackSn - sn < 100_000_000
    } else {
        sn - ackSn > 100_000_000
    }

internal fun compareSrtSn(first: Int, second: Int): Int = when {
    first == second -> 0
    isSrtSnAcked(first, second) -> -1
    else -> 1
}

internal fun nextSrtSn(sn: Int): Int = (sn + 1) and SRT_SEQUENCE_MASK

internal inline fun processSrtNak(
    packet: ByteArray,
    length: Int,
    maximumRange: Int,
    onNak: (Int) -> Unit,
) {
    var offset = SRT_HEADER_SIZE
    while (offset <= length - 4) {
        val nakSn = packet.getInt(offset)
        offset += 4
        if (isSrtSnRange(nakSn)) {
            if (offset > length - 4) {
                return
            }
            val upToSn = packet.getInt(offset)
            offset += 4
            val fromSn = nakSn and SRT_SEQUENCE_MASK
            val count = ((upToSn and SRT_SEQUENCE_MASK) - fromSn) and SRT_SEQUENCE_MASK
            if (count >= maximumRange) {
                continue
            }
            for (index in 0..count) {
                onNak((fromSn + index) and SRT_SEQUENCE_MASK)
            }
        } else {
            onNak(nakSn)
        }
    }
}

internal class IntRing(capacity: Int) {
    private val values = IntArray(capacity)
    private var head = 0
    var size = 0
        private set

    fun addLast(value: Int) {
        values[(head + size) % values.size] = value
        size += 1
    }

    fun removeFirst(): Int {
        val value = values[head]
        head = (head + 1) % values.size
        size -= 1
        return value
    }

    fun clear() {
        head = 0
        size = 0
    }
}
