package app.mobdroid.engine.chat.kick

import app.mobdroid.engine.chat.ChatListener
import app.mobdroid.engine.chat.ChatMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val PONG = """{"event":"pusher:pong","data":{}}"""

private sealed interface Event {
    data object Connected : Event

    data class Disconnected(val reason: String) : Event

    data class Message(val message: ChatMessage) : Event

    data class Deleted(val messageId: String) : Event

    data class Banned(val userId: String) : Event
}

private class Recorder : ChatListener {
    private val events = LinkedBlockingQueue<Event>()

    override fun onConnected() {
        events.add(Event.Connected)
    }

    override fun onDisconnected(reason: String) {
        events.add(Event.Disconnected(reason))
    }

    override fun onMessage(message: ChatMessage) {
        events.add(Event.Message(message))
    }

    override fun onMessageDeleted(messageId: String) {
        events.add(Event.Deleted(messageId))
    }

    override fun onUserMessagesDeleted(userId: String) {
        events.add(Event.Banned(userId))
    }

    fun next(): Event = events.poll(10, TimeUnit.SECONDS) ?: error("no listener call within 10 s")
}

private class PusherStub(
    private val confirm: Boolean = true,
    private val afterSubscribed: (WebSocket) -> Unit = {},
) : WebSocketListener() {
    val frames = CopyOnWriteArrayList<String>()
    val closed = CountDownLatch(1)

    val channels: List<String>
        get() = frames.mapNotNull { channelOf(it) }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        webSocket.send(KickFrames.ESTABLISHED)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        frames.add(text)
        val channel = channelOf(text) ?: return
        if (confirm) {
            webSocket.send("""{"event":"pusher_internal:subscription_succeeded","data":"{}","channel":"$channel"}""")
            if (channels.size == 3) {
                afterSubscribed(webSocket)
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        closed.countDown()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        closed.countDown()
    }

    private fun channelOf(frame: String): String? =
        Json.parseToJsonElement(frame).jsonObject["data"]?.jsonObject?.get("channel")?.jsonPrimitive?.content
}

private fun json(code: Int, body: String): MockResponse =
    MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()

private fun lookup(chatroomId: Long): MockResponse =
    json(200, """{"id":${chatroomId + 1},"slug":"foo","chatroom":{"id":$chatroomId,"channel_id":${chatroomId + 1}}}""")

private class Harness(
    private val stubs: List<PusherStub>,
    private val lookups: List<MockResponse> = listOf(lookup(4242)),
    private val sends: List<MockResponse> = emptyList(),
) : AutoCloseable {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    private val lookupCount = AtomicInteger()
    private val upgradeCount = AtomicInteger()
    private val sendCount = AtomicInteger()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    val recorder = Recorder()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.url.encodedPath
                return when {
                    path.startsWith("/api/v1/channels/") -> lookups.pick(lookupCount)
                    path.startsWith("/api/v2/messages/send/") -> sends.pick(sendCount)
                    path == "/pusher" ->
                        MockResponse.Builder().webSocketUpgrade(stubs.pick(upgradeCount)).build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    val paths: List<String>
        get() = requests.map { it.url.encodedPath }

    val lastRequest: RecordedRequest
        get() = requests.last()

    fun client(
        channel: String = "foo",
        login: KickLogin? = null,
        listener: ChatListener = recorder,
        subscribeTimeoutMs: Long = 20_000,
    ) = KickChatClient(
        channel,
        login,
        listener,
        http,
        server.url("/pusher").toString(),
        server.url("/").toString(),
        subscribeTimeoutMs,
    )

    override fun close() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun <T> List<T>.pick(counter: AtomicInteger): T = this[minOf(counter.getAndIncrement(), lastIndex)]
}

private fun KickChatClient.sendAndWait(text: String): String? {
    val result = CompletableFuture<String?>()
    send(text) { result.complete(it) }
    return result.get(10, TimeUnit.SECONDS)
}

private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        if (System.nanoTime() > deadline) {
            return false
        }
        Thread.sleep(10)
    }
    return true
}

private fun chatThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "kick-chat" }

class KickChatClientTest {
    @Test
    fun resolvesTheChatroomSubscribesAndDeliversChatEvents() {
        val stub = PusherStub {
            it.send(KickFrames.PLAIN)
            it.send(KickFrames.PING)
            it.send(KickFrames.DELETED)
            it.send(KickFrames.BANNED)
        }
        Harness(listOf(stub)).use { harness ->
            val client = harness.client()
            client.start()
            try {
                assertEquals(Event.Connected, harness.recorder.next())
                val message = assertIs<Event.Message>(harness.recorder.next()).message
                assertEquals("that last play was clean", message.text)
                assertEquals("PixelFox", message.user)
                assertEquals(Event.Deleted("0c4cdb14-56e5-49fe-94da-fd68ff0cbb2c"), harness.recorder.next())
                assertEquals(Event.Banned("4821"), harness.recorder.next())
                assertTrue(waitUntil { PONG in stub.frames })
            } finally {
                client.stop()
            }
            assertEquals(listOf("/api/v1/channels/foo", "/pusher"), harness.paths)
            assertEquals(
                """{"event":"pusher:subscribe","data":{"auth":"","channel":"chatrooms.4242.v2"}}""",
                stub.frames.first(),
            )
            assertEquals(listOf("chatrooms.4242.v2", "chatroom_4242", "chatrooms.4242"), stub.channels)
            assertTrue(stub.closed.await(10, TimeUnit.SECONDS))
            assertEquals(0, chatThreads())
        }
    }

    @Test
    fun stopWaitsForARunningCallbackAndSilencesTheListener() {
        val flood = PusherStub {
            thread(isDaemon = true) {
                while (it.send(KickFrames.PLAIN)) {
                    Thread.sleep(5)
                }
            }
        }
        Harness(listOf(flood)).use { harness ->
            val inCallback = CountDownLatch(1)
            val release = CountDownLatch(1)
            val stopReturned = AtomicBoolean()
            val callsAfterStop = AtomicInteger()
            val listener = object : ChatListener {
                override fun onConnected() = countLateCall()

                override fun onDisconnected(reason: String) = countLateCall()

                override fun onMessageDeleted(messageId: String) = countLateCall()

                override fun onUserMessagesDeleted(userId: String) = countLateCall()

                override fun onMessage(message: ChatMessage) {
                    countLateCall()
                    if (inCallback.count > 0) {
                        inCallback.countDown()
                        release.await(10, TimeUnit.SECONDS)
                    }
                }

                private fun countLateCall() {
                    if (stopReturned.get()) {
                        callsAfterStop.incrementAndGet()
                    }
                }
            }
            val client = harness.client(listener = listener)
            client.start()
            assertTrue(inCallback.await(10, TimeUnit.SECONDS))
            val stopDone = CountDownLatch(1)
            thread {
                client.stop()
                stopDone.countDown()
            }
            assertFalse(stopDone.await(300, TimeUnit.MILLISECONDS))
            release.countDown()
            assertTrue(stopDone.await(10, TimeUnit.SECONDS))
            stopReturned.set(true)
            Thread.sleep(300)
            assertEquals(0, callsAfterStop.get())
            assertEquals(0, chatThreads())
            assertTrue(flood.closed.await(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun reconnectsAfterTheServerClosesTheConnection() {
        val dropped = PusherStub { it.close(1001, "going away") }
        val healthy = PusherStub { it.send(KickFrames.PLAIN) }
        Harness(listOf(dropped, healthy)).use { harness ->
            val client = harness.client()
            client.start()
            try {
                assertEquals(Event.Connected, harness.recorder.next())
                assertEquals(
                    Event.Disconnected("Kick closed the chat connection (1001 going away)"),
                    harness.recorder.next(),
                )
                assertEquals(Event.Connected, harness.recorder.next())
                assertIs<Event.Message>(harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(listOf("/api/v1/channels/foo", "/pusher", "/pusher"), harness.paths)
            assertEquals(listOf("chatrooms.4242.v2", "chatroom_4242", "chatrooms.4242"), healthy.channels)
        }
    }

    @Test
    fun reportsARefusedLookupOnceAndKeepsRetrying() {
        val refused = MockResponse.Builder().code(403).body("<html><title>Just a moment...</title></html>").build()
        Harness(listOf(PusherStub()), lookups = listOf(refused, refused, lookup(4242))).use { harness ->
            val client = harness.client()
            client.start()
            try {
                val disconnected = assertIs<Event.Disconnected>(harness.recorder.next())
                assertEquals("Kick channel lookup failed (HTTP 403)", disconnected.reason)
                assertEquals(Event.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(3, harness.paths.count { it == "/api/v1/channels/foo" })
        }
    }

    @Test
    fun triesTheKebabSlugWhenTheNameIsNotFound() {
        val notFound = json(404, """{"message":"No query results for model [App\\Models\\Channel]."}""")
        Harness(listOf(PusherStub()), lookups = listOf(notFound, lookup(4242))).use { harness ->
            val client = harness.client(channel = " my_channel ")
            client.start()
            try {
                assertEquals(Event.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(
                listOf("/api/v1/channels/my_channel", "/api/v1/channels/my-channel", "/pusher"),
                harness.paths,
            )
        }
    }

    @Test
    fun sendsThroughTheApiAndReportsWhyKickRefused() {
        val sends = listOf(
            json(200, """{"status":{"error":false}}"""),
            json(403, """{"message":"User is not authenticated."}"""),
            MockResponse.Builder().code(502).body("<html>Bad gateway</html>").build(),
        )
        Harness(listOf(PusherStub()), sends = sends).use { harness ->
            val anonymous = harness.client()
            assertFalse(anonymous.canSend)
            assertEquals("Not logged in to Kick", anonymous.sendAndWait("hello"))
            val client = harness.client(login = KickLogin("test-token"))
            assertTrue(client.canSend)
            assertEquals("Kick chat is not connected", client.sendAndWait("too early"))
            client.start()
            try {
                assertEquals(Event.Connected, harness.recorder.next())
                assertNull(client.sendAndWait("say \"hi\""))
                val request = harness.lastRequest
                assertEquals("POST", request.method)
                assertEquals("/api/v2/messages/send/4242", request.url.encodedPath)
                assertEquals("Bearer test-token", request.headers["Authorization"])
                assertTrue(request.headers["Content-Type"].orEmpty().startsWith("application/json"))
                assertEquals("""{"type":"message","content":"say \"hi\""}""", request.body?.utf8())
                assertEquals("Kick: User is not authenticated.", client.sendAndWait("again"))
                assertEquals("Kick rejected the message (HTTP 502)", client.sendAndWait("and again"))
            } finally {
                client.stop()
            }
            assertEquals("Kick chat is not connected", client.sendAndWait("after stop"))
        }
    }

    @Test
    fun reconnectsWhenKickNeverConfirmsTheSubscription() {
        val silent = PusherStub(confirm = false)
        Harness(listOf(silent, PusherStub())).use { harness ->
            val client = harness.client(subscribeTimeoutMs = 300)
            client.start()
            try {
                assertEquals(
                    Event.Disconnected("Kick did not confirm the chat subscription"),
                    harness.recorder.next(),
                )
                assertEquals(Event.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertTrue(silent.closed.await(10, TimeUnit.SECONDS))
        }
    }
}
