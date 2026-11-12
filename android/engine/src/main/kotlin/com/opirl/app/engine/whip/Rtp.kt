package com.opirl.app.engine.whip

private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

internal fun writeRtpHeader(buf: ByteArray, marker: Boolean, payloadType: Int, sequence: Int, timestamp: Int, ssrc: Int) {
    buf[0] = 0x80.toByte()
    buf[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
    writeU16(buf, 2, sequence)
    writeU32(buf, 4, timestamp)
    writeU32(buf, 8, ssrc)
}

internal fun buildRtxPacket(
    original: ByteArray,
    length: Int,
    rtxPayloadType: Int,
    rtxSequence: Int,
    rtxSsrc: Int,
    out: ByteArray,
): Int {
    val header = rtpHeaderLength(original, length)
    if (header < 0 || out.size < length + 2) {
        return -1
    }
    System.arraycopy(original, 0, out, 0, header)
    out[1] = ((original[1].toInt() and 0x80) or rtxPayloadType).toByte()
    writeU16(out, 2, rtxSequence)
    writeU32(out, 8, rtxSsrc)
    out[header] = original[2]
    out[header + 1] = original[3]
    System.arraycopy(original, header, out, header + 2, length - header)
    return length + 2
}

internal fun opusPacketSamples(data: ByteArray): Int {
    if (data.isEmpty()) {
        return 960
    }
    val toc = data[0].toInt() and 0xFF
    val config = toc ushr 3
    val frame = when {
        config < 12 -> intArrayOf(480, 960, 1920, 2880)[config % 4]
        config < 16 -> if (config % 2 == 0) 480 else 960
        else -> intArrayOf(120, 240, 480, 960)[config % 4]
    }
    val frames = when (toc and 3) {
        0 -> 1
        1, 2 -> 2
        else -> if (data.size >= 2) data[1].toInt() and 0x3F else 1
    }
    return frame * frames.coerceAtLeast(1)
}

internal class NackHistory(size: Int) {
    private val mask = size - 1
    private val slots = arrayOfNulls<ByteArray>(size)
    private val lengths = IntArray(size)
    private val sequences = IntArray(size) { -1 }
    private val resentAt = LongArray(size)

    init {
        require(size and mask == 0 && size > 0) { "size must be a power of two" }
    }

    fun put(sequence: Int, packet: ByteArray, length: Int) {
        val index = sequence and mask
        var slot = slots[index]
        if (slot == null || slot.size < length) {
            slot = ByteArray(maxOf(length, RTP_PACKET_CAPACITY))
            slots[index] = slot
        }
        System.arraycopy(packet, 0, slot, 0, length)
        lengths[index] = length
        sequences[index] = sequence
        resentAt[index] = 0
    }

    fun contains(sequence: Int): Boolean = sequences[sequence and mask] == sequence

    fun take(sequence: Int, nowNanos: Long, minIntervalNanos: Long, into: ByteArray): Int {
        val index = sequence and mask
        if (sequences[index] != sequence) {
            return -1
        }
        val last = resentAt[index]
        if (last != 0L && nowNanos - last < minIntervalNanos) {
            return -1
        }
        resentAt[index] = nowNanos
        System.arraycopy(slots[index]!!, 0, into, 0, lengths[index])
        return lengths[index]
    }
}

internal class MediaClock(
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var anchorPtsUs = 0L

    @Volatile private var anchorNanos = 0L

    @Volatile private var anchorNtp = 0L

    @Volatile private var anchored = false

    val isAnchored: Boolean
        get() = anchored

    fun anchor(ptsUs: Long) {
        if (anchored) {
            return
        }
        anchorPtsUs = ptsUs
        anchorNanos = nanoTime()
        val millis = wallMillis()
        anchorNtp = ((millis / 1000 + NTP_UNIX_OFFSET_SECONDS) shl 32) + ((millis % 1000) shl 32) / 1000
        anchored = true
    }

    fun ticks(ptsUs: Long, clockRate: Int): Long = (ptsUs - anchorPtsUs) * clockRate / 1_000_000

    fun ptsAt(nanos: Long): Long = anchorPtsUs + (nanos - anchorNanos) / 1000

    fun ntpAt(nanos: Long): Long {
        val elapsed = nanos - anchorNanos
        return anchorNtp + ((elapsed / 1_000_000_000) shl 32) + ((elapsed % 1_000_000_000) shl 32) / 1_000_000_000
    }
}

internal fun ntpMiddle32(ntp: Long): Long = (ntp ushr 16) and 0xFFFFFFFFL
