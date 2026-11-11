package com.opirl.app.engine.srtla

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoFormat
import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.engine.srt.SrtEnvironment
import com.opirl.app.engine.srt.SrtOptions
import com.opirl.app.engine.srt.SrtPublisher
import com.opirl.app.engine.srt.TestSession
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srt.awaitSrtListener
import com.opirl.app.engine.srt.call
import com.opirl.app.engine.srt.countAudioPackets
import com.opirl.app.engine.srt.countVideoFrames
import com.opirl.app.engine.srt.decodeErrors
import com.opirl.app.engine.srt.ffmpegWithSrt
import com.opirl.app.engine.srt.freeUdpPort
import com.opirl.app.engine.srt.generateTransportStream
import com.opirl.app.engine.srt.scratchDirectory
import com.opirl.app.engine.srt.startSrtListener
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

private class Rig(
    val srtlaPort: Int,
    val input: File,
    val output: File,
    val listener: Process,
    val receiverLog: List<String>,
    val provider: TestLinkProvider,
    val cell: TestLink,
    val wifi: TestLink,
) {
    fun verifyOutput() {
        assertTrue(listener.waitFor(30, TimeUnit.SECONDS), "libsrt listener ends after our shutdown packet")
        assertEquals(countVideoFrames(input), countVideoFrames(output))
        assertEquals(countAudioPackets(input), countAudioPackets(output))
        assertEquals("", decodeErrors(output))
    }
}

private fun withReferenceReceiver(body: (Rig) -> Unit) {
    val receiverBinary = System.getenv("SRTLA_REC")
    assumeTrue(receiverBinary != null && File(receiverBinary).canExecute(), "SRTLA_REC points to srtla_rec")
    assumeTrue(ffmpegWithSrt(), "ffmpeg with SRT support is required")
    val directory = scratchDirectory("srtla-e2e")
    try {
        val input = File(directory, "in.ts")
        generateTransportStream(input)
        val output = File(directory, "out.ts")
        val srtPort = freeUdpPort()
        val srtlaPort = freeUdpPort()
        val listener = startSrtListener(srtPort, output)
        awaitSrtListener(srtPort)
        val receiver = ProcessBuilder(receiverBinary, srtlaPort.toString(), "127.0.0.1", srtPort.toString())
            .redirectErrorStream(true)
            .start()
        val receiverLog = CopyOnWriteArrayList<String>()
        val logReader = Thread {
            receiver.inputStream.bufferedReader().forEachLine { receiverLog.add(it) }
        }.also {
            it.isDaemon = true
            it.start()
        }
        try {
            await(what = "srtla_rec running") { receiverLog.any { it.contains("srtla_rec is now running") } }
            val cell = TestLink("cell", LinkType.CELLULAR, "127.0.0.2")
            val wifi = TestLink("wifi", LinkType.WIFI, "127.0.0.3")
            body(Rig(srtlaPort, input, output, listener, receiverLog, TestLinkProvider(cell, wifi), cell, wifi))
        } finally {
            listener.destroyForcibly()
            receiver.destroyForcibly()
            logReader.join(2000)
        }
    } finally {
        directory.deleteRecursively()
    }
}

private class BondedSession(rig: Rig) {
    var client: SrtlaClient? = null
    val test = TestSession({ engine ->
        SrtlaClient(engine, InetSocketAddress("127.0.0.1", rig.srtlaPort), rig.provider).also { client = it }
    })

    fun links(): List<SrtlaLinkStats> {
        test.engine.call { client!!.refreshStats() }
        return client!!.linkStats()
    }

    fun start(rig: Rig) {
        test.start()
        assertTrue(test.connected.await(20, TimeUnit.SECONDS), "SRT handshake through SRTLA: ${rig.receiverLog}")
    }

    fun finish(rig: Rig) {
        assertTrue(test.awaitDrained(30_000), "all packets acknowledged")
        Thread.sleep(2600)
        test.stop()
        assertTrue(test.failures.isEmpty(), "engine failures: ${test.failures}")
        rig.verifyOutput()
    }
}

private class ReplayMuxer(private val output: (ByteArray, Int, Int) -> Unit, private val data: ByteArray) : MediaSink {
    private var position = 0

    override fun setVideoFormat(format: VideoFormat) {}

    override fun setAudioFormat(format: AudioFormat) {}

    override fun writeVideo(frame: EncodedFrame) {
        val count = minOf(188 * 20, data.size - position)
        if (count > 0) {
            output(data, position, count)
            position += count
        }
    }

    override fun writeAudio(frame: EncodedFrame) {}
}

class SrtlaEndToEndTest {
    @Test
    @Timeout(180)
    fun bondedSrtReachesLibsrtThroughReferenceReceiver() {
        withReferenceReceiver { rig ->
            val session = BondedSession(rig)
            session.start(rig)
            session.test.feed(rig.input.readBytes())
            assertTrue(session.test.awaitDrained(30_000), "all packets acknowledged")
            val links = session.links()
            assertEquals(2, links.size)
            assertTrue(links.all { it.state == SrtlaLinkState.Registered }, "links $links")
            assertTrue(links.all { it.bytesSent > 10_000 }, "both links carried data: $links")
            session.finish(rig)
        }
    }

    @Test
    @Timeout(180)
    fun sessionSurvivesALinkDisappearingAndReturning() {
        withReferenceReceiver { rig ->
            val session = BondedSession(rig)
            session.start(rig)
            val feeder = Thread { session.test.feed(rig.input.readBytes(), packetsPerTick = 3, tickMs = 25) }
            feeder.start()
            Thread.sleep(800)
            rig.provider.set(rig.cell)
            await(what = "wifi socket released") { rig.wifi.openSockets == 0 }
            Thread.sleep(1000)
            rig.provider.set(rig.cell, rig.wifi)
            await(what = "wifi registered again") {
                session.links().count { it.state == SrtlaLinkState.Registered } == 2
            }
            feeder.join(30_000)
            assertNull(session.test.disconnectReason, "SRT session stayed up")
            assertEquals(2, rig.wifi.sockets.size)
            await(what = "returned link carries traffic") {
                session.links().first { it.id == "wifi" }.bytesSent > 1000
            }
            session.finish(rig)
        }
    }

    @Test
    @Timeout(180)
    fun publisherStreamsOverASrtlaUrlAndHonoursLinkPriorities() {
        withReferenceReceiver { rig ->
            val data = rig.input.readBytes()
            val connected = CountDownLatch(1)
            val disconnects = CopyOnWriteArrayList<String>()
            val publisher = SrtPublisher(
                "srtla://127.0.0.1:${rig.srtlaPort}?latency=2000",
                object : PublisherListener {
                    override fun onConnected() {
                        connected.countDown()
                    }

                    override fun onDisconnected(reason: String) {
                        disconnects.add(reason)
                    }
                },
                rig.provider,
                SrtOptions(),
                SrtEnvironment(muxerFactory = { output -> ReplayMuxer(output, data) }),
            )
            val wifiOff = mapOf(LinkType.CELLULAR to LinkPriority(1), LinkType.WIFI to LinkPriority(1, false))
            publisher.setLinkPriorities(SrtlaPriorities(true, wifiOff))
            publisher.start()
            assertTrue(connected.await(20, TimeUnit.SECONDS), "connected through SRTLA: ${rig.receiverLog}")
            repeat(data.size / (188 * 20) + 1) {
                publisher.writeVideo(EncodedFrame(ByteArray(1), it * 33_000L, true))
                Thread.sleep(10)
            }
            await(timeoutMs = 30_000, what = "everything acknowledged") {
                val stats = publisher.stats()
                stats.queuedBytes == 0L && stats.packetsInFlight == 0 && stats.bytesSent > data.size
            }
            val stats = publisher.stats()
            assertTrue(stats.rttMs > 0.0, "rtt from SRT ACKs")
            assertTrue(stats.sendBitrateBps > 0, "send bitrate")
            await(what = "link stats refreshed") {
                (publisher.linkStats().firstOrNull { it.id == "cell" }?.bytesSent ?: 0L) > 100_000
            }
            val links = publisher.linkStats().associateBy { it.id }
            assertEquals(false, links.getValue("wifi").enabled)
            assertEquals(0L, links.getValue("wifi").bytesSent)
            assertEquals(SrtlaLinkState.Registered, links.getValue("wifi").state)
            Thread.sleep(2600)
            publisher.stop()
            assertEquals(0, disconnects.size)
            rig.verifyOutput()
        }
    }
}
