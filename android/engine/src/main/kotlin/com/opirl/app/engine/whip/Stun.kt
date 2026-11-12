package com.opirl.app.engine.whip

import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal const val STUN_BINDING_REQUEST = 0x0001
internal const val STUN_BINDING_SUCCESS = 0x0101
internal const val STUN_BINDING_ERROR = 0x0111
internal const val STUN_ATTR_USERNAME = 0x0006
internal const val STUN_ATTR_MESSAGE_INTEGRITY = 0x0008
internal const val STUN_ATTR_ERROR_CODE = 0x0009
internal const val STUN_ATTR_XOR_MAPPED_ADDRESS = 0x0020
internal const val STUN_ATTR_PRIORITY = 0x0024
internal const val STUN_ATTR_USE_CANDIDATE = 0x0025
internal const val STUN_ATTR_SOFTWARE = 0x8022
internal const val STUN_ATTR_FINGERPRINT = 0x8028
internal const val STUN_ATTR_ICE_CONTROLLED = 0x8029
internal const val STUN_ATTR_ICE_CONTROLLING = 0x802A
internal const val STUN_ERROR_ROLE_CONFLICT = 487
internal const val STUN_ERROR_BAD_REQUEST = 400
internal const val STUN_ERROR_UNAUTHORIZED = 401

private const val STUN_HEADER_SIZE = 20
private const val STUN_MAGIC_COOKIE = 0x2112A442
private const val STUN_FINGERPRINT_XOR = 0x5354554E
private const val STUN_INTEGRITY_SIZE = 20
private const val STUN_FAMILY_IPV4 = 1
private const val STUN_FAMILY_IPV6 = 2

internal class StunAttribute(val type: Int, val value: ByteArray)

internal class StunMessage(val type: Int, val transactionId: ByteArray, val attributes: List<StunAttribute>) {
    private var raw: ByteArray? = null
    private var integrityOffset = -1
    private var fingerprintOffset = -1

    val isRequest: Boolean
        get() = type == STUN_BINDING_REQUEST

    val isSuccess: Boolean
        get() = type == STUN_BINDING_SUCCESS

    val isError: Boolean
        get() = type == STUN_BINDING_ERROR

    val username: String?
        get() = find(STUN_ATTR_USERNAME)?.toString(Charsets.UTF_8)

    val priority: Long?
        get() = find(STUN_ATTR_PRIORITY)?.takeIf { it.size == 4 }?.let { readU32(it, 0) }

    val useCandidate: Boolean
        get() = find(STUN_ATTR_USE_CANDIDATE) != null

    val iceControlling: Long?
        get() = find(STUN_ATTR_ICE_CONTROLLING)?.takeIf { it.size == 8 }?.let { readU64(it) }

    val iceControlled: Long?
        get() = find(STUN_ATTR_ICE_CONTROLLED)?.takeIf { it.size == 8 }?.let { readU64(it) }

    val errorCode: Int?
        get() = find(STUN_ATTR_ERROR_CODE)?.takeIf { it.size >= 4 }?.let { (it[2].toInt() and 7) * 100 + (it[3].toInt() and 0xFF) }

    val xorMappedAddress: InetSocketAddress?
        get() = find(STUN_ATTR_XOR_MAPPED_ADDRESS)?.let { decodeXorAddress(it, transactionId) }

    val hasIntegrity: Boolean
        get() = integrityOffset >= 0

    fun find(attribute: Int): ByteArray? = attributes.firstOrNull { it.type == attribute }?.value

    fun verifyIntegrity(key: ByteArray): Boolean {
        val data = raw ?: return false
        if (integrityOffset < 0) {
            return false
        }
        val covered = data.copyOf(integrityOffset)
        writeU16(covered, 2, integrityOffset - STUN_HEADER_SIZE + 4 + STUN_INTEGRITY_SIZE)
        val expected = hmacSha1(key, covered, 0, covered.size)
        return MessageDigest.isEqual(expected, data.copyOfRange(integrityOffset + 4, integrityOffset + 4 + STUN_INTEGRITY_SIZE))
    }

    fun verifyFingerprint(): Boolean {
        val data = raw ?: return false
        if (fingerprintOffset < 0) {
            return false
        }
        val crc = CRC32()
        crc.update(data, 0, fingerprintOffset)
        return (crc.value.toInt() xor STUN_FINGERPRINT_XOR) == readU32(data, fingerprintOffset + 4).toInt()
    }

    fun encode(integrityKey: ByteArray?, fingerprint: Boolean): ByteArray {
        var attributeBytes = 0
        for (attribute in attributes) {
            attributeBytes += 4 + padded(attribute.value.size)
        }
        val integrityBytes = if (integrityKey != null) 4 + STUN_INTEGRITY_SIZE else 0
        val fingerprintBytes = if (fingerprint) 8 else 0
        val out = ByteArray(STUN_HEADER_SIZE + attributeBytes + integrityBytes + fingerprintBytes)
        writeU16(out, 0, type)
        writeU16(out, 2, attributeBytes + integrityBytes + fingerprintBytes)
        writeU32(out, 4, STUN_MAGIC_COOKIE)
        System.arraycopy(transactionId, 0, out, 8, 12)
        var position = STUN_HEADER_SIZE
        for (attribute in attributes) {
            writeU16(out, position, attribute.type)
            writeU16(out, position + 2, attribute.value.size)
            System.arraycopy(attribute.value, 0, out, position + 4, attribute.value.size)
            position += 4 + padded(attribute.value.size)
        }
        if (integrityKey != null) {
            writeU16(out, 2, attributeBytes + integrityBytes)
            val mac = hmacSha1(integrityKey, out, 0, position)
            writeU16(out, position, STUN_ATTR_MESSAGE_INTEGRITY)
            writeU16(out, position + 2, STUN_INTEGRITY_SIZE)
            System.arraycopy(mac, 0, out, position + 4, STUN_INTEGRITY_SIZE)
            position += integrityBytes
            writeU16(out, 2, attributeBytes + integrityBytes + fingerprintBytes)
        }
        if (fingerprint) {
            val crc = CRC32()
            crc.update(out, 0, position)
            writeU16(out, position, STUN_ATTR_FINGERPRINT)
            writeU16(out, position + 2, 4)
            writeU32(out, position + 4, crc.value.toInt() xor STUN_FINGERPRINT_XOR)
        }
        return out
    }

    companion object {
        fun isStun(data: ByteArray, offset: Int, length: Int): Boolean =
            length >= STUN_HEADER_SIZE && (data[offset].toInt() and 0xC0) == 0 &&
                readU32(data, offset + 4).toInt() == STUN_MAGIC_COOKIE

        fun parse(data: ByteArray, offset: Int, length: Int): StunMessage? {
            if (!isStun(data, offset, length)) {
                return null
            }
            val bodyLength = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
            if (bodyLength and 3 != 0 || STUN_HEADER_SIZE + bodyLength != length) {
                return null
            }
            val raw = data.copyOfRange(offset, offset + length)
            val attributes = ArrayList<StunAttribute>()
            var integrity = -1
            var fingerprint = -1
            var position = STUN_HEADER_SIZE
            while (position + 4 <= raw.size) {
                val type = readU16(raw, position)
                val size = readU16(raw, position + 2)
                if (position + 4 + size > raw.size) {
                    return null
                }
                when (type) {
                    STUN_ATTR_MESSAGE_INTEGRITY -> if (integrity < 0) integrity = position
                    STUN_ATTR_FINGERPRINT -> fingerprint = position
                }
                if (integrity < 0 || type == STUN_ATTR_MESSAGE_INTEGRITY) {
                    attributes.add(StunAttribute(type, raw.copyOfRange(position + 4, position + 4 + size)))
                } else if (type == STUN_ATTR_FINGERPRINT) {
                    attributes.add(StunAttribute(type, raw.copyOfRange(position + 4, position + 4 + size)))
                }
                position += 4 + padded(size)
            }
            if ((integrity >= 0 && integrity + 4 + STUN_INTEGRITY_SIZE > raw.size) || (fingerprint >= 0 && fingerprint + 8 != raw.size)) {
                return null
            }
            val message = StunMessage(readU16(raw, 0), raw.copyOfRange(8, 20), attributes)
            message.raw = raw
            message.integrityOffset = integrity
            message.fingerprintOffset = fingerprint
            return message
        }

        fun encodeXorAddress(address: InetSocketAddress, transactionId: ByteArray): ByteArray {
            val bytes = address.address.address
            val out = ByteArray(4 + bytes.size)
            out[1] = (if (bytes.size == 4) STUN_FAMILY_IPV4 else STUN_FAMILY_IPV6).toByte()
            writeU16(out, 2, address.port xor (STUN_MAGIC_COOKIE ushr 16))
            for (i in bytes.indices) {
                out[4 + i] = (bytes[i].toInt() xor xorMask(i, transactionId)).toByte()
            }
            return out
        }

        fun decodeXorAddress(value: ByteArray, transactionId: ByteArray): InetSocketAddress? {
            if (value.size < 4) {
                return null
            }
            val size = when (value[1].toInt()) {
                STUN_FAMILY_IPV4 -> 4
                STUN_FAMILY_IPV6 -> 16
                else -> return null
            }
            if (value.size < 4 + size) {
                return null
            }
            val bytes = ByteArray(size) { (value[4 + it].toInt() xor xorMask(it, transactionId)).toByte() }
            val port = readU16(value, 2) xor (STUN_MAGIC_COOKIE ushr 16)
            return InetSocketAddress(InetAddress.getByAddress(bytes), port)
        }

        private fun xorMask(index: Int, transactionId: ByteArray): Int =
            if (index < 4) (STUN_MAGIC_COOKIE ushr (24 - 8 * index)) and 0xFF else transactionId[index - 4].toInt() and 0xFF
    }
}

internal class StunBuilder(private val type: Int, private val transactionId: ByteArray) {
    private val attributes = ArrayList<StunAttribute>()

    fun add(attribute: Int, value: ByteArray): StunBuilder {
        attributes.add(StunAttribute(attribute, value))
        return this
    }

    fun username(value: String): StunBuilder = add(STUN_ATTR_USERNAME, value.toByteArray(Charsets.UTF_8))

    fun priority(value: Long): StunBuilder = add(STUN_ATTR_PRIORITY, ByteArray(4).also { writeU32(it, 0, value.toInt()) })

    fun useCandidate(): StunBuilder = add(STUN_ATTR_USE_CANDIDATE, ByteArray(0))

    fun iceControlling(tieBreaker: Long): StunBuilder = add(STUN_ATTR_ICE_CONTROLLING, ByteArray(8).also { writeU64(it, tieBreaker) })

    fun iceControlled(tieBreaker: Long): StunBuilder = add(STUN_ATTR_ICE_CONTROLLED, ByteArray(8).also { writeU64(it, tieBreaker) })

    fun xorMappedAddress(address: InetSocketAddress): StunBuilder =
        add(STUN_ATTR_XOR_MAPPED_ADDRESS, StunMessage.encodeXorAddress(address, transactionId))

    fun errorCode(code: Int): StunBuilder =
        add(STUN_ATTR_ERROR_CODE, byteArrayOf(0, 0, (code / 100).toByte(), (code % 100).toByte()))

    fun build(): StunMessage = StunMessage(type, transactionId, attributes.toList())
}

private fun padded(size: Int): Int = (size + 3) and 3.inv()

private fun hmacSha1(key: ByteArray, data: ByteArray, offset: Int, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA1")
    mac.init(SecretKeySpec(key, "HmacSHA1"))
    mac.update(data, offset, length)
    return mac.doFinal()
}

internal fun readU16(data: ByteArray, offset: Int): Int =
    ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

internal fun readU32(data: ByteArray, offset: Int): Long =
    ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
        ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)

internal fun readU64(data: ByteArray, offset: Int = 0): Long = (readU32(data, offset) shl 32) or readU32(data, offset + 4)

internal fun writeU16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 8).toByte()
    data[offset + 1] = value.toByte()
}

internal fun writeU32(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value ushr 24).toByte()
    data[offset + 1] = (value ushr 16).toByte()
    data[offset + 2] = (value ushr 8).toByte()
    data[offset + 3] = value.toByte()
}

internal fun writeU64(data: ByteArray, value: Long, offset: Int = 0) {
    writeU32(data, offset, (value ushr 32).toInt())
    writeU32(data, offset + 4, value.toInt())
}
