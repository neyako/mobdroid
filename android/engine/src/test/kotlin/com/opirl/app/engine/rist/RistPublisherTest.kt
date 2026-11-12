package com.opirl.app.engine.rist

import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.srt.await
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class RistPublisherTest {
    private fun environment(connectTimeoutMs: Long = 5000) =
        RistEnvironment(muxerFactory = { PassThroughMuxer(it) }, connectTimeoutMs = connectTimeoutMs)

    private fun publisher(receiver: FakeRistReceiver, listener: RecordingListener, query: String = "") =
        RistPublisher("rist://127.0.0.1:${receiver.port}?buffer=200$query", listener, null, environment())

    private fun send(publisher: RistPublisher, receiver: FakeRistReceiver, packets: Int) {
        val before = receiver.dataPackets().size
        for (index in 0 until packets) {
            publisher.writeVideo(EncodedFrame(transportPayload(index, 7), index * 1000L, true))
        }
        await(5000, "data packets") { receiver.dataPackets().size >= before + packets }
    }

    @Test
    @Timeout(30)
    fun framesBeforeTheReceiverAnswersAreDropped() {
        FakeRistReceiver(RistProfile.Main, answer = false).use { receiver ->
            val listener = RecordingListener()
            val publisher = publisher(receiver, listener)
            publisher.start()
            await(5000, "handshake packets") { receiver.datagrams.isNotEmpty() }
            publisher.writeVideo(EncodedFrame(transportPayload(1, 7), 0, true))
            Thread.sleep(300)
            assertEquals(0, receiver.dataPackets().size)
            assertEquals(0, listener.connects.get())
            publisher.stop()
        }
    }

    @Test
    @Timeout(30)
    fun bothNackFormsAreRetransmittedMarkedAsRetries() {
        for (profile in RistProfile.entries) {
            FakeRistReceiver(profile).use { receiver ->
                val listener = RecordingListener()
                val publisher = publisher(receiver, listener, if (profile == RistProfile.Simple) "&profile=simple" else "")
                publisher.start()
                assertTrue(listener.connected.await(5, TimeUnit.SECONDS), "$profile connects")
                send(publisher, receiver, 8)
                val sent = receiver.dataPackets()
                val ssrc = receiver.ssrcOf(sent.first())
                assertEquals(0, ssrc and 1)
                val first = receiver.sequenceOf(sent.first())
                val peer = receiver.peers.last()
                receiver.send(peer, rangeNack(ssrc, first + 1 to 1))
                receiver.send(peer, bitmaskNack(ssrc, first + 4 to 0b10))
                await(5000, "$profile retransmits") { receiver.dataPackets().size >= sent.size + 4 }
                val retries = receiver.dataPackets().drop(sent.size)
                assertEquals(
                    setOf(first + 1, first + 2, first + 4, first + 6),
                    retries.map { receiver.sequenceOf(it) }.toSet(),
                )
                assertTrue(retries.all { receiver.ssrcOf(it) == ssrc or 1 })
                assertEquals(4, publisher.stats().retransmittedPackets)
                publisher.stop()
            }
        }
    }

    @Test
    @Timeout(30)
    fun theRoundTripTimeComesFromTheEchoes() {
        FakeRistReceiver(RistProfile.Main).use { receiver ->
            receiver.echoDelayMs = 40
            val listener = RecordingListener()
            val publisher = publisher(receiver, listener)
            publisher.start()
            assertTrue(listener.connected.await(5, TimeUnit.SECONDS))
            await(5000, "round trip time") { publisher.stats().rttMs >= 40 }
            assertTrue(publisher.stats().rttMs < 400, "${publisher.stats().rttMs}")
            publisher.stop()
        }
    }

    @Test
    @Timeout(30)
    fun aSilentReceiverEndsTheSessionOnce() {
        FakeRistReceiver(RistProfile.Main).use { receiver ->
            val listener = RecordingListener()
            val publisher = publisher(receiver, listener, "&session-timeout=400")
            publisher.start()
            assertTrue(listener.connected.await(5, TimeUnit.SECONDS))
            receiver.answering = false
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(listOf("RIST receiver stopped answering"), listener.disconnects.toList())
            await(2000, "threads end") { ristThreads().isEmpty() }
            publisher.stop()
        }
    }

    @Test
    @Timeout(30)
    fun aReceiverThatNeverAnswersTimesOut() {
        FakeRistReceiver(RistProfile.Main, answer = false).use { receiver ->
            val listener = RecordingListener()
            val publisher = RistPublisher("rist://127.0.0.1:${receiver.port}", listener, null, environment(connectTimeoutMs = 300))
            publisher.start()
            assertTrue(listener.disconnected.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("RIST handshake timed out"), listener.disconnects.toList())
            assertEquals(0, listener.connects.get())
        }
    }

    @Test
    @Timeout(30)
    fun stopIsSilentAndLeavesNothingBehind() {
        FakeRistReceiver(RistProfile.Simple).use { receiver ->
            val listener = RecordingListener()
            val publisher = publisher(receiver, listener, "&profile=simple")
            publisher.start()
            assertTrue(listener.connected.await(5, TimeUnit.SECONDS))
            send(publisher, receiver, 4)
            val started = System.nanoTime()
            publisher.stop()
            assertTrue(System.nanoTime() - started < 2_000_000_000L)
            await(2000, "threads end") { ristThreads().isEmpty() }
            Thread.sleep(300)
            assertEquals(0, listener.disconnects.size)
        }
    }

    @Test
    fun urlsThatCannotWorkAreRefused() {
        val listener = RecordingListener()
        assertFailsWith<IllegalArgumentException> { RistPublisher("rist://host:5000?secret=abc", listener) }
        assertFailsWith<IllegalArgumentException> { RistPublisher("rist://host:5001?profile=simple", listener) }
        assertFailsWith<IllegalArgumentException> { RistPublisher("rist://host", listener) }
        assertFailsWith<IllegalArgumentException> { RistPublisher("srt://host:5000", listener) }
        assertEquals(RistProfile.Main, RistUrl.parse("rist://host:5000").profile)
        assertEquals(3000, RistUrl.parse("rist://host:5000?stream-id=3000&buffer=250").virtualDestinationPort)
    }
}
