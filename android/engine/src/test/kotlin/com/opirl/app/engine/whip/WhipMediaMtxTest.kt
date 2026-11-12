package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.ElementaryStreams
import com.opirl.app.engine.flv.FfmpegTools
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srt.countAudioPackets
import com.opirl.app.engine.srt.countVideoFrames
import com.opirl.app.engine.srt.decodeErrors
import com.opirl.app.engine.rist.UdpProxy
import com.opirl.app.engine.rist.lossFilter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

class WhipMediaMtxTest {
    private fun withServer(body: (MediaMtx) -> Unit) {
        val binary = MediaMtx.binary()
        assumeTrue(binary != null && FfmpegTools.available, "MEDIAMTX and ffmpeg are required")
        MediaMtx(binary!!).use(body)
    }

    private fun publishAndReadBack(
        server: MediaMtx,
        video: GeneratedVideo,
        codec: String,
        http: OkHttpClient = OkHttpClient(),
        environment: WhipEnvironment = WhipEnvironment(),
        intact: Boolean = true,
    ): WhipPublisher {
        val listener = WhipListener()
        val keyFrameRequests = AtomicInteger()
        val publisher = WhipPublisher(server.whipUrl("live"), emptyMap(), listener, http, { keyFrameRequests.incrementAndGet() }, environment)
        val feed = LoopingFeed(publisher, video, opusAudio())
        feed.start()
        publisher.start()
        try {
            assertTrue(listener.connected.await(15, TimeUnit.SECONDS), "connects: ${listener.disconnects} ${server.log.readText()}")
            assertTrue(keyFrameRequests.get() >= 1)
            val output = FfmpegTools.file("whip-$codec-${System.nanoTime()}.mkv")
            val reader = server.record("live", 3, output)
            assertTrue(reader != null, "the stream can be read: ${server.log.readText()}")
            assertTrue(reader!!.waitFor(30, TimeUnit.SECONDS), "the reader ends")
            val streams = FfmpegTools.probeStreams(output, countFrames = false)
            assertEquals(setOf(codec, "opus"), streams.mapNotNull { it["codec_name"] }.toSet(), streams.toString())
            assertTrue(countVideoFrames(output) >= 55, "video frames ${countVideoFrames(output)}")
            assertTrue(countAudioPackets(output) >= 90, "audio packets ${countAudioPackets(output)}")
            if (intact) {
                assertEquals("", decodeErrors(output))
            }
            assertEquals(0, listener.disconnects.size, listener.disconnects.toString())
            assertTrue(publisher.stats().bytesSent > 100_000)
        } catch (error: Throwable) {
            publisher.stop()
            throw error
        } finally {
            feed.stop()
        }
        return publisher
    }

    @Test
    @Timeout(120)
    fun h264AndOpusReachAPionServer() = withServer { server ->
        val publisher = publishAndReadBack(server, ElementaryStreams.h264(false), "h264")
        publisher.stop()
        Thread.sleep(1500)
        assertEquals("", describeWhipThreads())
        await(5000, "the session is deleted") { server.log.readText().contains("closed") }
    }

    @Test
    @Timeout(120)
    fun hevcReachesAPionServer() = withServer { server ->
        publishAndReadBack(server, ElementaryStreams.hevc(false), "hevc").stop()
    }

    @Test
    @Timeout(120)
    fun lostPacketsAreSentAgain() = withServer { server ->
        val drop = lossFilter(0.03, 11) { buffer, length -> length > 12 && (buffer[0].toInt() and 0xC0) == 0x80 && (buffer[1].toInt() and 0x7F) == 96 }
        UdpProxy(InetSocketAddress("127.0.0.1", server.udpPort), drop = drop).use { proxy ->
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val text = body.string().replace(" ${server.udpPort} typ host", " ${proxy.port} typ host")
                response.newBuilder().body(text.toResponseBody(body.contentType())).build()
            }.build()
            val unreachable = WhipEnvironment(localAddresses = { listOf(InetAddress.getByName("192.0.2.1")) })
            val publisher = publishAndReadBack(server, ElementaryStreams.h264(false), "h264", http, unreachable, intact = false)
            val stats = publisher.stats()
            publisher.stop()
            assertTrue(proxy.dropped.get() > 0, "the proxy dropped packets")
            assertTrue(stats.retransmittedPackets > 0, "retransmitted ${stats.retransmittedPackets} of ${proxy.dropped.get()}")
        }
    }

    @Test
    @Timeout(120)
    fun aServerThatGoesAwayEndsTheSessionOnce() = withServer { server ->
        val listener = WhipListener()
        val publisher = WhipPublisher(
            server.whipUrl("live"),
            emptyMap(),
            listener,
            OkHttpClient(),
            {},
            WhipEnvironment(consentIntervalNanos = 200_000_000L, consentTimeoutNanos = 1_500_000_000L),
        )
        val feed = LoopingFeed(publisher, ElementaryStreams.h264(false), opusAudio())
        feed.start()
        publisher.start()
        try {
            assertTrue(listener.connected.await(15, TimeUnit.SECONDS), "connects: ${listener.disconnects}")
            Thread.sleep(500)
            server.kill()
            assertTrue(listener.disconnected.await(10, TimeUnit.SECONDS), "notices the server is gone")
            Thread.sleep(500)
            assertEquals(1, listener.disconnects.size, listener.disconnects.toString())
        } finally {
            feed.stop()
            publisher.stop()
        }
    }
}
