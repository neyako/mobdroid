package app.mobdroid.engine.srt

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.fail

fun await(timeoutMs: Long = 5000, what: String = "condition", condition: () -> Boolean) {
    val deadline = System.nanoTime() + timeoutMs * 1_000_000
    while (!condition()) {
        if (System.nanoTime() > deadline) {
            fail("Timed out waiting for $what")
        }
        Thread.sleep(5)
    }
}

internal fun <T> SrtEngine.call(block: () -> T): T {
    val task = FutureTask(Callable { block() })
    execute(task)
    return task.get(10, TimeUnit.SECONDS)
}

fun ms(value: Long): Long = TimeUnit.MILLISECONDS.toNanos(value)

fun hex(bytes: ByteArray, length: Int = bytes.size): String =
    (0 until length).joinToString("") { "%02x".format(bytes[it]) }

fun bytesOf(hex: String): ByteArray {
    val clean = hex.filter { !it.isWhitespace() }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

fun controlHeader(type: Int, info: Int = 0, timestamp: Int = 0, destination: Int = 0, size: Int = 16): ByteArray {
    val packet = ByteArray(size)
    packet.putShort(0, 0x8000 or type)
    packet.putInt(4, info)
    packet.putInt(8, timestamp)
    packet.putInt(12, destination)
    return packet
}

fun handshakeResponse(version: Int, type: Int, socketId: Int, cookie: Int = 0x1234): ByteArray {
    val packet = controlHeader(SRT_TYPE_HANDSHAKE, size = 64)
    packet.putInt(16, version)
    packet.putShort(22, if (version == 5) 0x4A17 else 2)
    packet.putInt(24, 0x100)
    packet.putInt(28, 1500)
    packet.putInt(32, 8192)
    packet.putInt(36, type)
    packet.putInt(40, socketId)
    packet.putInt(44, cookie)
    return packet
}

fun ackPacket(ackNumber: Int, lastAckSn: Int, rttUs: Int): ByteArray {
    val packet = controlHeader(SRT_TYPE_ACK, info = ackNumber, size = 44)
    packet.putInt(16, lastAckSn)
    packet.putInt(20, rttUs)
    return packet
}

fun lightAckPacket(lastAckSn: Int): ByteArray {
    val packet = controlHeader(SRT_TYPE_ACK, size = 20)
    packet.putInt(16, lastAckSn)
    return packet
}

fun nakPacket(vararg entries: Int): ByteArray {
    val packet = controlHeader(SRT_TYPE_NAK, size = 16 + 4 * entries.size)
    entries.forEachIndexed { index, value -> packet.putInt(16 + 4 * index, value) }
    return packet
}

fun nakRange(from: Int, to: Int): IntArray = intArrayOf(from or Int.MIN_VALUE, to)

fun isDataPacket(packet: ByteArray): Boolean = isSrtDataPacket(packet, packet.size)

fun controlType(packet: ByteArray): Int = packet.getShort(0) and 0x7FFF

fun sequenceOf(packet: ByteArray): Int = packet.getInt(0)

fun payloadOf(packet: ByteArray): ByteArray = packet.copyOfRange(SRT_HEADER_SIZE, packet.size)

fun tsPayload(seed: Int, packets: Int): ByteArray {
    val bytes = ByteArray(188 * packets)
    for (packet in 0 until packets) {
        bytes[packet * 188] = 0x47
        for (index in 1 until 188) {
            bytes[packet * 188 + index] = (seed * 31 + packet * 7 + index).toByte()
        }
    }
    return bytes
}
