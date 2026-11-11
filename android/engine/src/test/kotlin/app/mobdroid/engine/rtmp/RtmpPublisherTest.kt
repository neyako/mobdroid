package app.mobdroid.engine.rtmp

import app.mobdroid.engine.flv.annexB
import app.mobdroid.engine.flv.hex
import app.mobdroid.engine.flv.toHex
import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import app.mobdroid.engine.net.PublisherListener
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class RtmpPublisherTest {
    private val sps = hex("6742c00dda0507ec0440000003004000000f03c50aa8")
    private val pps = hex("68ce0fc8")
    private val aac = AudioFormat(48000, 2, hex("1190"))
    private val sequenceHeader = hex("0a0b000000043cffbc02f80040")

    private fun h264() = VideoFormat(VideoCodec.H264, 640, 360, 30, annexB(sps, pps))

    private fun video(index: Int, key: Boolean, size: Int = 200, ptsUs: Long = index * 33_333L, base: Long = 0): EncodedFrame {
        val nalu = ByteArray(size) { 0x55 }
        nalu[0] = if (key) 0x65 else 0x41
        nalu[1] = (((index ushr 7) and 0x7F) + 1).toByte()
        nalu[2] = ((index and 0x7F) + 1).toByte()
        return EncodedFrame(annexB(nalu), base + ptsUs, key)
    }

    private fun audio(index: Int, base: Long = 0) = EncodedFrame(ByteArray(120) { 7 }, base + index * 21_333L, true)

    private fun indexOf(media: ReceivedMedia): Int {
        val nalu = media.body.copyOfRange(9, media.body.size)
        return ((nalu[1].toInt() - 1) shl 7) or (nalu[2].toInt() - 1)
    }

    private fun newPublisher(server: FakeRtmpServer, listener: RecordingListener, path: String = "live", key: String = "mykey", timeoutMs: Long = 5000) =
        RtmpPublisher("rtmp://127.0.0.1:${server.port}/$path", key, listener, timeoutMs = timeoutMs)

    private fun publisherThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("rtmp-") }

    @Test
    fun performsTheStandardPublishSequenceWithMetadataAndSequenceHeaders() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            publisher.start()
            assertTrue(listener.awaitConnected(), "${listener.reasons}")
            assertTrue(waitUntil { server.media.size >= 3 })

            assertEquals(listOf("connect", "releaseStream", "FCPublish", "createStream", "publish"), server.commandNames())
            val connect = server.commands[0].arguments[0] as Map<*, *>
            assertEquals("live", connect["app"])
            assertEquals("rtmp://127.0.0.1:${server.port}/live", connect["tcUrl"])
            assertEquals(null, connect["fourCcList"])
            assertEquals(listOf(null, "mykey"), server.commands[1].arguments)
            assertEquals(listOf(null, "mykey", "live"), server.commands[4].arguments)
            assertEquals(1, server.commands[4].streamId)
            assertEquals(listOf(1, 2, 3, 4), server.commands.map { it.transaction }.take(4))

            val setChunkSize = server.controls.first { it.type == MSG_SET_CHUNK_SIZE }
            assertEquals("00001000", setChunkSize.body.toHex())
            assertTrue(server.controls.any { it.type == MSG_WINDOW_ACK_SIZE && it.body.toHex() == "000186a0" })

            val data = server.media[0]
            assertEquals(MSG_DATA_AMF0, data.type)
            val amf = Amf0.Reader(data.body)
            assertEquals("@setDataFrame", amf.read())
            assertEquals("onMetaData", amf.read())
            val metadata = amf.read() as Map<*, *>
            assertEquals(640.0, metadata["width"])
            assertEquals(360.0, metadata["height"])
            assertEquals(30.0, metadata["framerate"])
            assertEquals(7.0, metadata["videocodecid"])
            assertEquals(10.0, metadata["audiocodecid"])
            assertEquals(48000.0, metadata["audiosamplerate"])
            assertEquals(true, metadata["stereo"])

            val videoHeader = server.mediaOfType(MSG_VIDEO).first()
            assertEquals(1, videoHeader.streamId)
            assertEquals(0L, videoHeader.timestamp)
            assertTrue(videoHeader.body.toHex().startsWith("1700000000" + "01" + "42c00d"))
            assertEquals("af001190", server.mediaOfType(MSG_AUDIO).first().body.toHex())
            publisher.stop()
        }
    }

    @Test
    fun enhancedCodecsAreDeclaredInTheConnectCommandAndMetadata() {
        val hevcConfig = annexB(
            hex("40010c01ffff01600000030090000003000003003cba0240"),
            hex("42010101600000030090000003000003003ca00a080f165ba4a4c2f0168080000003008000000f04"),
            hex("4401c073c189"),
        )
        val cases = listOf(
            Triple(VideoFormat(VideoCodec.HEVC, 640, 360, 30, hevcConfig), "hvc1" to 1752589105.0, "9068766331"),
            Triple(VideoFormat(VideoCodec.AV1, 640, 360, 30, sequenceHeader), "av01" to 1635135537.0, "9061763031"),
        )
        for ((format, expected, firstBytes) in cases) {
            FakeRtmpServer().start().use { server ->
                val listener = RecordingListener()
                val publisher = newPublisher(server, listener)
                publisher.setVideoFormat(format)
                publisher.start()
                assertTrue(listener.awaitConnected(), "${listener.reasons}")
                assertTrue(waitUntil { server.media.size >= 2 })
                val connect = server.commands[0].arguments[0] as Map<*, *>
                assertEquals(listOf(expected.first), connect["fourCcList"])
                val metadata = Amf0.Reader(server.media[0].body).let { it.read(); it.read(); it.read() } as Map<*, *>
                assertEquals(expected.second, metadata["videocodecid"])
                assertTrue(server.mediaOfType(MSG_VIDEO).first().body.toHex().startsWith(firstBytes))
                assertEquals(null, metadata["audiocodecid"])
                publisher.stop()
            }
        }
    }

    @Test
    fun appAndStreamKeyFollowTheUrlRule() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener, path = "app/live_abc", key = "")
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected(), "${listener.reasons}")
            val connect = server.commands[0].arguments[0] as Map<*, *>
            assertEquals("app", connect["app"])
            assertEquals("rtmp://127.0.0.1:${server.port}/app", connect["tcUrl"])
            assertEquals(listOf(null, "live_abc", "live"), server.commands.first { it.name == "publish" }.arguments)
            publisher.stop()
        }
    }

    private class Failure(val name: String, val behavior: Behavior, val expectedReason: String, val timeoutMs: Long = 5000)

    @Test
    fun serverRejectionsAreReportedExactlyOnceAndNeverCallBackAfterStop() {
        val failures = listOf(
            Failure("connect error", Behavior(connectCode = "NetConnection.Connect.Rejected", connectAsError = true), "NetConnection.Connect.Rejected: rejected"),
            Failure("connect result with failure code", Behavior(connectCode = "NetConnection.Connect.Rejected"), "NetConnection.Connect.Rejected"),
            Failure("publish bad name", Behavior(publishCode = "NetStream.Publish.BadName", publishLevel = "error"), "NetStream.Publish.BadName: Started publishing"),
            Failure("publish denied as status", Behavior(publishCode = "NetStream.Publish.Denied"), "NetStream.Publish.Denied"),
            Failure("connection closed after publish start", Behavior(closeAfterPublishStart = true), "Connection closed by server|Connection reset|Broken pipe"),
            Failure("silent server", Behavior(answerConnect = false), "Timed out", timeoutMs = 700),
        )
        for (failure in failures) {
            FakeRtmpServer(behavior = failure.behavior).start().use { server ->
                val listener = RecordingListener()
                val publisher = newPublisher(server, listener, timeoutMs = failure.timeoutMs)
                publisher.setVideoFormat(h264())
                publisher.start()
                assertTrue(listener.awaitDisconnected(), "${failure.name}: no disconnect")
                Thread.sleep(200)
                assertEquals(1, listener.reasons.size, "${failure.name}: ${listener.reasons}")
                assertTrue(Regex(failure.expectedReason).containsMatchIn(listener.reasons[0]), "${failure.name}: ${listener.reasons[0]}")
                publisher.writeVideo(video(0, true))
                publisher.stop()
                assertEquals(1, listener.reasons.size, failure.name)
                assertTrue(publisherThreads().isEmpty(), "${failure.name}: leaked ${publisherThreads()}")
            }
        }
    }

    @Test
    fun connectionRefusedIsReportedOnce() {
        val port = ServerSocket(0).use { it.localPort }
        val listener = RecordingListener()
        val publisher = RtmpPublisher("rtmp://127.0.0.1:$port/live", "k", listener, timeoutMs = 2000)
        publisher.start()
        assertTrue(listener.awaitDisconnected())
        Thread.sleep(100)
        assertEquals(1, listener.reasons.size)
        publisher.stop()
        assertTrue(publisherThreads().isEmpty())
    }

    @Test
    fun invalidConfigurationAndMisuseAreRejectedUpFront() {
        assertFailsWith<IllegalArgumentException> { RtmpPublisher("http://x/y", "k", RecordingListener()) }
        assertFailsWith<IllegalArgumentException> { RtmpPublisher("rtmp://host", "", RecordingListener()) }
        FakeRtmpServer().start().use { server ->
            val publisher = newPublisher(server, RecordingListener())
            publisher.start()
            assertFailsWith<IllegalStateException> { publisher.start() }
            publisher.stop()
        }
    }

    @Test
    fun stoppingWhileWaitingForTheVideoFormatReturnsPromptlyWithoutLeavingThreads() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.start()
            assertTrue(server.accepted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val started = System.nanoTime()
            publisher.stop()
            assertTrue(System.nanoTime() - started < 1_000_000_000L)
            assertTrue(publisherThreads().isEmpty(), "leaked ${publisherThreads()}")
            assertTrue(server.commands.isEmpty())
            assertTrue(listener.reasons.isEmpty())
        }
    }

    @Test
    fun invalidVideoConfigurationIsReportedAsADisconnect() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(VideoFormat(VideoCodec.H264, 640, 360, 30, ByteArray(0)))
            publisher.start()
            assertTrue(listener.awaitDisconnected())
            assertTrue(listener.reasons.single().contains("SPS"), listener.reasons.single())
            publisher.stop()
        }
    }

    @Test
    fun stopSendsUnpublishQuietlyAndLeavesNoThreadsBehind() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            publisher.start()
            assertTrue(listener.awaitConnected())
            repeat(5) {
                publisher.writeVideo(video(it, it == 0))
                publisher.writeAudio(audio(it))
            }
            assertTrue(waitUntil { server.mediaOfType(MSG_VIDEO).size >= 6 })
            publisher.stop()
            assertTrue(waitUntil { server.commandNames().contains("deleteStream") })
            assertEquals(listOf("FCUnpublish", "deleteStream"), server.commandNames().takeLast(2))
            assertEquals(1.0, server.commands.last().arguments[1])
            Thread.sleep(100)
            assertTrue(listener.reasons.isEmpty(), "${listener.reasons}")
            assertTrue(publisherThreads().isEmpty(), "leaked ${publisherThreads()}")
            publisher.writeVideo(video(9, true))
            publisher.stop()
        }
    }

    @Test
    fun serverErrorStatusWhileStreamingIsReportedOnceAndStopsTheThreads() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected())
            server.sendStatus("NetStream.Publish.BadName")
            assertTrue(listener.awaitDisconnected())
            Thread.sleep(100)
            assertEquals(1, listener.reasons.size)
            assertTrue(listener.reasons[0].startsWith("NetStream.Publish.BadName"))
            assertTrue(waitUntil { publisherThreads().isEmpty() })
            publisher.stop()
            assertEquals(1, listener.reasons.size)
        }
    }

    @Test
    fun answersPingsAndWindowAcknowledgementRequestsAndAcknowledgesReceivedBytes() {
        FakeRtmpServer(behavior = Behavior(pingAfterPublish = true, serverWindowAckSize = 100)).start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected())
            assertTrue(waitUntil { server.controls.any { it.type == MSG_USER_CONTROL && it.body.toHex() == "000701020304" } })
            assertTrue(waitUntil { server.controls.any { it.type == MSG_ACK } })
            val acknowledged = server.controls.first { it.type == MSG_ACK }.body.let {
                ((it[0].toLong() and 0xFF) shl 24) or ((it[1].toLong() and 0xFF) shl 16) or ((it[2].toLong() and 0xFF) shl 8) or (it[3].toLong() and 0xFF)
            }
            assertTrue(acknowledged >= 100, "acknowledged $acknowledged")
            publisher.stop()
        }
    }

    @Test
    fun serverAcknowledgementsDriveRoundTripTimeAndPacketsInFlight() {
        FakeRtmpServer(behavior = Behavior(ackEveryBytes = 4000)).start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected())
            for (i in 0 until 40) {
                publisher.writeVideo(video(i, i % 30 == 0, size = 2000))
                Thread.sleep(5)
            }
            assertTrue(waitUntil { publisher.stats().rttMs > 0.0 })
            assertTrue(waitUntil { publisher.stats().queuedBytes == 0L })
            assertTrue(waitUntil { publisher.stats().packetsInFlight <= 3 }, "in flight ${publisher.stats().packetsInFlight}")
            val stats = publisher.stats()
            assertTrue(stats.bytesSent > 40 * 2000)
            assertTrue(stats.sendBitrateBps > 0)
            publisher.stop()
        }
    }

    @Test
    fun framesQueuedBeforeConnectingStartAtTheLatestKeyFrame() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            for (i in 0 until 45) {
                publisher.writeVideo(video(i, i % 30 == 0))
            }
            for (j in 0 until 70) {
                publisher.writeAudio(audio(j))
            }
            publisher.start()
            assertTrue(listener.awaitConnected())
            assertTrue(waitUntil { publisher.stats().queuedBytes == 0L })
            assertTrue(waitUntil { server.mediaOfType(MSG_VIDEO).size == 16 })
            val frames = server.mediaOfType(MSG_VIDEO).drop(1)
            assertEquals(15, frames.size)
            assertEquals((30..44).toList(), frames.map { indexOf(it) })
            assertTrue(frames.first().body[0].toInt() == 0x17)
            assertEquals(0L, publisher.stats().droppedVideoFrames)
            val keyPtsMs = video(30, true).ptsUs / 1000
            val firstAudioIndex = (0 until 70).first { audio(it).ptsUs >= keyPtsMs * 1000 }
            val audioFrames = server.mediaOfType(MSG_AUDIO).drop(1)
            assertEquals(70 - firstAudioIndex, audioFrames.size)
            assertEquals(0L, frames.first().timestamp)
            publisher.stop()
        }
    }

    @Test
    fun framesWrittenFromTheConnectedCallbackAreNotTrimmed() {
        FakeRtmpServer().start().use { server ->
            lateinit var publisher: RtmpPublisher
            val listener = object : PublisherListener by RecordingListener() {
                override fun onConnected() {
                    for (i in 0 until 45) {
                        publisher.writeVideo(video(i, i % 30 == 0))
                    }
                }
            }
            publisher = RtmpPublisher("rtmp://127.0.0.1:${server.port}/live", "mykey", listener, timeoutMs = 5000)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(waitUntil { server.mediaOfType(MSG_VIDEO).size == 46 })
            assertEquals((0..44).toList(), server.mediaOfType(MSG_VIDEO).drop(1).map { indexOf(it) })
            publisher.stop()
        }
    }

    @Test
    fun timestampsAreRelativeToTheFirstFrameAndMonotonicPerStream() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            publisher.start()
            assertTrue(listener.awaitConnected())
            val base = 1_000_000_000_000_000L
            publisher.writeVideo(video(0, true, base = base))
            publisher.writeAudio(audio(0, base = base + 5_000))
            publisher.writeVideo(video(1, false, base = base))
            publisher.writeAudio(audio(1, base = base + 5_000))
            publisher.writeVideo(EncodedFrame(video(2, false).data, base + 20_000, false))
            assertTrue(waitUntil { server.mediaOfType(MSG_VIDEO).size == 4 && server.mediaOfType(MSG_AUDIO).size == 3 })
            assertEquals(listOf(0L, 0L, 33L, 33L), server.mediaOfType(MSG_VIDEO).map { it.timestamp })
            assertEquals(listOf(0L, 5L, 26L), server.mediaOfType(MSG_AUDIO).map { it.timestamp })
            publisher.stop()
        }
    }

    @Test
    fun aStalledReceiverMakesTheBacklogDropVideoUntilAKeyFrameAndAudioOlderThanTheLimit() {
        val gate = CountDownLatch(1)
        FakeRtmpServer(behavior = Behavior(readGate = gate)).start().use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher("rtmp://127.0.0.1:${server.port}/live", "k", listener, maxBacklogMs = 250, timeoutMs = 60_000)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            publisher.start()
            assertTrue(listener.awaitConnected())
            assertTrue(server.published.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var videoFrames = 0
            var audioFrames = 0
            val deadline = System.nanoTime() + 60_000_000_000L
            while (publisher.stats().droppedVideoFrames < 30 && System.nanoTime() < deadline) {
                publisher.writeVideo(video(videoFrames, videoFrames % 15 == 0, size = 100_000))
                publisher.writeAudio(audio(audioFrames++))
                videoFrames++
                Thread.sleep(8)
            }
            assertTrue(publisher.stats().droppedVideoFrames >= 30, "no drops after $videoFrames frames")
            assertTrue(publisher.stats().queuedBytes <= 16L * 1024 * 1024)
            gate.countDown()
            assertTrue(waitUntil { publisher.stats().queuedBytes == 0L })
            Thread.sleep(300)
            val videos = server.mediaOfType(MSG_VIDEO).drop(1)
            val audios = server.mediaOfType(MSG_AUDIO).drop(1)
            assertTrue(audios.size < audioFrames)
            assertEquals((audioFrames - 1) * 21_333L / 1000, audios.last().timestamp)
            audios.zipWithNext().forEach { (previous, next) -> assertTrue(next.timestamp > previous.timestamp) }
            assertEquals(videoFrames.toLong(), videos.size + publisher.stats().droppedVideoFrames)
            videos.zipWithNext().forEach { (previous, next) ->
                if (indexOf(next) != indexOf(previous) + 1) {
                    assertEquals(0x17, next.body[0].toInt() and 0xFF, "frame ${indexOf(next)} follows a gap and must be a key frame")
                }
            }
            publisher.stop()
        }
    }

    @Test
    fun aSendThatStallsLongerThanTheTimeoutDisconnectsExactlyOnce() {
        val gate = CountDownLatch(1)
        FakeRtmpServer(behavior = Behavior(readGate = gate)).start().use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher("rtmp://127.0.0.1:${server.port}/live", "k", listener, timeoutMs = 1500)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected())
            assertTrue(server.published.await(5, java.util.concurrent.TimeUnit.SECONDS))
            var i = 0
            val deadline = System.nanoTime() + 60_000_000_000L
            while (listener.disconnected.count > 0 && System.nanoTime() < deadline) {
                publisher.writeVideo(video(i, i % 15 == 0, size = 100_000))
                i++
                Thread.sleep(8)
            }
            assertTrue(listener.awaitDisconnected(1))
            Thread.sleep(200)
            gate.countDown()
            assertEquals(1, listener.reasons.size)
            assertTrue(listener.reasons[0].contains("stalled"), listener.reasons[0])
            assertTrue(waitUntil { publisherThreads().isEmpty() })
            publisher.stop()
        }
    }

    @Test
    fun aSessionThatRunsPastTheExtendedTimestampBoundaryStaysContinuousAndBounded() {
        FakeRtmpServer(behavior = Behavior(recordMedia = false)).start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(h264())
            publisher.setAudioFormat(aac)
            publisher.start()
            assertTrue(listener.awaitConnected())
            val frames = 130_000
            val stepUs = 150_000L
            for (i in 0 until frames) {
                publisher.writeVideo(video(i % 16000, i % 30 == 0, size = 2000, ptsUs = i * stepUs))
                publisher.writeAudio(audio(0, base = i * stepUs + 70_000))
                if (i % 500 == 0) {
                    assertTrue(waitUntil { publisher.stats().queuedBytes < 4_000_000 })
                }
            }
            assertTrue(waitUntil(60) { publisher.stats().queuedBytes == 0L })
            assertTrue(waitUntil(60) { server.mediaCount.get() >= 2L * frames + 3 })
            assertEquals(0L, server.timestampRegressions.get())
            val lastVideoMs = server.lastTimestamps[MSG_VIDEO]!!
            assertEquals((frames - 1) * stepUs / 1000, lastVideoMs)
            assertTrue(lastVideoMs > 0x1000000L)
            assertEquals(0L, publisher.stats().droppedVideoFrames)
            publisher.stop()
            assertTrue(listener.reasons.isEmpty())
            assertTrue(publisherThreads().isEmpty())
        }
    }

    private class TestTls(val server: SSLContext, val client: SSLContext)

    private fun generateTls(): TestTls? {
        val keytool = File(System.getProperty("java.home"), "bin/keytool")
        if (!keytool.canExecute()) return null
        val directory = Files.createTempDirectory("rtmps-test").toFile()
        val store = File(directory, "store.p12")
        try {
            val process = ProcessBuilder(
                keytool.path, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "3",
                "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
                "-keystore", store.path, "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            if (process.waitFor() != 0) return null
            val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "changeit".toCharArray()) } }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "changeit".toCharArray()) }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keyStore) }
            return TestTls(
                SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) },
                SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) },
            )
        } finally {
            store.delete()
            directory.delete()
        }
    }

    @Test
    fun rtmpsPublishesOverTlsAndRejectsAnUntrustedCertificate() {
        val tls = generateTls()
        assumeTrue(tls != null, "keytool is not available")
        FakeRtmpServer(tls!!.server).start().use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher("rtmps://127.0.0.1:${server.port}/live", "k", listener, tls.client.socketFactory, timeoutMs = 5000)
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected(), "${listener.reasons}")
            publisher.writeVideo(video(0, true))
            assertTrue(waitUntil { server.mediaOfType(MSG_VIDEO).size >= 2 })
            assertEquals("connect", server.commandNames().first())
            publisher.stop()
            assertTrue(listener.reasons.isEmpty())
        }
        FakeRtmpServer(tls.server).start().use { server ->
            val listener = RecordingListener()
            val publisher = RtmpPublisher("rtmps://127.0.0.1:${server.port}/live", "k", listener, timeoutMs = 5000)
            publisher.start()
            assertTrue(listener.awaitDisconnected())
            assertTrue(listener.reasons.single().startsWith("TLS error"), listener.reasons.single())
            assertTrue(server.commands.isEmpty())
            publisher.stop()
        }
    }

    @Test
    fun theMostRecentAudioAndVideoFormatsAreUsedWhenTheyChangeBeforeConnecting() {
        FakeRtmpServer().start().use { server ->
            val listener = RecordingListener()
            val publisher = newPublisher(server, listener)
            publisher.setVideoFormat(VideoFormat(VideoCodec.H264, 100, 100, 10, annexB(sps, pps)))
            publisher.setVideoFormat(h264())
            publisher.start()
            assertTrue(listener.awaitConnected())
            assertTrue(waitUntil { server.media.isNotEmpty() })
            val metadata = Amf0.Reader(server.media[0].body).let { it.read(); it.read(); it.read() } as Map<*, *>
            assertEquals(640.0, metadata["width"])
            assertContentEquals(byteArrayOf(0x17, 0, 0, 0, 0), server.mediaOfType(MSG_VIDEO).first().body.copyOf(5))
            publisher.stop()
        }
    }
}
