package app.mobdroid.engine.chat.youtube

import app.mobdroid.engine.chat.ChatListener
import app.mobdroid.engine.chat.ChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.QueueDispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class YouTubeChatClientTest {
    private val server = MockWebServer()
    private val events = LinkedBlockingQueue<String>()
    private var client: YouTubeChatClient? = null
    private val timings = YouTubeChatTimings(
        initialPollMs = 5,
        minPollMs = 1,
        maxPollMs = 10,
        retryInitialMs = 5,
        retryMaxMs = 20,
    )
    private val listener = object : ChatListener {
        override fun onConnected() {
            events += "connected"
        }

        override fun onDisconnected(reason: String) {
            events += "disconnected: $reason"
        }

        override fun onMessage(message: ChatMessage) {
            events += "message: ${message.id} ${message.text}"
        }

        override fun onMessageDeleted(messageId: String) {
            events += "deleted: $messageId"
        }

        override fun onUserMessagesDeleted(userId: String) {
            events += "user deleted: $userId"
        }
    }

    @BeforeTest
    fun setUp() {
        (server.dispatcher as QueueDispatcher).setFailFast(true)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        client?.stop()
        server.close()
    }

    private fun startClient(source: String): YouTubeChatClient =
        YouTubeChatClient(source, listener, OkHttpClient(), server.url("/"), timings).also {
            client = it
            it.start()
        }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse(code = code, body = body))
    }

    private fun take(count: Int): List<String> =
        List(count) { events.poll(5, TimeUnit.SECONDS) ?: fail("Only $it of $count events arrived") }

    private fun chatThreadIsGone() = Thread.getAllStackTraces().keys.none { it.name == "youtube-chat" && it.isAlive }

    @Test
    fun pollsWithTheContinuationOfThePreviousResponseAndSkipsRepeatedMessages() {
        val hello = textMessage("m1", user = "@alice", runs = textRuns(textRun("hello "), CUSTOM_EMOJI))
        val repeated = textMessage("m1", user = "@alice")
        val bye = textMessage("m2", user = "@bob", runs = textRuns(textRun("bye")))
        enqueue(channelLivePage("4t4AQ15yj1M"))
        enqueue(chatPage("tok-0"))
        enqueue(liveChatResponse(listOf(addAction(hello)), "tok-1"))
        enqueue(
            liveChatResponse(
                listOf(
                    addAction(repeated),
                    """{"markChatItemsByAuthorAsDeletedAction":{"externalChannelId":"UCbanned"}}""",
                    addAction(bye),
                ),
                "tok-2",
            ),
        )

        startClient("@testchannel")

        assertEquals(
            listOf("connected", "message: m1 hello yt", "user deleted: UCbanned", "message: m2 bye"),
            take(4),
        )
        val live = server.takeRequest()
        assertEquals("GET /@testchannel/live HTTP/1.1", live.requestLine)
        assertTrue(live.headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
        assertEquals("CONSENT=YES+1", live.headers["Cookie"])
        assertEquals("GET /live_chat?is_popout=1&v=4t4AQ15yj1M HTTP/1.1", server.takeRequest().requestLine)
        for (token in listOf("tok-0", "tok-1")) {
            val poll = server.takeRequest()
            assertEquals("POST /youtubei/v1/live_chat/get_live_chat?prettyPrint=false HTTP/1.1", poll.requestLine)
            val body = Json.parseToJsonElement(poll.body!!.utf8()).jsonObject
            assertEquals(token, body["continuation"]!!.jsonPrimitive.content)
            assertEquals("WEB", body["context"]!!.jsonObject["client"]!!.jsonObject["clientName"]!!.jsonPrimitive.content)
        }

        client!!.stop()
        assertTrue(chatThreadIsGone())
        val seen = events.size
        Thread.sleep(100)
        assertEquals(seen, events.size)
    }

    @Test
    fun keepsRetryingWhileTheChatIsNotLiveFailsOrEnds() {
        enqueue(DISABLED_CHAT_PAGE)
        enqueue(DISABLED_CHAT_PAGE)
        enqueue(chatPage("tok-a"))
        enqueue("", 500)
        enqueue(chatPage("tok-b"))
        enqueue(liveChatResponse(listOf(addAction(textMessage("m1"))), "tok-c"))
        enqueue(liveChatResponse(listOf(addAction(textMessage("m-last"))), null))
        enqueue(chatPage("tok-d"))
        enqueue(liveChatResponse(listOf(addAction(textMessage("m1")), addAction(textMessage("m2"))), "tok-e"))

        startClient("4t4AQ15yj1M")

        assertEquals(
            listOf(
                "disconnected: No live chat",
                "disconnected: HTTP 500",
                "connected",
                "message: m1 hello",
                "message: m-last hello",
                "disconnected: Chat ended",
                "connected",
                "message: m2 hello",
            ),
            take(8),
        )
        client!!.stop()
        assertTrue(chatThreadIsGone())
        val seen = events.size
        Thread.sleep(100)
        assertEquals(seen, events.size)
    }

    @Test
    fun stopCancelsARequestInFlight() {
        server.enqueue(MockResponse.Builder().body(chatPage("tok-0")).headersDelay(5, TimeUnit.SECONDS).build())
        startClient("4t4AQ15yj1M")
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

        val started = System.nanoTime()
        client!!.stop()

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_500)
        assertTrue(chatThreadIsGone())
        assertTrue(events.isEmpty())
    }

    @Test
    fun sendingIsReportedAsUnsupported() {
        val youTube = YouTubeChatClient("@testchannel", listener, OkHttpClient())
        var result: String? = null
        youTube.send("hello") { result = it }
        assertFalse(youTube.canSend)
        assertNotNull(result)
    }
}
