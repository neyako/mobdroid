package com.opirl.app.engine.rist

import com.opirl.app.engine.flv.ElementaryStreams
import com.opirl.app.engine.flv.GeneratedAudio
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srtla.TestLink
import com.opirl.app.engine.srtla.TestLinkProvider
import com.opirl.app.engine.srt.countAudioPackets
import com.opirl.app.engine.srt.countVideoFrames
import com.opirl.app.engine.srt.decodeErrors
import com.opirl.app.engine.srt.freeUdpPort
import com.opirl.app.engine.srt.scratchDirectory
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

private class Receiver(
    val port: Int,
    val video: GeneratedVideo,
    val audio: GeneratedAudio,
    val output: File,
    val log: File,
    val process: Process,
    val directory: File,
) {
    fun verify() {
        assertTrue(awaitFfmpeg(process), "ffmpeg receiver ends: ${log.readText()}")
        assertEquals(video.frames.size, countVideoFrames(output), log.readText())
        assertEquals(expectedAudioFrames(video, audio), countAudioPackets(output), log.readText())
        assertEquals("", decodeErrors(output))
    }
}

private fun withReceiver(profile: String, body: (Receiver) -> Unit) {
    assumeTrue(ffmpegWithRist(), "ffmpeg with RIST support is required")
    val video = ElementaryStreams.h264(false)
    val audio = ElementaryStreams.aac()
    val directory = scratchDirectory("rist-e2e")
    val port = if (profile == "simple") freeEvenUdpPortPair() else freeUdpPort()
    val output = File(directory, "out.ts")
    val log = File(directory, "receiver.log")
    val process = startRistReceiver(port, output, log, profile)
    try {
        body(Receiver(port, video, audio, output, log, process, directory))
    } finally {
        process.destroyForcibly()
        directory.deleteRecursively()
    }
}

class RistFfmpegTest {
    private fun deliversEveryFrame(profile: String) = withReceiver(profile) { receiver ->
        val listener = RecordingListener()
        val publisher = RistPublisher("rist://127.0.0.1:${receiver.port}?profile=$profile&buffer=500", listener)
        publisher.setVideoFormat(receiver.video.format)
        publisher.setAudioFormat(receiver.audio.format)
        publisher.start()
        assertTrue(listener.connected.await(15, TimeUnit.SECONDS), "connects: ${receiver.log.readText()}")
        feedPaced(publisher, receiver.video, receiver.audio)
        receiver.verify()
        publisher.stop()
        assertEquals(1, listener.connects.get())
        assertEquals(0, listener.disconnects.size)
        assertTrue(publisher.stats().bytesSent > 0)
    }

    @Test
    @Timeout(120)
    fun mainProfileDeliversEveryFrameToLibrist() = deliversEveryFrame("main")

    @Test
    @Timeout(120)
    fun simpleProfileDeliversEveryFrameToLibrist() = deliversEveryFrame("simple")

    @Test
    @Timeout(120)
    fun retransmissionRecoversDroppedPackets() = withReceiver("main") { receiver ->
        UdpProxy(InetSocketAddress("127.0.0.1", receiver.port), drop = lossFilter(0.05, 7, ::mainDataPacket)).use { proxy ->
            val listener = RecordingListener()
            val publisher = RistPublisher("rist://127.0.0.1:${proxy.port}?buffer=500", listener)
            publisher.setVideoFormat(receiver.video.format)
            publisher.setAudioFormat(receiver.audio.format)
            publisher.start()
            assertTrue(listener.connected.await(15, TimeUnit.SECONDS), "connects: ${receiver.log.readText()}")
            feedPaced(publisher, receiver.video, receiver.audio)
            receiver.verify()
            val stats = publisher.stats()
            publisher.stop()
            assertTrue(proxy.dropped.get() > 0, "the proxy dropped packets")
            assertTrue(stats.retransmittedPackets >= proxy.dropped.get(), "${stats.retransmittedPackets} of ${proxy.dropped.get()}")
            assertEquals(0, listener.disconnects.size)
        }
    }

    @Test
    @Timeout(120)
    fun bondingSendsOverEveryLinkAndSurvivesLosingOne() = withReceiver("main") { receiver ->
        val wifi = TestLink("wifi", LinkType.WIFI, "127.0.0.1")
        val cellular = TestLink("cellular", LinkType.CELLULAR, "127.0.0.1")
        val links = TestLinkProvider(wifi, cellular)
        val listener = RecordingListener()
        val publisher = RistPublisher("rist://127.0.0.1:${receiver.port}?buffer=500", listener, links)
        publisher.setVideoFormat(receiver.video.format)
        publisher.setAudioFormat(receiver.audio.format)
        publisher.start()
        assertTrue(listener.connected.await(15, TimeUnit.SECONDS), "connects: ${receiver.log.readText()}")
        await(5000, "both links connected") { publisher.linkUsage().size == 2 && publisher.linkUsage().all { it.rttMs >= 0 } }
        var both = emptyList<Long>()
        feedPaced(publisher, receiver.video, receiver.audio) { index ->
            if (index == receiver.video.frames.size / 2) {
                both = publisher.linkUsage().map { it.bytesSent }
                links.set(wifi)
            }
        }
        receiver.verify()
        val after = publisher.linkUsage()
        publisher.stop()
        assertEquals(2, both.size)
        assertTrue(both.all { it > 20_000 }, "both links carried media: $both")
        assertEquals(listOf("wifi"), after.map { it.id })
        assertEquals(0, cellular.openSockets)
        assertEquals(0, listener.disconnects.size)
    }
}
