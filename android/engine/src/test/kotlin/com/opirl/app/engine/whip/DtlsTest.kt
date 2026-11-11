package com.opirl.app.engine.whip

import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DtlsTest {
    private val random = SecureRandom()

    private class Link(private val lossPercent: Int = 0) {
        val client = DtlsDatagramTransport { buf, off, len -> deliver(server, buf, off, len) }
        val server: DtlsDatagramTransport = DtlsDatagramTransport { buf, off, len -> deliver(client, buf, off, len) }
        private val random = java.util.Random(7)

        private fun deliver(target: DtlsDatagramTransport, buf: ByteArray, off: Int, len: Int) {
            if (lossPercent > 0 && synchronized(random) { random.nextInt(100) } < lossPercent) {
                return
            }
            target.offer(buf.copyOfRange(off, off + len), len)
        }
    }

    private fun handshake(
        link: Link,
        clientIdentity: DtlsIdentity,
        serverIdentity: DtlsIdentity,
        clientExpects: DtlsFingerprint,
        serverExpects: DtlsFingerprint,
        clientProfiles: List<SrtpProfile> = SrtpProfile.entries,
        serverProfiles: List<SrtpProfile> = SrtpProfile.entries,
    ): Pair<DtlsResult, DtlsResult> {
        val executor = Executors.newFixedThreadPool(2)
        try {
            link.client.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            link.server.deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            val serverTask = executor.submit(Callable { DtlsEndpoint(serverIdentity, serverExpects, serverProfiles).acceptAsServer(link.server) })
            val clientTask = executor.submit(Callable { DtlsEndpoint(clientIdentity, clientExpects, clientProfiles).connectAsClient(link.client) })
            return clientTask.get(30, TimeUnit.SECONDS) to serverTask.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fingerprintOf(identity: DtlsIdentity) = DtlsFingerprint("sha-256", identity.fingerprint)

    private fun rtp(seq: Int, ssrc: Int): Pair<ByteArray, Int> {
        val buf = ByteArray(12 + 40 + 32)
        buf[0] = 0x80.toByte()
        buf[1] = 96
        writeU16(buf, 2, seq)
        writeU32(buf, 8, ssrc)
        for (i in 12 until 52) buf[i] = i.toByte()
        return buf to 52
    }

    @Test
    fun clientAndServerAgreeOnTheSrtpKeysAndProfile() {
        val a = DtlsIdentity.generate(random)
        val b = DtlsIdentity.generate(random)
        val (client, server) = handshake(Link(), a, b, fingerprintOf(b), fingerprintOf(a))
        assertEquals(client.profile, server.profile)
        assertEquals(SrtpProfile.AES128_CM_HMAC_SHA1_80, client.profile)
        val (toServer, toServerLength) = rtp(1, 11)
        val sent = client.keys.local.protectRtp(toServer, toServerLength)
        assertEquals(toServerLength, server.keys.remote.unprotectRtp(toServer, sent))
        val (toClient, toClientLength) = rtp(2, 22)
        val back = server.keys.local.protectRtp(toClient, toClientLength)
        assertEquals(toClientLength, client.keys.remote.unprotectRtp(toClient, back))
        client.transport.close()
        server.transport.close()
    }

    @Test
    fun theProfileFollowsTheServersPreferenceAmongTheOffered() {
        val a = DtlsIdentity.generate(random)
        val b = DtlsIdentity.generate(random)
        val (client, server) = handshake(
            Link(), a, b, fingerprintOf(b), fingerprintOf(a),
            clientProfiles = listOf(SrtpProfile.AEAD_AES_128_GCM, SrtpProfile.AES128_CM_HMAC_SHA1_80),
            serverProfiles = listOf(SrtpProfile.AES128_CM_HMAC_SHA1_80, SrtpProfile.AEAD_AES_128_GCM),
        )
        assertEquals(SrtpProfile.AES128_CM_HMAC_SHA1_80, server.profile)
        assertEquals(SrtpProfile.AES128_CM_HMAC_SHA1_80, client.profile)
        val (packet, length) = rtp(5, 7)
        val sent = server.keys.local.protectRtp(packet, length)
        assertEquals(length + 10, sent)
        assertEquals(length, client.keys.remote.unprotectRtp(packet, sent))
    }

    @Test
    fun theHandshakeSurvivesLostDatagrams() {
        val a = DtlsIdentity.generate(random)
        val b = DtlsIdentity.generate(random)
        val (client, server) = handshake(Link(lossPercent = 30), a, b, fingerprintOf(b), fingerprintOf(a))
        assertEquals(client.profile, server.profile)
    }

    @Test
    fun aWrongFingerprintFailsTheHandshakeOnTheCheckingSide() {
        val a = DtlsIdentity.generate(random)
        val b = DtlsIdentity.generate(random)
        val other = DtlsIdentity.generate(random)
        assertFailsWith<Exception> { handshake(Link(), a, b, fingerprintOf(other), fingerprintOf(a)) }
        assertFailsWith<Exception> { handshake(Link(), a, b, fingerprintOf(b), fingerprintOf(other)) }
    }

    @Test
    fun theFingerprintOfTheGeneratedCertificateIsTheSha256OfItsEncoding() {
        val identity = DtlsIdentity.generate(random)
        assertTrue(DtlsFingerprint("sha-256", identity.fingerprint).matches(identity.encoded))
        assertTrue(!DtlsFingerprint("sha-256", identity.fingerprint).matches(identity.encoded.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        assertContentEquals(identity.fingerprint, java.security.MessageDigest.getInstance("SHA-256").digest(identity.encoded))
    }
}
