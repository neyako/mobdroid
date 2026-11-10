package app.mobdroid.engine.srt

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import app.mobdroid.engine.net.PublisherListener
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PEER_SOCKET = 0x2ab1f77c

internal class FakeSrtPeer(private val engine: SrtEngine, private val handshake: Boolean = true) : SrtTransport {
    override var listener: SrtTransportListener? = null
    val received = CopyOnWriteArrayList<ByteArray>()
    @Volatile var closed = false
    @Volatile var rttUs = 40_000

    override fun start() {
        listener?.onReady()
    }

    override fun send(packet: ByteArray, length: Int) {
        val copy = packet.copyOf(length)
        received.add(copy)
        if (isDataPacket(copy)) {
            reply(ackPacket(sequenceOf(copy) + 1, sequenceOf(copy) + 1, rttUs))
        } else if (handshake && controlType(copy) == SRT_TYPE_HANDSHAKE) {
            when (copy.getInt(36)) {
                SRT_HANDSHAKE_INDUCTION -> reply(handshakeResponse(5, SRT_HANDSHAKE_INDUCTION, PEER_SOCKET))
                SRT_HANDSHAKE_CONCLUSION -> reply(handshakeResponse(5, SRT_HANDSHAKE_CONCLUSION, PEER_SOCKET))
            }
        }
    }

    override fun close() {
        closed = true
    }

    fun reply(packet: ByteArray) {
        engine.execute { listener?.onPacket(packet, packet.size) }
    }

    fun data(): List<ByteArray> = received.filter { isDataPacket(it) }

    fun payload(): ByteArray = data().fold(ByteArray(0)) { all, packet -> all + payloadOf(packet) }
}

private class RecordingListener : PublisherListener {
    val connected = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val disconnects = CopyOnWriteArrayList<String>()
    @Volatile var connectedCount = 0

    override fun onConnected() {
        connectedCount += 1
        connected.countDown()
    }

    override fun onDisconnected(reason: String) {
        disconnects.add(reason)
        disconnected.countDown()
    }
}

private class RecordingMuxer(private val output: (ByteArray, Int, Int) -> Unit) : MediaSink {
    val calls = CopyOnWriteArrayList<String>()
    @Volatile var blockVideo: CountDownLatch? = null
    val entered = CountDownLatch(1)

    override fun setVideoFormat(format: VideoFormat) {
        calls.add("vformat")
    }

    override fun setAudioFormat(format: AudioFormat) {
        calls.add("aformat")
    }

    override fun writeVideo(frame: EncodedFrame) {
        calls.add("V${frame.ptsUs}")
        entered.countDown()
        blockVideo?.await(10, TimeUnit.SECONDS)
        output(frame.data, 0, frame.data.size)
    }

    override fun writeAudio(frame: EncodedFrame) {
        calls.add("A${frame.ptsUs}")
        output(frame.data, 0, frame.data.size)
    }
}

private fun tsFrame(packets: Int, seed: Int, pts: Long, key: Boolean = true) =
    EncodedFrame(tsPayload(seed, packets), pts, key)

private fun publisherThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter {
    it.isAlive && (it.name == "srt-publisher" || it.name == "srt-udp-reader")
}

private class Setup(
    url: String = "srt://127.0.0.1:9000?streamid=test&latency=1000",
    handshake: Boolean = true,
    maximumQueuedBytes: Long = 4L * 1024 * 1024,
    resolver: (String, Int) -> InetSocketAddress = { _, port -> InetSocketAddress("127.0.0.1", port) },
    options: SrtOptions = SrtOptions(),
) {
    val listener = RecordingListener()
    var peer: FakeSrtPeer? = null
    var muxer: RecordingMuxer? = null
    val publisher = SrtPublisher(
        url,
        listener,
        null,
        options,
        SrtEnvironment(
            muxerFactory = { output -> RecordingMuxer(output).also { muxer = it } },
            transportFactory = { engine, _, _ -> FakeSrtPeer(engine, handshake).also { peer = it } },
            resolver = resolver,
            maximumQueuedBytes = maximumQueuedBytes,
        ),
    )

    fun connect() {
        publisher.start()
        assertTrue(listener.connected.await(5, TimeUnit.SECONDS), "publisher connects")
    }
}

class SrtPublisherTest {
    @Test
    fun connectsOnceAndSendsTheStreamIdInTheHandshake() {
        val setup = Setup()
        setup.connect()
        val conclusion = setup.peer!!.received.first { controlType(it) == SRT_TYPE_HANDSHAKE && it.getInt(36) == -1 }
        assertEquals(5, conclusion.getShort(22))
        assertEquals("0005000174736574", hex(conclusion.copyOfRange(conclusion.size - 8, conclusion.size)))
        assertEquals(1, setup.listener.connectedCount)
        setup.publisher.stop()
    }

    @Test
    fun smallPacketsOptionUsesSixMpegTsPacketsPerSrtPacket() {
        val setup = Setup(options = SrtOptions(bigPackets = false))
        setup.connect()
        setup.publisher.writeVideo(tsFrame(20, 1, 1))
        await(what = "all bytes delivered") { setup.peer!!.payload().size == 20 * 188 }
        assertEquals(listOf(1128, 1128, 1128, 376), setup.peer!!.data().map { it.size - 16 })
        setup.publisher.stop()
    }

    @Test
    fun framesGoThroughTheMuxerIntoSrtPacketsInOrder() {
        val setup = Setup()
        setup.publisher.setVideoFormat(VideoFormat(VideoCodec.H264, 1280, 720, 30, ByteArray(4)))
        setup.publisher.setAudioFormat(AudioFormat(48000, 2, ByteArray(2)))
        setup.connect()
        val frames = listOf(tsFrame(20, 1, 1), tsFrame(2, 2, 2, key = false), tsFrame(9, 3, 3, key = false))
        setup.publisher.writeVideo(frames[0])
        setup.publisher.writeAudio(frames[1])
        setup.publisher.writeVideo(frames[2])
        val expected = frames[0].data + frames[1].data + frames[2].data
        await(what = "all bytes delivered") { setup.peer!!.payload().size == expected.size }
        assertContentEquals(expected, setup.peer!!.payload())
        assertEquals(listOf("vformat", "aformat", "V1", "A2", "V3"), setup.muxer!!.calls.toList())
        setup.publisher.stop()
    }

    @Test
    fun statsReportSenderCounters() {
        val setup = Setup()
        setup.connect()
        setup.publisher.writeVideo(tsFrame(50, 1, 1))
        await(what = "rtt measured") { setup.publisher.stats().rttMs == 40.0 }
        val stats = setup.publisher.stats()
        assertTrue(stats.bytesSent > 50 * 188)
        assertEquals(0L, stats.queuedBytes)
        assertEquals(0L, stats.retransmittedPackets)
        assertEquals(0L, stats.droppedVideoFrames)
        assertTrue(setup.publisher.srtStats()!!.queuedPackets == 0)
        setup.publisher.stop()
    }

    @Test
    fun stopIsSilentSendsShutdownAndReleasesResources() {
        val setup = Setup()
        setup.connect()
        setup.publisher.stop()
        assertTrue(setup.peer!!.closed)
        assertEquals(SRT_TYPE_SHUTDOWN, controlType(setup.peer!!.received.last()))
        await(what = "engine thread ends") { publisherThreads().none { it.name == "srt-publisher" } }
        assertEquals(0, setup.listener.disconnects.size)
        setup.publisher.stop()
        setup.publisher.writeVideo(tsFrame(1, 1, 1))
        assertEquals(0, setup.listener.disconnects.size)
    }

    @Test
    fun serverShutdownDisconnectsExactlyOnce() {
        val setup = Setup()
        setup.connect()
        setup.peer!!.reply(controlHeader(SRT_TYPE_SHUTDOWN, size = 20))
        assertTrue(setup.listener.disconnected.await(5, TimeUnit.SECONDS))
        setup.publisher.stop()
        Thread.sleep(100)
        assertEquals(listOf("SRT server closed the connection"), setup.listener.disconnects.toList())
        assertTrue(setup.peer!!.closed)
        await(what = "engine thread ends") { publisherThreads().none { it.name == "srt-publisher" } }
    }

    @Test
    fun handshakeTimeoutIsReportedOnce() {
        val setup = Setup(handshake = false)
        setup.publisher.start()
        assertTrue(setup.listener.disconnected.await(8, TimeUnit.SECONDS))
        assertEquals(listOf("SRT handshake timed out"), setup.listener.disconnects.toList())
        assertEquals(0, setup.listener.connectedCount)
        setup.publisher.stop()
    }

    @Test
    fun invalidUrlsAreReportedThroughTheListener() {
        val cases = mapOf(
            "srt://host" to "SRT URL needs a host and a port",
            "http://host:1" to "SRT URL must start with srt:// or srtla://",
            "srt://host:1?passphrase=abcdefghij" to "SRT encryption (passphrase) is not supported",
            "srtla://host:1" to "srtla:// needs network links",
            "srt://host:1?streamid=" + "x".repeat(600) to "SRT stream id is longer than 512 bytes",
        )
        for ((url, message) in cases) {
            val setup = Setup(url)
            setup.publisher.start()
            assertTrue(setup.listener.disconnected.await(5, TimeUnit.SECONDS), url)
            assertEquals(listOf(message), setup.listener.disconnects.toList(), url)
            setup.publisher.stop()
        }
    }

    @Test
    fun unresolvableHostIsReported() {
        val setup = Setup(resolver = { host, _ -> throw UnknownHostException(host) })
        setup.publisher.start()
        assertTrue(setup.listener.disconnected.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("Failed to resolve 127.0.0.1"), setup.listener.disconnects.toList())
        setup.publisher.stop()
    }

    @Test
    fun stopWhileTheHostIsStillResolvingOpensNoTransportAfterwards() {
        val resolving = CountDownLatch(1)
        val release = CountDownLatch(1)
        val resolved = CountDownLatch(1)
        val setup = Setup(resolver = { _, port ->
            resolving.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                }
            }
            resolved.countDown()
            InetSocketAddress("127.0.0.1", port)
        })
        setup.publisher.start()
        assertTrue(resolving.await(5, TimeUnit.SECONDS))
        setup.publisher.stop()
        release.countDown()
        assertTrue(resolved.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "srt-publisher" } && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(null, setup.peer)
    }

    @Test
    fun overflowWhileTheSenderIsStalledDropsVideoUntilTheNextKeyFrameButKeepsAudio() {
        val setup = Setup(maximumQueuedBytes = 20_000)
        setup.connect()
        val release = CountDownLatch(1)
        await(what = "muxer created") { setup.muxer != null }
        setup.muxer!!.blockVideo = release
        setup.publisher.writeVideo(tsFrame(50, 1, 1))
        assertTrue(setup.muxer!!.entered.await(5, TimeUnit.SECONDS))
        setup.publisher.writeVideo(tsFrame(50, 2, 2, key = false))
        setup.publisher.writeAudio(tsFrame(2, 3, 3, key = false))
        setup.publisher.writeVideo(tsFrame(50, 4, 4, key = false))
        setup.publisher.writeVideo(tsFrame(50, 5, 5, key = false))
        setup.publisher.writeVideo(tsFrame(50, 6, 6, key = false))
        setup.publisher.writeAudio(tsFrame(2, 7, 7, key = false))
        setup.publisher.writeVideo(tsFrame(50, 8, 8, key = true))
        setup.publisher.writeVideo(tsFrame(50, 9, 9, key = false))
        assertEquals(4L, setup.publisher.stats().droppedVideoFrames)
        release.countDown()
        await(what = "queue drained") { setup.muxer!!.calls.size >= 5 && setup.publisher.stats().queuedBytes == 0L }
        assertEquals(listOf("V1", "A3", "A7", "V8", "V9"), setup.muxer!!.calls.toList())
        setup.publisher.stop()
    }

    @Test
    fun stopBeforeStartMakesThePublisherDead() {
        val setup = Setup()
        setup.publisher.stop()
        setup.publisher.start()
        Thread.sleep(50)
        assertEquals(0, setup.listener.connectedCount)
        assertEquals(0, setup.listener.disconnects.size)
        assertTrue(publisherThreads().isEmpty())
    }

    @Test
    fun repeatedStartStopLeavesNoThreadsOrSockets() {
        repeat(25) {
            val listener = RecordingListener()
            val publisher = SrtPublisher("srt://127.0.0.1:9?latency=500", listener)
            publisher.start()
            Thread.sleep(20)
            publisher.stop()
        }
        await(what = "all publisher threads end") { publisherThreads().isEmpty() }
    }
}
