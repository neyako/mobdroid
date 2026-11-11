package com.opirl.app.engine.chat.youtube

import com.opirl.app.engine.chat.ChatClient
import com.opirl.app.engine.chat.ChatListener
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val YOUTUBE_URL = "https://www.youtube.com"
private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:124.0) Gecko/20100101 Firefox/124.0"
private const val CONSENT_COOKIE = "CONSENT=YES+1"
private const val CLIENT_VERSION = "2.20210128.02.00"
private const val MAX_BODY_BYTES = 8L * 1024 * 1024
private const val CALL_TIMEOUT_S = 30L
private const val STOP_JOIN_MS = 2_000L
private const val SEEN_MESSAGE_IDS = 2_048
private const val SEND_UNSUPPORTED = "Sending to YouTube chat needs a Google login"
private val JSON_TYPE = "application/json".toMediaType()

internal class YouTubeChatTimings(
    val initialPollMs: Long = 2_000,
    val minPollMs: Long = 200,
    val maxPollMs: Long = 3_000,
    val retryInitialMs: Long = 5_000,
    val retryMaxMs: Long = 30_000,
)

private class ChatUnavailable(message: String) : Exception(message)

private class RecentIds(private val capacity: Int) {
    private val ids = LinkedHashSet<String>()

    fun add(id: String): Boolean {
        if (!ids.add(id)) {
            return false
        }
        if (ids.size > capacity) {
            val oldest = ids.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }
}

class YouTubeChatClient internal constructor(
    source: String,
    private val listener: ChatListener,
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val timings: YouTubeChatTimings,
) : ChatClient {
    constructor(source: String, listener: ChatListener, http: OkHttpClient) :
        this(source, listener, http, YOUTUBE_URL.toHttpUrl(), YouTubeChatTimings())

    private val target = parseTarget(source)
    private val lock = Any()
    private var run: Run? = null

    override val canSend: Boolean
        get() = false

    override fun start() {
        synchronized(lock) {
            if (run == null) {
                run = Run().also { it.start() }
            }
        }
    }

    override fun stop() {
        val current = synchronized(lock) {
            run.also { run = null }
        }
        current?.stop()
    }

    override fun send(text: String, onResult: (error: String?) -> Unit) {
        onResult(SEND_UNSUPPORTED)
    }

    private inner class Run : Runnable {
        private val thread = Thread(this, "youtube-chat").apply { isDaemon = true }
        private val monitor = ReentrantLock()
        private val woken = monitor.newCondition()
        private val client = http.newBuilder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES))
            .build()
        private val pollUrl = baseUrl.newBuilder()
            .addPathSegments("youtubei/v1/live_chat/get_live_chat")
            .addQueryParameter("prettyPrint", "false")
            .build()
        private val seen = RecentIds(SEEN_MESSAGE_IDS)
        private var stopped = false
        private var call: Call? = null
        private var retryMs = timings.retryInitialMs
        private var lastReason: String? = null

        fun start() {
            thread.start()
        }

        fun stop() {
            monitor.withLock {
                stopped = true
                call?.cancel()
                woken.signalAll()
            }
            if (Thread.currentThread() !== thread) {
                try {
                    thread.join(STOP_JOIN_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            client.connectionPool.evictAll()
        }

        override fun run() {
            try {
                while (!isStopped()) {
                    try {
                        session()
                    } catch (error: Exception) {
                        if (isStopped()) {
                            return
                        }
                        lose(describe(error))
                    }
                    if (!pause(retryMs)) {
                        return
                    }
                    retryMs = minOf(retryMs * 2, timings.retryMaxMs)
                }
            } finally {
                monitor.withLock { stopped = true }
            }
        }

        private fun session() {
            val videoId = resolveVideoId()
            var continuation = fetchContinuation(videoId)
            var delayMs = timings.initialPollMs
            var connected = false
            while (true) {
                val page = poll(continuation)
                if (!connected && page.continuation != null) {
                    connected = true
                    retryMs = timings.retryInitialMs
                    lastReason = null
                    deliver { listener.onConnected() }
                }
                val messages = dispatch(page.events)
                continuation = page.continuation ?: throw ChatUnavailable("Chat ended")
                delayMs = nextPollDelayMs(delayMs, messages, timings)
                if (!pause(delayMs)) {
                    return
                }
            }
        }

        private fun resolveVideoId(): String =
            when (target) {
                is YouTubeTarget.Video -> target.id
                is YouTubeTarget.Channel -> {
                    val url = baseUrl.newBuilder().addPathSegment(target.handle).addPathSegment("live").build()
                    findVideoId(fetchPage(url)) ?: throw ChatUnavailable("No live stream")
                }
            }

        private fun fetchContinuation(videoId: String): String {
            val url = baseUrl.newBuilder()
                .addPathSegment("live_chat")
                .addQueryParameter("is_popout", "1")
                .addQueryParameter("v", videoId)
                .build()
            return findContinuation(fetchPage(url)) ?: throw ChatUnavailable("No live chat")
        }

        private fun fetchPage(url: HttpUrl): String {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Cookie", CONSENT_COOKIE)
                .build()
            return execute(request) { it.readText() }
        }

        private fun poll(continuation: String): LiveChatPage {
            val body = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", CLIENT_VERSION)
                    }
                }
                put("continuation", continuation)
            }
            val request = Request.Builder()
                .url(pollUrl)
                .header("User-Agent", USER_AGENT)
                .post(body.toString().toRequestBody(JSON_TYPE))
                .build()
            return execute(request) { parseLiveChat(it.readText(), System.currentTimeMillis()) }
        }

        private fun <T> execute(request: Request, read: (Response) -> T): T {
            val newCall = client.newCall(request)
            monitor.withLock {
                if (stopped) {
                    throw IOException("Stopped")
                }
                call = newCall
            }
            try {
                return newCall.execute().use { response ->
                    if (!response.isSuccessful) {
                        throw ChatUnavailable("HTTP ${response.code}")
                    }
                    read(response)
                }
            } finally {
                monitor.withLock { call = null }
            }
        }

        private fun dispatch(events: List<LiveChatEvent>): Int {
            var messages = 0
            for (event in events) {
                when (event) {
                    is LiveChatEvent.Message -> {
                        val id = event.message.id
                        if (id == null || seen.add(id)) {
                            messages++
                            deliver { listener.onMessage(event.message) }
                        }
                    }
                    is LiveChatEvent.MessageDeleted -> deliver { listener.onMessageDeleted(event.messageId) }
                    is LiveChatEvent.UserMessagesDeleted -> deliver { listener.onUserMessagesDeleted(event.userId) }
                }
            }
            return messages
        }

        private fun lose(reason: String) {
            if (reason != lastReason) {
                lastReason = reason
                deliver {
                    try {
                        listener.onDisconnected(reason)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        private fun describe(error: Exception): String =
            when (error) {
                is ChatUnavailable -> error.message.orEmpty()
                is IOException -> error.message ?: "Network error"
                is SerializationException -> "Unexpected response"
                else -> error.message ?: error.javaClass.simpleName
            }

        private fun pause(ms: Long): Boolean {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
            try {
                monitor.withLock {
                    while (!stopped) {
                        val remaining = deadline - System.nanoTime()
                        if (remaining <= 0) {
                            return true
                        }
                        woken.awaitNanos(remaining)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            return false
        }

        private fun isStopped(): Boolean = monitor.withLock { stopped }

        private inline fun deliver(block: () -> Unit) {
            monitor.withLock {
                if (!stopped) {
                    block()
                }
            }
        }
    }
}

private fun Response.readText(): String {
    val source = body.source()
    source.request(MAX_BODY_BYTES + 1)
    if (source.buffer.size > MAX_BODY_BYTES) {
        throw IOException("Response too large")
    }
    return source.buffer.readUtf8()
}
