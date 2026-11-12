package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.hex
import com.opirl.app.engine.flv.toHex
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrtpTest {
    private fun rtpPacket(seq: Int, ssrc: Long, payload: ByteArray, timestamp: Long = 1000, capacity: Int = 64): Pair<ByteArray, Int> {
        val buf = ByteArray(12 + payload.size + capacity)
        buf[0] = 0x80.toByte()
        buf[1] = 96
        writeU16(buf, 2, seq)
        writeU32(buf, 4, timestamp.toInt())
        writeU32(buf, 8, ssrc.toInt())
        System.arraycopy(payload, 0, buf, 12, payload.size)
        return buf to 12 + payload.size
    }

    private fun keysFor(profile: SrtpProfile, seed: Int): SrtpKeys =
        deriveSrtpKeys(profile, ByteArray(profile.keyLength) { (seed + it).toByte() }, ByteArray(profile.saltLength) { (seed * 3 + it).toByte() })

    @Test
    fun derivesTheSessionKeysOfRfc3711AppendixB3() {
        val keys = deriveSrtpKeys(SrtpProfile.AES128_CM_HMAC_SHA1_80, hex("E1F97A0D3E018BE0D64FA32C06DE4139"), hex("0EC675AD498AFEEBB6960B3AABE6"))
        assertEquals("c61e7a93744f39ee10734afe3ff7a087", keys.rtpKey.toHex())
        assertEquals("30cbbc08863d8c85d49db34a9ae1", keys.rtpSalt.toHex())
        assertEquals("cebe321f6ff7716b6fd4ab49af256a156d38baa4", keys.rtpAuth.toHex())
    }

    @Test
    fun theAesCounterKeystreamMatchesRfc3711AppendixB2() {
        val keys = SrtpKeys(
            hex("2B7E151628AED2A6ABF7158809CF4F3C"), hex("F0F1F2F3F4F5F6F7F8F9FAFBFCFD"), ByteArray(20),
            ByteArray(16), ByteArray(14), ByteArray(20),
        )
        val context = SrtpContext(SrtpProfile.AES128_CM_HMAC_SHA1_80, keys)
        val (buf, length) = rtpPacket(0, 0, ByteArray(48))
        val protectedLength = context.protectRtp(buf, length)
        assertEquals(length + 10, protectedLength)
        assertEquals(
            "e03ead0935c95e80e166b16dd92b4eb4" + "d23513162b02d0f72a43a2fe4a5f97ab" + "41e95b3bb0a2e8dd477901e4fca894c0",
            buf.copyOfRange(12, length).toHex(),
        )
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(ByteArray(20), "HmacSHA1")) }
        mac.update(buf, 0, length)
        mac.update(ByteArray(4))
        assertEquals(mac.doFinal().copyOf(10).toHex(), buf.copyOfRange(length, protectedLength).toHex())
    }

    @Test
    fun protectsLikeRfc7714AppendixRtp() {
        val keys = SrtpKeys(hex("000102030405060708090a0b0c0d0e0f"), hex("51756964 2070726f 2071756f"), ByteArray(0), ByteArray(16), ByteArray(12), ByteArray(0))
        val context = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, keys)
        val plain = hex("8040f17b 8041f8d3 5501a0b2 47616c6c 69612065 7374206f 6d6e6973 20646976 69736120 696e2070 61727465 73207472 6573")
        val buf = plain.copyOf(plain.size + 16)
        val length = context.protectRtp(buf, plain.size)
        assertEquals(
            "8040f17b8041f8d35501a0b2f24de3a3fb34de6cacba861c9d7e4bcabe633bd50d294e6f42a5f47a51c7d19b36de3adf8833899d7f27beb16a9152cf765ee4390cce",
            buf.copyOf(length).toHex(),
        )
        val receiver = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, keys)
        val back = receiver.unprotectRtp(buf, length)
        assertEquals(plain.size, back)
        assertContentEquals(plain, buf.copyOf(back))
    }

    @Test
    fun protectsLikeRfc7714AppendixRtcp() {
        val keys = SrtpKeys(ByteArray(16), ByteArray(12), ByteArray(0), hex("000102030405060708090a0b0c0d0e0f"), hex("51756964 2070726f 2071756f"), ByteArray(0))
        val context = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, keys)
        context.setSendRtcpIndex(0x5d4)
        val plain = hex("81c8000d 4d617273 4e545031 4e545032 52545020 0000042a 0000e930 4c756e61 deadbeef deadbeef deadbeef deadbeef deadbeef")
        val buf = plain.copyOf(plain.size + 20)
        val length = context.protectRtcp(buf, plain.size)
        assertEquals(
            "81c8000d4d61727363e94885dcdab67ca727d7662f6b7e997ff5c0f76c06f32dc676a5f1730d6fda4ce09b4686303ded0bb9275bc84aa45896cf4d2fc5abf87245d9eade800005d4",
            buf.copyOf(length).toHex(),
        )
        val receiver = SrtpContext(SrtpProfile.AEAD_AES_128_GCM, keys)
        val back = receiver.unprotectRtcp(buf, length)
        assertEquals(plain.size, back)
        assertContentEquals(plain, buf.copyOf(back))
    }

    @Test
    fun rtpSurvivesTheSequenceWrapAndALateRetransmission() {
        for (profile in SrtpProfile.entries) {
            val keys = keysFor(profile, 7)
            val sender = SrtpContext(profile, keys)
            val receiver = SrtpContext(profile, keys)
            val payload = ByteArray(300) { it.toByte() }
            for (seq in listOf(65530, 65531, 65532, 65533, 65534, 65535, 0, 1, 2, 3)) {
                val (buf, length) = rtpPacket(seq, 0x11223344, payload)
                val protectedLength = sender.protectRtp(buf, length)
                assertEquals(length + profile.tagLength, protectedLength)
                if (seq == 65533) {
                    continue
                }
                assertEquals(length, receiver.unprotectRtp(buf, protectedLength), "$profile seq $seq")
                assertContentEquals(payload, buf.copyOfRange(12, length))
            }
            val (late, lateLength) = rtpPacket(65533, 0x11223344, payload)
            val lateProtected = sender.protectRtp(late, lateLength)
            assertEquals(lateLength, receiver.unprotectRtp(late, lateProtected), "$profile late")
            assertContentEquals(payload, late.copyOfRange(12, lateLength))
        }
    }

    @Test
    fun rtpAuthenticationRejectsTamperingAndReplays() {
        for (profile in SrtpProfile.entries) {
            val keys = keysFor(profile, 21)
            val sender = SrtpContext(profile, keys)
            val receiver = SrtpContext(profile, keys)
            val (buf, length) = rtpPacket(100, 5, ByteArray(100) { 9 })
            val protectedLength = sender.protectRtp(buf, length)
            val copy = buf.copyOf()
            assertEquals(length, receiver.unprotectRtp(buf, protectedLength))
            assertEquals(-1, receiver.unprotectRtp(copy, protectedLength), "$profile replay")
            val (other, otherLength) = rtpPacket(101, 5, ByteArray(100) { 9 })
            val otherProtected = sender.protectRtp(other, otherLength)
            other[20] = (other[20].toInt() xor 1).toByte()
            assertEquals(-1, receiver.unprotectRtp(other, otherProtected), "$profile tamper")
        }
    }

    @Test
    fun rtcpRoundTripsAndRejectsTamperingAndReplays() {
        for (profile in SrtpProfile.entries) {
            val keys = keysFor(profile, 33)
            val sender = SrtpContext(profile, keys)
            val receiver = SrtpContext(profile, keys)
            val plain = hex("81c8000d 4d617273 4e545031 4e545032 52545020 0000042a 0000e930 4c756e61 deadbeef deadbeef deadbeef deadbeef deadbeef")
            for (round in 0 until 3) {
                val buf = plain.copyOf(plain.size + 20)
                val length = sender.protectRtcp(buf, plain.size)
                assertEquals(plain.size + 4 + profile.tagLength, length)
                val copy = buf.copyOf()
                val tampered = buf.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
                assertEquals(-1, receiver.unprotectRtcp(tampered, length), "$profile tamper")
                assertEquals(plain.size, receiver.unprotectRtcp(buf, length), "$profile round $round")
                assertContentEquals(plain, buf.copyOf(plain.size))
                assertEquals(-1, receiver.unprotectRtcp(copy, length), "$profile replay")
            }
        }
    }

    @Test
    fun dtlsExportGivesEachSideTheOtherSidesKeys() {
        for (profile in SrtpProfile.entries) {
            val exported = ByteArray(srtpExportLength(profile)) { (it * 7 + 1).toByte() }
            val client = srtpFromDtlsExport(profile, exported, isDtlsClient = true)
            val server = srtpFromDtlsExport(profile, exported, isDtlsClient = false)
            val (toServer, toServerLength) = rtpPacket(10, 1, ByteArray(50) { 1 })
            val sent = client.local.protectRtp(toServer, toServerLength)
            assertEquals(toServerLength, server.remote.unprotectRtp(toServer, sent))
            val (toClient, toClientLength) = rtpPacket(11, 2, ByteArray(50) { 2 })
            val back = server.local.protectRtp(toClient, toClientLength)
            assertEquals(toClientLength, client.remote.unprotectRtp(toClient, back))
        }
    }
}
