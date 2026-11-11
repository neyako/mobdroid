package app.mobdroid.engine.srtla

import app.mobdroid.engine.srt.putLong
import app.mobdroid.engine.srt.putShort

internal const val SRTLA_ID_LENGTH = 256
internal const val SRTLA_TYPE_SIZE = 2
internal const val SRTLA_KEEPALIVE = 0x9000
internal const val SRTLA_ACK = 0x9100
internal const val SRTLA_REG1 = 0x9200
internal const val SRTLA_REG2 = 0x9201
internal const val SRTLA_REG3 = 0x9202
internal const val SRTLA_REG_ERR = 0x9210
internal const val SRTLA_REG_NGP = 0x9211
internal const val SRTLA_REG_NAK = 0x9212
internal const val SRTLA_KEEPALIVE_SIZE = SRTLA_TYPE_SIZE + 8
internal const val SRTLA_REGISTRATION_SIZE = SRTLA_TYPE_SIZE + SRTLA_ID_LENGTH

internal fun createSrtlaRegistrationPacket(type: Int, groupId: ByteArray): ByteArray {
    val packet = ByteArray(SRTLA_REGISTRATION_SIZE)
    packet.putShort(0, type)
    System.arraycopy(groupId, 0, packet, SRTLA_TYPE_SIZE, SRTLA_ID_LENGTH)
    return packet
}

internal fun createSrtlaKeepAlivePacket(timeMs: Long): ByteArray {
    val packet = ByteArray(SRTLA_KEEPALIVE_SIZE)
    packet.putShort(0, SRTLA_KEEPALIVE)
    packet.putLong(SRTLA_TYPE_SIZE, timeMs)
    return packet
}
