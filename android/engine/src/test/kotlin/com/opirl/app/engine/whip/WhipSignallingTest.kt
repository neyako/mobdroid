package com.opirl.app.engine.whip

import com.opirl.app.engine.flv.ElementaryStreams
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srt.freeUdpPort
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Timeout

class WhipSignallingTest {
    private val server = MockWebServer().apply { start() }
    private val url get() = "whip://127.0.0.1:${server.port}/live/whip"

    @AfterTest
    fun close() {
        server.close()
    }

    private fun answer() = MockResponse.Builder()
        .code(201)
        .addHeader("Content-Type", "application/sdp")
        .addHeader("Location", "/live/whip/session-1")
        .body(ANSWER_SDP.replace("CANDIDATE_PORT", freeUdpPort().toString()))
        .build()

    @Test
    @Timeout(30)
    fun aRefusedTokenEndsTheSessionOnce() {
        server.enqueue(MockResponse(code = 401))
        val listener = WhipListener()
        val publisher = WhipPublisher(url, mapOf("Authorization" to "Bearer wrong"), listener)
        publisher.setVideoFormat(ElementaryStreams.h264(false).format)
        publisher.start()
        assertTrue(listener.disconnected.await(10, TimeUnit.SECONDS))
        Thread.sleep(200)
        assertEquals(listOf("WHIP server returned HTTP status 401"), listener.disconnects.toList())
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer wrong", request.headers["Authorization"])
        assertEquals("application/sdp", request.headers["Content-Type"]?.substringBefore(';'))
        assertTrue(request.body!!.utf8().contains("a=rtpmap:96 H264/90000"))
        await(2000, "threads end") { whipThreads().isEmpty() }
    }

    @Test
    @Timeout(30)
    fun stopDeletesTheSessionAndLeavesNothingBehind() {
        server.enqueue(answer())
        server.enqueue(MockResponse(code = 200))
        val listener = WhipListener()
        val publisher = WhipPublisher(url, mapOf("Authorization" to "Bearer token"), listener)
        publisher.setVideoFormat(ElementaryStreams.h264(false).format)
        publisher.start()
        assertEquals("POST", server.takeRequest(10, TimeUnit.SECONDS)!!.method)
        Thread.sleep(300)
        val started = System.nanoTime()
        publisher.stop()
        assertTrue(System.nanoTime() - started < 2_500_000_000L)
        val delete = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("DELETE", delete.method)
        assertEquals("/live/whip/session-1", delete.url.encodedPath)
        assertEquals("Bearer token", delete.headers["Authorization"])
        await(2000, "threads end") { whipThreads().isEmpty() }
        assertEquals(emptyList(), listener.disconnects.toList())
        assertEquals(0, listener.connects.get())
    }

    @Test
    @Timeout(30)
    fun anAnswerWithoutTheOfferedCodecEndsTheSession() {
        server.enqueue(answer())
        server.enqueue(MockResponse(code = 200))
        val listener = WhipListener()
        val publisher = WhipPublisher(url, emptyMap(), listener)
        publisher.setVideoFormat(ElementaryStreams.hevc(false).format)
        publisher.start()
        assertTrue(server.takeRequest(10, TimeUnit.SECONDS)!!.body!!.utf8().contains("a=rtpmap:97 H265/90000"))
        assertTrue(listener.disconnected.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("WHIP server refused the HEVC video"), listener.disconnects.toList())
    }

    @Test
    @Timeout(30)
    fun aServerThatNeverAnswersTheChecksTimesOut() {
        server.enqueue(answer())
        server.enqueue(MockResponse(code = 200))
        val listener = WhipListener()
        val publisher = WhipPublisher(url, emptyMap(), listener, OkHttpClient(), {}, WhipEnvironment(connectTimeoutMs = 1500, formatWaitMs = 100))
        publisher.start()
        assertTrue(listener.disconnected.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("WHIP connection timed out"), listener.disconnects.toList())
        assertEquals("POST", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest(5, TimeUnit.SECONDS)!!.method)
    }
}
