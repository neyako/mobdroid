package app.mobdroid.engine.srt

import java.io.File
import java.net.InetSocketAddress
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

internal class LossyTransport(private val inner: SrtTransport, private val lossRate: Double) : SrtTransport by inner {
    private val random = Random(7)
    var dropped = 0
        private set

    override fun send(packet: ByteArray, length: Int) {
        if (isSrtDataPacket(packet, length) && random.nextDouble() < lossRate) {
            dropped += 1
            return
        }
        inner.send(packet, length)
    }
}

internal class TestSession(
    transportFactory: (SrtEngine) -> SrtTransport,
    config: SrtConfig = SrtConfig(null, 2000),
) : SrtSessionListener {
    val failures = CopyOnWriteArrayList<Throwable>()
    val engine = SrtEngine("test-srt-engine") { failures.add(it) }
    val connected = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    @Volatile var disconnectReason: String? = null
    val transport = transportFactory(engine)
    val session = SrtSession(engine, transport, config, this)

    override fun onSessionConnected() {
        connected.countDown()
    }

    override fun onSessionDisconnected(reason: String) {
        disconnectReason = reason
        disconnected.countDown()
    }

    fun start() {
        engine.execute { session.start() }
    }

    fun feed(data: ByteArray, packetsPerTick: Int = 3, tickMs: Long = 10) {
        val chunk = 7 * 188 * packetsPerTick
        var offset = 0
        while (offset < data.size) {
            val start = offset
            val length = minOf(chunk, data.size - offset)
            engine.execute {
                session.write(data, start, length, false)
                session.flush()
            }
            offset += length
            Thread.sleep(tickMs)
        }
    }

    fun awaitDrained(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            val counters = session.counters()
            if (counters.queuedPackets == 0 && counters.packetsInFlight == 0) {
                return true
            }
            Thread.sleep(20)
        }
        return false
    }

    fun stop() {
        val done = CountDownLatch(1)
        engine.execute {
            session.stop()
            engine.shutdown()
            done.countDown()
        }
        done.await(5, TimeUnit.SECONDS)
        engine.awaitTermination(2000)
    }
}

class SrtLoopbackTest {
    private fun loopback(lossRate: Double): SrtCounters {
        assumeTrue(ffmpegWithSrt(), "ffmpeg with SRT support is required")
        val directory = scratchDirectory("srt-loopback")
        try {
            val input = File(directory, "in.ts")
            generateTransportStream(input)
            val output = File(directory, "out.ts")
            val port = freeUdpPort()
            val receiver = startSrtListener(port, output)
            try {
                val target = InetSocketAddress("127.0.0.1", port)
                val test = TestSession({ engine ->
                    LossyTransport(UdpSrtTransport(engine, target), lossRate)
                })
                test.start()
                assertTrue(test.connected.await(15, TimeUnit.SECONDS), "SRT handshake with libsrt")
                test.feed(input.readBytes())
                assertTrue(test.awaitDrained(30_000), "all packets acknowledged")
                Thread.sleep(2600)
                val counters = test.session.counters()
                test.stop()
                assertTrue(test.failures.isEmpty(), "engine failures: ${test.failures}")
                assertTrue(receiver.waitFor(30, TimeUnit.SECONDS), "libsrt listener ends after our shutdown packet")
                val receiverLog = receiver.inputStream.readBytes().decodeToString()
                assertTrue(output.exists(), "ffmpeg listener wrote no output: $receiverLog")
                assertEquals(countVideoFrames(input), countVideoFrames(output))
                assertEquals(countAudioPackets(input), countAudioPackets(output))
                assertEquals("", decodeErrors(output))
                return counters
            } finally {
                receiver.destroyForcibly()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    @Timeout(120)
    fun streamsTransportStreamToLibsrtListener() {
        val counters = loopback(0.0)
        assertEquals(0L, counters.retransmittedPackets)
        assertTrue(counters.rttMs > 0.0)
        assertTrue(counters.bytesSent > 600_000)
    }

    @Test
    @Timeout(120)
    fun retransmissionRecoversFromPacketLoss() {
        val counters = loopback(0.05)
        assertTrue(counters.retransmittedPackets > 0, "packets were retransmitted")
        assertTrue(counters.receivedNaks > 0, "receiver sent NAK reports")
    }
}
