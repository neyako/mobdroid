package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.hex
import com.opirl.app.engine.flv.toHex
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StunTest {
    private val password = "VOkJxbRl1RmTxUk/WvJxBt".toByteArray()
    private val transactionId = hex("b7e7a701bc34d686fa87dfae")

    private val sampleRequest = hex(
        "000100582112a442" + "b7e7a701bc34d686fa87dfae" +
            "80220010" + "5354554e" + "2074657374" + "20636c69656e74" +
            "002400046e0001ff" +
            "80290008932ff9b151263b36" +
            "000600096576746a3a683676" + "59202020" +
            "000800149aeaa70cbfd8cb56781ef2b5b2d3f249c1b571a2" +
            "80280004e57a3bcf",
    )

    private val sampleIpv4Response = hex(
        "0101003c2112a442" + "b7e7a701bc34d686fa87dfae" +
            "8022000b7465737420766563746f7220" +
            "0020000800" + "01a147e112a643" +
            "000800142b91f599fd9e90c38c7489f92af9ba53f06be7d7" +
            "80280004c07d4c96",
    )

    private val sampleIpv6Response = hex(
        "010100482112a442" + "b7e7a701bc34d686fa87dfae" +
            "8022000b7465737420766563746f7220" +
            "002000140002a1470113a9faa5d3f179bc25f4b5bed2b9d9" +
            "00080014a382954e4be67bf11784c97c8292c275bfe3ed41" +
            "80280004c8fb0b4c",
    )

    @Test
    fun parsesTheRfc5769RequestAndVerifiesIntegrityAndFingerprint() {
        val message = assertNotNull(StunMessage.parse(sampleRequest, 0, sampleRequest.size))
        assertTrue(message.isRequest)
        assertEquals("evtj:h6vY", message.username)
        assertEquals(0x6e0001ffL, message.priority)
        assertEquals(0x932ff9b151263b36uL.toLong(), message.iceControlled)
        assertNull(message.iceControlling)
        assertContentEquals(transactionId, message.transactionId)
        assertTrue(message.verifyIntegrity(password))
        assertTrue(message.verifyFingerprint())
        assertFalse(message.verifyIntegrity("another password".toByteArray()))
    }

    @Test
    fun rejectsATamperedRequest() {
        val tampered = sampleRequest.copyOf()
        tampered[64] = (tampered[64].toInt() xor 1).toByte()
        val message = assertNotNull(StunMessage.parse(tampered, 0, tampered.size))
        assertFalse(message.verifyIntegrity(password))
        assertFalse(message.verifyFingerprint())
    }

    @Test
    fun decodesTheIpv4MappedAddressOfTheRfc5769Response() {
        val message = assertNotNull(StunMessage.parse(sampleIpv4Response, 0, sampleIpv4Response.size))
        assertTrue(message.isSuccess)
        assertEquals(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), message.xorMappedAddress)
        assertTrue(message.verifyIntegrity(password))
        assertTrue(message.verifyFingerprint())
    }

    @Test
    fun decodesTheIpv6MappedAddressOfTheRfc5769Response() {
        val message = assertNotNull(StunMessage.parse(sampleIpv6Response, 0, sampleIpv6Response.size))
        assertEquals(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853), message.xorMappedAddress)
        assertTrue(message.verifyIntegrity(password))
        assertTrue(message.verifyFingerprint())
    }

    @Test
    fun encodesMappedAddressesLikeTheRfc() {
        assertEquals("0001a147e112a643", StunMessage.encodeXorAddress(InetSocketAddress(InetAddress.getByName("192.0.2.1"), 32853), transactionId).toHex())
        assertEquals(
            "0002a1470113a9faa5d3f179bc25f4b5bed2b9d9",
            StunMessage.encodeXorAddress(InetSocketAddress(InetAddress.getByName("2001:db8:1234:5678:11:2233:4455:6677"), 32853), transactionId).toHex(),
        )
    }

    @Test
    fun anEncodedRequestParsesAndVerifiesWithTheKeyItWasSignedWith() {
        val built = StunBuilder(STUN_BINDING_REQUEST, transactionId)
            .username("remote:local")
            .iceControlling(0x0102030405060708L)
            .priority(1845501695L)
            .useCandidate()
            .build()
        val encoded = built.encode(password, fingerprint = true)
        assertEquals(0, encoded.size % 4)
        val parsed = assertNotNull(StunMessage.parse(encoded, 0, encoded.size))
        assertEquals("remote:local", parsed.username)
        assertEquals(0x0102030405060708L, parsed.iceControlling)
        assertEquals(1845501695L, parsed.priority)
        assertTrue(parsed.useCandidate)
        assertTrue(parsed.verifyIntegrity(password))
        assertTrue(parsed.verifyFingerprint())
        assertFalse(parsed.verifyIntegrity("wrong".toByteArray()))
    }

    @Test
    fun anEncodedResponseCarriesTheMappedAddressAndAnErrorCodeRoundTrips() {
        val source = InetSocketAddress(InetAddress.getByName("203.0.113.9"), 40000)
        val success = StunBuilder(STUN_BINDING_SUCCESS, transactionId).xorMappedAddress(source).build().encode(password, true)
        assertEquals(source, StunMessage.parse(success, 0, success.size)!!.xorMappedAddress)
        val error = StunBuilder(STUN_BINDING_ERROR, transactionId).errorCode(STUN_ERROR_ROLE_CONFLICT).build().encode(password, true)
        val parsed = StunMessage.parse(error, 0, error.size)!!
        assertTrue(parsed.isError)
        assertEquals(487, parsed.errorCode)
    }

    @Test
    fun rejectsDatagramsThatAreNotStun() {
        assertFalse(StunMessage.isStun(ByteArray(20), 0, 20))
        assertNull(StunMessage.parse(hex("160303000100"), 0, 6))
        val truncated = sampleRequest.copyOf(sampleRequest.size - 4)
        assertNull(StunMessage.parse(truncated, 0, truncated.size))
        val rtp = ByteArray(40).also { it[0] = 0x80.toByte() }
        assertFalse(StunMessage.isStun(rtp, 0, rtp.size))
    }
}
