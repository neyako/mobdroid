package com.opirl.app.engine.chat.twitch

import com.opirl.app.engine.chat.ChatListener
import com.opirl.app.engine.chat.ChatMessage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val JOIN_REPLY =
    ":justinfan48214!justinfan48214@justinfan48214.tmi.twitch.tv JOIN #somechan\r\n" +
        "@emote-only=0;followers-only=10;r9k=0;room-id=12345678;slow=0;subs-only=0 :tmi.twitch.tv ROOMSTATE #somechan\r\n" +
        ":justinfan48214.tmi.twitch.tv 353 justinfan48214 = #somechan :justinfan48214\r\n" +
        ":justinfan48214.tmi.twitch.tv 366 justinfan48214 #somechan :End of /NAMES list\r\n"

private const val SENT = """{"data":[{"message_id":"abc-123","is_sent":true}]}"""

private class Events(private val messageDelayMs: Long = 0) : ChatListener {
    val queue = LinkedBlockingQueue<String>()
    val messages = AtomicInteger()
    val firstMessages = CountDownLatch(20)

    override fun onConnected() {
        queue.add("connected")
    }

    override fun onDisconnected(reason: String) {
        queue.add("disconnected: $reason")
    }

    override fun onMessage(message: ChatMessage) {
        if (messageDelayMs > 0) {
            Thread.sleep(messageDelayMs)
        }
        messages.incrementAndGet()
        firstMessages.countDown()
        queue.add("message ${message.user}: ${message.text}")
    }

    override fun onMessageDeleted(messageId: String) {
        queue.add("deleted $messageId")
    }

    override fun onUserMessagesDeleted(userId: String) {
        queue.add("user deleted $userId")
    }

    fun next(): String? = queue.poll(10, TimeUnit.SECONDS)
}

private class FakeIrc : AutoCloseable {
    val server = MockWebServer()
    val sockets = LinkedBlockingQueue<WebSocket>()
    val received = LinkedBlockingQueue<String>()
    val ended = LinkedBlockingQueue<String>()

    init {
        server.start()
    }

    val url: String get() = server.url("/").toString()

    fun accept(joinReply: String? = JOIN_REPLY, afterJoin: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    sockets.add(webSocket)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    received.add(text)
                    if (joinReply != null && text.startsWith("JOIN ")) {
                        webSocket.send(joinReply)
                        afterJoin(webSocket)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    ended.add("closed $code")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    ended.add("failed")
                }
            }).build(),
        )
    }

    override fun close() {
        server.close()
    }
}

class TwitchChatClientTest {
    private val http = OkHttpClient()
    private val login = TwitchLogin("somechan", "test-token", "test-client", "12345678")

    @AfterTest
    fun releaseHttp() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun config(irc: FakeIrc? = null, helix: MockWebServer? = null, joinTimeoutMs: Long = 20_000) =
        TwitchChatConfig(
            ircUrl = irc?.url ?: "http://localhost:1/",
            helixUrl = helix?.url("/helix")?.toString() ?: "http://localhost:1/helix",
            joinTimeoutMs = joinTimeoutMs,
            reconnectDelayMs = { 20 },
        )

    private fun chatThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name == "twitch-chat" }

    private fun sendAndWait(client: TwitchChatClient, text: String): String? {
        val result = CompletableFuture<String?>()
        client.send(text) { result.complete(it) }
        return result.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun joinsAnonymouslyDeliversMessagesAndAnswersPing() {
        FakeIrc().use { irc ->
            irc.accept()
            val events = Events()
            val client = TwitchChatClient("SomeChan", null, events, http, config(irc))
            client.start()
            try {
                assertEquals("CAP REQ :twitch.tv/tags twitch.tv/commands", irc.received.poll(10, TimeUnit.SECONDS))
                assertEquals("PASS oauth:SCHMOOPIIE", irc.received.poll(10, TimeUnit.SECONDS))
                assertTrue(Regex("NICK justinfan\\d+").matches(irc.received.poll(10, TimeUnit.SECONDS).orEmpty()))
                assertEquals("JOIN #somechan", irc.received.poll(10, TimeUnit.SECONDS))
                assertEquals("connected", events.next())

                val socket = irc.sockets.take()
                socket.send(capturedPrivmsg("that was close") + "\r\n")
                assertEquals("message Foo_Bar: that was close", events.next())
                socket.send("PING :tmi.twitch.tv\r\n")
                assertEquals("PONG :tmi.twitch.tv", irc.received.poll(10, TimeUnit.SECONDS))
                socket.send(
                    "@login=foo_bar;room-id=;target-msg-id=m-1;tmi-sent-ts=1794281930123 " +
                        ":tmi.twitch.tv CLEARMSG #somechan :x\r\n",
                )
                assertEquals("deleted m-1", events.next())
                socket.send(
                    "@room-id=12345678;target-user-id=24681357;tmi-sent-ts=1794282254831 " +
                        ":tmi.twitch.tv CLEARCHAT #somechan :u\r\n",
                )
                assertEquals("user deleted 24681357", events.next())
                assertTrue(events.queue.isEmpty())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun reconnectsAfterTheServerClosesAndReportsTheLossOnce() {
        FakeIrc().use { irc ->
            irc.accept()
            irc.accept()
            val events = Events()
            val client = TwitchChatClient("somechan", null, events, http, config(irc))
            client.start()
            try {
                assertEquals("connected", events.next())
                irc.sockets.take().close(1001, "going away")
                assertEquals("disconnected: Closed by server (1001: going away)", events.next())
                assertEquals("connected", events.next())
                val second = irc.sockets.take()
                second.send(capturedPrivmsg("back again") + "\r\n")
                assertEquals("message Foo_Bar: back again", events.next())
                assertTrue(events.queue.isEmpty())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun reconnectsWhenTwitchAsksTo() {
        FakeIrc().use { irc ->
            irc.accept()
            irc.accept()
            val events = Events()
            val client = TwitchChatClient("somechan", null, events, http, config(irc))
            client.start()
            try {
                assertEquals("connected", events.next())
                irc.sockets.take().send(":tmi.twitch.tv RECONNECT\r\n")
                assertEquals("disconnected: Twitch asked to reconnect", events.next())
                assertEquals("connected", events.next())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun aJoinTwitchNeverConfirmsIsReportedOnceAndRetried() {
        FakeIrc().use { irc ->
            irc.accept(joinReply = null)
            irc.accept(joinReply = null)
            irc.accept()
            val events = Events()
            val client = TwitchChatClient("somechan", null, events, http, config(irc, joinTimeoutMs = 500))
            client.start()
            try {
                assertEquals("disconnected: Could not join #somechan", events.next())
                assertEquals("connected", events.next())
                assertTrue(events.queue.isEmpty())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun listenerIsSilentOnceStopReturnsAndEveryThreadIsGone() {
        FakeIrc().use { irc ->
            irc.accept { socket -> repeat(3000) { socket.send(capturedPrivmsg("message $it")) } }
            val events = Events(messageDelayMs = 1)
            val client = TwitchChatClient("somechan", null, events, http, config(irc))
            client.start()
            assertTrue(events.firstMessages.await(10, TimeUnit.SECONDS))
            client.stop()
            val atStop = events.messages.get()
            assertNotNull(irc.ended.poll(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(atStop, events.messages.get())
            assertTrue(atStop < 3000)
            assertEquals(emptyList(), chatThreads())
        }
    }

    @Test
    fun invalidChannelNamesAreReportedWithoutConnecting() {
        val events = Events()
        val client = TwitchChatClient("bad name\r\nJOIN #other", null, events, http, config())
        client.start()
        try {
            assertEquals("disconnected: Invalid Twitch channel name", events.next())
        } finally {
            client.stop()
        }
        assertEquals(emptyList(), chatThreads())
    }

    @Test
    fun sendPostsToHelixAsTheLoggedInUserAndReportsTheOutcome() {
        MockWebServer().use { helix ->
            helix.enqueue(MockResponse.Builder().body(SENT).build())
            helix.enqueue(MockResponse.Builder().code(401).body("""{"error":"Unauthorized","status":401}""").build())
            val duplicate = """{"data":[{"message_id":"","is_sent":false,""" +
                """"drop_reason":{"code":"msg_duplicate","message":"Your message is a duplicate"}}]}"""
            helix.enqueue(MockResponse.Builder().body(duplicate).build())
            helix.start()
            val client = TwitchChatClient("SomeChan", login, Events(), http, config(helix = helix))
            assertTrue(client.canSend)

            assertNull(sendAndWait(client, "hello \"chat\" 😀"))
            val request = assertNotNull(helix.takeRequest(10, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("/helix/chat/messages", request.target)
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertEquals("test-client", request.headers["Client-Id"])
            assertEquals("application/json", request.headers["Content-Type"]?.substringBefore(';'))
            val body = Json.parseToJsonElement(assertNotNull(request.body).utf8()).jsonObject
            assertEquals("12345678", body["broadcaster_id"]?.jsonPrimitive?.content)
            assertEquals("12345678", body["sender_id"]?.jsonPrimitive?.content)
            assertEquals("hello \"chat\" 😀", body["message"]?.jsonPrimitive?.content)

            assertEquals("Twitch login expired", sendAndWait(client, "again"))
            assertEquals("Your message is a duplicate", sendAndWait(client, "again"))
        }
    }

    @Test
    fun sendInAnotherChannelUsesTheRoomIdTwitchReportedOnJoin() {
        FakeIrc().use { irc ->
            MockWebServer().use { helix ->
                irc.accept()
                helix.enqueue(MockResponse.Builder().body(SENT).build())
                helix.start()
                val events = Events()
                val viewer = TwitchLogin("viewer", "test-token", "test-client", "999")
                val client = TwitchChatClient("somechan", viewer, events, http, config(irc, helix))
                assertEquals("Chat is not connected yet", sendAndWait(client, "too early"))
                client.start()
                try {
                    assertEquals("connected", events.next())
                    assertNull(sendAndWait(client, "hi"))
                    val request = assertNotNull(helix.takeRequest(10, TimeUnit.SECONDS))
                    val body = Json.parseToJsonElement(assertNotNull(request.body).utf8()).jsonObject
                    assertEquals("12345678", body["broadcaster_id"]?.jsonPrimitive?.content)
                    assertEquals("999", body["sender_id"]?.jsonPrimitive?.content)
                } finally {
                    client.stop()
                }
            }
        }
    }

    @Test
    fun withoutALoginTheClientCannotSend() {
        val client = TwitchChatClient("somechan", null, Events(), http, config())
        assertFalse(client.canSend)
        assertEquals("Not logged in to Twitch", sendAndWait(client, "hi"))
    }

    @Test
    fun stopCompletesSendsStillInFlightExactlyOnce() {
        MockWebServer().use { helix ->
            helix.enqueue(MockResponse.Builder().body(SENT).headersDelay(1, TimeUnit.SECONDS).build())
            helix.start()
            val client = TwitchChatClient("somechan", login, Events(), http, config(helix = helix))
            val results = LinkedBlockingQueue<String?>()
            client.send("slow") { results.add(it ?: "sent") }
            assertNotNull(helix.takeRequest(10, TimeUnit.SECONDS))
            client.stop()
            assertEquals("Chat is stopped", results.poll(0, TimeUnit.SECONDS))
            assertNull(results.poll(300, TimeUnit.MILLISECONDS))
            assertEquals("Chat is stopped", sendAndWait(client, "after stop"))
        }
    }
}
