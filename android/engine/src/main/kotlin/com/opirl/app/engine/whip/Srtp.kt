package com.opirl.app.engine.whip

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val AUTH_KEY_LENGTH = 20
private const val SRTCP_INDEX_SIZE = 4
private const val SRTCP_ENCRYPTED = 0x80000000.toInt()
private const val SRTCP_INDEX_MASK = 0x7FFFFFFF
private const val MAX_STATES = 8
private const val GCM_IV_SIZE = 12

internal enum class SrtpProfile(val dtlsId: Int, val keyLength: Int, val saltLength: Int, val tagLength: Int) {
    AES128_CM_HMAC_SHA1_80(0x0001, 16, 14, 10),
    AEAD_AES_128_GCM(0x0007, 16, 12, 16),
    ;

    val gcm: Boolean
        get() = this == AEAD_AES_128_GCM

    companion object {
        fun fromDtls(id: Int): SrtpProfile? = entries.firstOrNull { it.dtlsId == id }
    }
}

internal class SrtpKeys(
    val rtpKey: ByteArray,
    val rtpSalt: ByteArray,
    val rtpAuth: ByteArray,
    val rtcpKey: ByteArray,
    val rtcpSalt: ByteArray,
    val rtcpAuth: ByteArray,
)

internal fun deriveSrtpKeys(profile: SrtpProfile, masterKey: ByteArray, masterSalt: ByteArray): SrtpKeys {
    fun derive(label: Int, length: Int): ByteArray {
        val iv = ByteArray(16)
        System.arraycopy(masterSalt, 0, iv, 0, masterSalt.size)
        iv[7] = (iv[7].toInt() xor label).toByte()
        val blocks = (length + 15) / 16
        val counters = ByteArray(blocks * 16)
        for (block in 0 until blocks) {
            System.arraycopy(iv, 0, counters, block * 16, 14)
            counters[block * 16 + 14] = (block ushr 8).toByte()
            counters[block * 16 + 15] = block.toByte()
        }
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
        return cipher.doFinal(counters).copyOf(length)
    }
    return SrtpKeys(
        rtpKey = derive(0, profile.keyLength),
        rtpSalt = derive(2, profile.saltLength),
        rtpAuth = derive(1, AUTH_KEY_LENGTH),
        rtcpKey = derive(3, profile.keyLength),
        rtcpSalt = derive(5, profile.saltLength),
        rtcpAuth = derive(4, AUTH_KEY_LENGTH),
    )
}

internal class SrtpSessionKeys(val local: SrtpContext, val remote: SrtpContext)

internal fun srtpFromDtlsExport(profile: SrtpProfile, exported: ByteArray, isDtlsClient: Boolean): SrtpSessionKeys {
    val key = profile.keyLength
    val salt = profile.saltLength
    val clientKey = exported.copyOfRange(0, key)
    val serverKey = exported.copyOfRange(key, 2 * key)
    val clientSalt = exported.copyOfRange(2 * key, 2 * key + salt)
    val serverSalt = exported.copyOfRange(2 * key + salt, 2 * key + 2 * salt)
    val client = SrtpContext(profile, deriveSrtpKeys(profile, clientKey, clientSalt))
    val server = SrtpContext(profile, deriveSrtpKeys(profile, serverKey, serverSalt))
    return if (isDtlsClient) SrtpSessionKeys(client, server) else SrtpSessionKeys(server, client)
}

internal fun srtpExportLength(profile: SrtpProfile): Int = 2 * (profile.keyLength + profile.saltLength)

private class ReplayWindow {
    private var highest = -1L
    private var bitmap = 0L

    fun accepts(index: Long): Boolean {
        if (highest < 0) {
            return true
        }
        val delta = highest - index
        return when {
            delta < 0 -> true
            delta >= 64 -> false
            else -> bitmap and (1L shl delta.toInt()) == 0L
        }
    }

    fun update(index: Long) {
        if (highest < 0 || index > highest) {
            val shift = if (highest < 0) 64L else index - highest
            bitmap = if (shift >= 64) 1L else (bitmap shl shift.toInt()) or 1L
            highest = index
        } else {
            bitmap = bitmap or (1L shl (highest - index).toInt())
        }
    }
}

private class StreamState(val ssrc: Int) {
    var roc = 0L
    var highestSeq = -1
    val replay = ReplayWindow()
    val rtcpReplay = ReplayWindow()

    fun guessRoc(seq: Int): Long {
        if (highestSeq < 0) {
            return roc
        }
        return if (highestSeq < 0x8000) {
            if (seq - highestSeq > 0x8000) roc - 1 else roc
        } else {
            if (highestSeq - 0x8000 > seq) roc + 1 else roc
        }
    }

    fun advance(guessedRoc: Long, seq: Int) {
        if (highestSeq < 0 || guessedRoc > roc || (guessedRoc == roc && seq > highestSeq)) {
            roc = guessedRoc
            highestSeq = seq
        }
    }
}

internal class SrtpContext(private val profile: SrtpProfile, keys: SrtpKeys) {
    private val gcm = profile.gcm
    private val rtpSalt = keys.rtpSalt
    private val rtcpSalt = keys.rtcpSalt
    private val rtpSpec = SecretKeySpec(keys.rtpKey, "AES")
    private val rtcpSpec = SecretKeySpec(keys.rtcpKey, "AES")
    private val rtpEcb: Cipher? = if (gcm) null else Cipher.getInstance("AES/ECB/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, rtpSpec) }
    private val rtcpEcb: Cipher? = if (gcm) null else Cipher.getInstance("AES/ECB/NoPadding").also { it.init(Cipher.ENCRYPT_MODE, rtcpSpec) }
    private val rtpMac: Mac? = if (gcm) null else Mac.getInstance("HmacSHA1").also { it.init(SecretKeySpec(keys.rtpAuth, "HmacSHA1")) }
    private val rtcpMac: Mac? = if (gcm) null else Mac.getInstance("HmacSHA1").also { it.init(SecretKeySpec(keys.rtcpAuth, "HmacSHA1")) }
    private val gcmCipher: Cipher? = if (gcm) Cipher.getInstance("AES/GCM/NoPadding") else null
    private val iv = ByteArray(16)
    private val tag = ByteArray(AUTH_KEY_LENGTH)
    private val rocBytes = ByteArray(4)
    private val states = arrayOfNulls<StreamState>(MAX_STATES)
    private var stateCount = 0
    private var counters = ByteArray(2048)
    private var keystream = ByteArray(2048)
    private var scratch = ByteArray(2048)
    private var sendRtcpIndex = 0

    val tagLength: Int
        get() = profile.tagLength

    internal fun setSendRtcpIndex(index: Int) {
        sendRtcpIndex = index
    }

    fun protectRtp(buf: ByteArray, length: Int): Int {
        val header = rtpHeaderLength(buf, length)
        if (header < 0) {
            return -1
        }
        val seq = readU16(buf, 2)
        val ssrc = readU32(buf, 8).toInt()
        val state = stateFor(ssrc)
        val roc = state.guessRoc(seq)
        state.advance(roc, seq)
        return try {
            if (gcm) protectRtpGcm(buf, length, header, ssrc, roc, seq) else protectRtpCm(buf, length, header, ssrc, roc, seq)
        } catch (_: GeneralSecurityException) {
            -1
        }
    }

    fun unprotectRtp(buf: ByteArray, length: Int): Int {
        val header = rtpHeaderLength(buf, length)
        if (header < 0 || length < header + profile.tagLength) {
            return -1
        }
        val seq = readU16(buf, 2)
        val ssrc = readU32(buf, 8).toInt()
        val state = stateFor(ssrc)
        val roc = state.guessRoc(seq)
        val index = (roc shl 16) or seq.toLong()
        if (!state.replay.accepts(index)) {
            return -1
        }
        val plain = if (gcm) unprotectRtpGcm(buf, length, header, ssrc, roc, seq) else unprotectRtpCm(buf, length, header, ssrc, roc, seq)
        if (plain >= 0) {
            state.replay.update(index)
            state.advance(roc, seq)
        }
        return plain
    }

    fun protectRtcp(buf: ByteArray, length: Int): Int {
        if (length < 8) {
            return -1
        }
        val ssrc = readU32(buf, 4).toInt()
        val index = sendRtcpIndex
        sendRtcpIndex = (sendRtcpIndex + 1) and SRTCP_INDEX_MASK
        return try {
            if (gcm) protectRtcpGcm(buf, length, ssrc, index) else protectRtcpCm(buf, length, ssrc, index)
        } catch (_: GeneralSecurityException) {
            -1
        }
    }

    fun unprotectRtcp(buf: ByteArray, length: Int): Int {
        if (length < 8 + SRTCP_INDEX_SIZE + profile.tagLength) {
            return -1
        }
        val ssrc = readU32(buf, 4).toInt()
        val state = stateFor(ssrc)
        val word = readU32(buf, protectedRtcpIndexOffset(length)).toInt()
        val index = (word and SRTCP_INDEX_MASK).toLong()
        if (!state.rtcpReplay.accepts(index)) {
            return -1
        }
        val plain = if (gcm) unprotectRtcpGcm(buf, length, ssrc, word) else unprotectRtcpCm(buf, length, ssrc, word)
        if (plain >= 0) {
            state.rtcpReplay.update(index)
        }
        return plain
    }

    private fun protectedRtcpIndexOffset(length: Int): Int = if (gcm) length - SRTCP_INDEX_SIZE else length - profile.tagLength - SRTCP_INDEX_SIZE

    private fun stateFor(ssrc: Int): StreamState {
        for (i in 0 until stateCount) {
            val state = states[i]!!
            if (state.ssrc == ssrc) {
                return state
            }
        }
        val created = StreamState(ssrc)
        if (stateCount < MAX_STATES) {
            states[stateCount++] = created
        } else {
            System.arraycopy(states, 1, states, 0, MAX_STATES - 1)
            states[MAX_STATES - 1] = created
        }
        return created
    }

    private fun protectRtpCm(buf: ByteArray, length: Int, header: Int, ssrc: Int, roc: Long, seq: Int): Int {
        fillCmIv(rtpSalt, ssrc, (roc shl 16) or seq.toLong())
        xorKeystream(rtpEcb!!, buf, header, length - header)
        val mac = rtpMac!!
        writeU32(rocBytes, 0, roc.toInt())
        mac.update(buf, 0, length)
        mac.update(rocBytes, 0, 4)
        mac.doFinal(tag, 0)
        System.arraycopy(tag, 0, buf, length, profile.tagLength)
        return length + profile.tagLength
    }

    private fun unprotectRtpCm(buf: ByteArray, length: Int, header: Int, ssrc: Int, roc: Long, seq: Int): Int {
        val body = length - profile.tagLength
        val mac = rtpMac!!
        writeU32(rocBytes, 0, roc.toInt())
        mac.update(buf, 0, body)
        mac.update(rocBytes, 0, 4)
        mac.doFinal(tag, 0)
        if (!constantTimeEquals(tag, buf, body, profile.tagLength)) {
            return -1
        }
        fillCmIv(rtpSalt, ssrc, (roc shl 16) or seq.toLong())
        xorKeystream(rtpEcb!!, buf, header, body - header)
        return body
    }

    private fun protectRtpGcm(buf: ByteArray, length: Int, header: Int, ssrc: Int, roc: Long, seq: Int): Int {
        val cipher = gcmCipher!!
        fillGcmRtpIv(ssrc, roc, seq)
        cipher.init(Cipher.ENCRYPT_MODE, rtpSpec, GCMParameterSpec(128, iv, 0, GCM_IV_SIZE))
        cipher.updateAAD(buf, 0, header)
        ensureScratch(length - header + profile.tagLength)
        val written = cipher.doFinal(buf, header, length - header, scratch, 0)
        System.arraycopy(scratch, 0, buf, header, written)
        return header + written
    }

    private fun unprotectRtpGcm(buf: ByteArray, length: Int, header: Int, ssrc: Int, roc: Long, seq: Int): Int {
        val cipher = gcmCipher!!
        fillGcmRtpIv(ssrc, roc, seq)
        return try {
            cipher.init(Cipher.DECRYPT_MODE, rtpSpec, GCMParameterSpec(128, iv, 0, GCM_IV_SIZE))
            cipher.updateAAD(buf, 0, header)
            ensureScratch(length - header)
            val written = cipher.doFinal(buf, header, length - header, scratch, 0)
            System.arraycopy(scratch, 0, buf, header, written)
            header + written
        } catch (_: GeneralSecurityException) {
            -1
        }
    }

    private fun protectRtcpCm(buf: ByteArray, length: Int, ssrc: Int, index: Int): Int {
        fillCmIv(rtcpSalt, ssrc, index.toLong())
        xorKeystream(rtcpEcb!!, buf, 8, length - 8)
        writeU32(buf, length, SRTCP_ENCRYPTED or index)
        val mac = rtcpMac!!
        mac.update(buf, 0, length + SRTCP_INDEX_SIZE)
        mac.doFinal(tag, 0)
        System.arraycopy(tag, 0, buf, length + SRTCP_INDEX_SIZE, profile.tagLength)
        return length + SRTCP_INDEX_SIZE + profile.tagLength
    }

    private fun unprotectRtcpCm(buf: ByteArray, length: Int, ssrc: Int, word: Int): Int {
        val indexOffset = length - profile.tagLength - SRTCP_INDEX_SIZE
        val mac = rtcpMac!!
        mac.update(buf, 0, indexOffset + SRTCP_INDEX_SIZE)
        mac.doFinal(tag, 0)
        if (!constantTimeEquals(tag, buf, indexOffset + SRTCP_INDEX_SIZE, profile.tagLength)) {
            return -1
        }
        if (word and SRTCP_ENCRYPTED != 0) {
            fillCmIv(rtcpSalt, ssrc, (word and SRTCP_INDEX_MASK).toLong())
            xorKeystream(rtcpEcb!!, buf, 8, indexOffset - 8)
        }
        return indexOffset
    }

    private fun protectRtcpGcm(buf: ByteArray, length: Int, ssrc: Int, index: Int): Int {
        val cipher = gcmCipher!!
        fillGcmRtcpIv(ssrc, index)
        cipher.init(Cipher.ENCRYPT_MODE, rtcpSpec, GCMParameterSpec(128, iv, 0, GCM_IV_SIZE))
        val word = SRTCP_ENCRYPTED or index
        cipher.updateAAD(buf, 0, 8)
        rocBytes[0] = (word ushr 24).toByte()
        rocBytes[1] = (word ushr 16).toByte()
        rocBytes[2] = (word ushr 8).toByte()
        rocBytes[3] = word.toByte()
        cipher.updateAAD(rocBytes, 0, 4)
        ensureScratch(length - 8 + profile.tagLength)
        val written = cipher.doFinal(buf, 8, length - 8, scratch, 0)
        System.arraycopy(scratch, 0, buf, 8, written)
        writeU32(buf, 8 + written, word)
        return 8 + written + SRTCP_INDEX_SIZE
    }

    private fun unprotectRtcpGcm(buf: ByteArray, length: Int, ssrc: Int, word: Int): Int {
        if (word and SRTCP_ENCRYPTED == 0) {
            return -1
        }
        val cipher = gcmCipher!!
        val indexOffset = length - SRTCP_INDEX_SIZE
        return try {
            fillGcmRtcpIv(ssrc, word and SRTCP_INDEX_MASK)
            cipher.init(Cipher.DECRYPT_MODE, rtcpSpec, GCMParameterSpec(128, iv, 0, GCM_IV_SIZE))
            cipher.updateAAD(buf, 0, 8)
            cipher.updateAAD(buf, indexOffset, SRTCP_INDEX_SIZE)
            ensureScratch(indexOffset - 8)
            val written = cipher.doFinal(buf, 8, indexOffset - 8, scratch, 0)
            System.arraycopy(scratch, 0, buf, 8, written)
            8 + written
        } catch (_: GeneralSecurityException) {
            -1
        }
    }

    private fun fillCmIv(salt: ByteArray, ssrc: Int, index: Long) {
        iv.fill(0)
        System.arraycopy(salt, 0, iv, 0, salt.size)
        iv[4] = (iv[4].toInt() xor (ssrc ushr 24)).toByte()
        iv[5] = (iv[5].toInt() xor (ssrc ushr 16)).toByte()
        iv[6] = (iv[6].toInt() xor (ssrc ushr 8)).toByte()
        iv[7] = (iv[7].toInt() xor ssrc).toByte()
        iv[8] = (iv[8].toInt() xor (index ushr 40).toInt()).toByte()
        iv[9] = (iv[9].toInt() xor (index ushr 32).toInt()).toByte()
        iv[10] = (iv[10].toInt() xor (index ushr 24).toInt()).toByte()
        iv[11] = (iv[11].toInt() xor (index ushr 16).toInt()).toByte()
        iv[12] = (iv[12].toInt() xor (index ushr 8).toInt()).toByte()
        iv[13] = (iv[13].toInt() xor index.toInt()).toByte()
    }

    private fun fillGcmRtpIv(ssrc: Int, roc: Long, seq: Int) {
        iv.fill(0)
        iv[2] = (ssrc ushr 24).toByte()
        iv[3] = (ssrc ushr 16).toByte()
        iv[4] = (ssrc ushr 8).toByte()
        iv[5] = ssrc.toByte()
        iv[6] = (roc ushr 24).toByte()
        iv[7] = (roc ushr 16).toByte()
        iv[8] = (roc ushr 8).toByte()
        iv[9] = roc.toByte()
        iv[10] = (seq ushr 8).toByte()
        iv[11] = seq.toByte()
        xorSalt(rtpSalt)
    }

    private fun fillGcmRtcpIv(ssrc: Int, index: Int) {
        iv.fill(0)
        iv[2] = (ssrc ushr 24).toByte()
        iv[3] = (ssrc ushr 16).toByte()
        iv[4] = (ssrc ushr 8).toByte()
        iv[5] = ssrc.toByte()
        iv[8] = (index ushr 24).toByte()
        iv[9] = (index ushr 16).toByte()
        iv[10] = (index ushr 8).toByte()
        iv[11] = index.toByte()
        xorSalt(rtcpSalt)
    }

    private fun xorSalt(salt: ByteArray) {
        for (i in 0 until GCM_IV_SIZE) {
            iv[i] = (iv[i].toInt() xor salt[i].toInt()).toByte()
        }
    }

    private fun xorKeystream(ecb: Cipher, buf: ByteArray, offset: Int, length: Int) {
        if (length <= 0) {
            return
        }
        val blocks = (length + 15) ushr 4
        val size = blocks shl 4
        if (counters.size < size) {
            counters = ByteArray(size)
            keystream = ByteArray(size)
        }
        for (block in 0 until blocks) {
            val at = block shl 4
            System.arraycopy(iv, 0, counters, at, 14)
            counters[at + 14] = (block ushr 8).toByte()
            counters[at + 15] = block.toByte()
        }
        ecb.update(counters, 0, size, keystream, 0)
        for (i in 0 until length) {
            buf[offset + i] = (buf[offset + i].toInt() xor keystream[i].toInt()).toByte()
        }
    }

    private fun ensureScratch(size: Int) {
        if (scratch.size < size) {
            scratch = ByteArray(size)
        }
    }

    private fun constantTimeEquals(expected: ByteArray, actual: ByteArray, offset: Int, length: Int): Boolean {
        var difference = 0
        for (i in 0 until length) {
            difference = difference or (expected[i].toInt() xor actual[offset + i].toInt())
        }
        return difference == 0
    }
}

internal fun rtpHeaderLength(buf: ByteArray, length: Int): Int {
    if (length < 12 || (buf[0].toInt() and 0xC0) != 0x80) {
        return -1
    }
    var header = 12 + 4 * (buf[0].toInt() and 0x0F)
    if (buf[0].toInt() and 0x10 != 0) {
        if (length < header + 4) {
            return -1
        }
        header += 4 + 4 * readU16(buf, header + 2)
    }
    return if (header <= length) header else -1
}
