package com.opirl.app.engine.alerts

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
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

private val ALL_SCOPES = setOf("user:read:chat", "moderator:read:followers", "channel:read:redemptions", "bits:read")

private sealed interface Call {
    data class Alert(val event: AlertEvent) : Call

    data object Connected : Call

    data class Disconnected(val reason: String) : Call

    data object Unauthorized : Call
}

private class Recorder : AlertListener {
    private val calls = LinkedBlockingQueue<Call>()

    override fun onAlert(event: AlertEvent) {
        calls.add(Call.Alert(event))
    }

    override fun onConnected() {
        calls.add(Call.Connected)
    }

    override fun onDisconnected(reason: String) {
        calls.add(Call.Disconnected(reason))
    }

    override fun onUnauthorized() {
        calls.add(Call.Unauthorized)
    }

    fun next(): Call = calls.poll(10, TimeUnit.SECONDS) ?: error("no listener call within 10 s")

    fun isEmpty() = calls.isEmpty()
}

private class Stub(private val onOpen: (WebSocket) -> Unit) : WebSocketListener() {
    val closed = CountDownLatch(1)

    @Volatile var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        onOpen.invoke(webSocket)
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
}

private class Harness(private val subscribeCodes: List<Int> = listOf(202)) : AutoCloseable {
    val server = MockWebServer()
    private val http = OkHttpClient()
    private val upgrades = AtomicInteger()
    private val subscribes = AtomicInteger()
    val bodies = CopyOnWriteArrayList<String>()
    val credentials = CopyOnWriteArrayList<Pair<String?, String?>>()
    val recorder = Recorder()
    var stubs: List<Stub> = emptyList()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path.startsWith("/ws") ->
                        MockResponse.Builder().webSocketUpgrade(stubs[minOf(upgrades.getAndIncrement(), stubs.lastIndex)]).build()
                    path == "/helix/eventsub/subscriptions" -> {
                        bodies.add(request.body?.utf8().orEmpty())
                        credentials.add(request.headers["Authorization"] to request.headers["Client-Id"])
                        val code = subscribeCodes[minOf(subscribes.getAndIncrement(), subscribeCodes.lastIndex)]
                        MockResponse.Builder().code(code).body("{}").build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    val types: List<String>
        get() = bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content }

    val wsUrl: String get() = server.url("/ws").toString()

    fun client(
        scopes: Set<String> = ALL_SCOPES,
        listener: AlertListener = recorder,
        keepaliveSlackMs: Long = 5_000,
    ) = TwitchEventSubClient(
        "1337",
        "token",
        "client",
        scopes,
        listener,
        http,
        EventSubConfig(
            url = wsUrl,
            helixUrl = server.url("/helix").toString(),
            keepaliveSlackMs = keepaliveSlackMs,
            reconnectDelayMs = { 20 },
        ),
    )

    override fun close() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

private fun waitUntil(condition: () -> Boolean): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (!condition()) {
        if (System.nanoTime() > deadline) return false
        Thread.sleep(10)
    }
    return true
}

private fun eventSubThreads() = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "twitch-eventsub" }

class TwitchEventSubClientTest {
    @Test
    fun subscribesAfterTheWelcomeAndDeliversNotifications() {
        val stub = Stub { it.send(EventSubFixtures.welcome("session-1")) }
        Harness().use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                stub.socket!!.send(EventSubFixtures.KEEPALIVE)
                stub.socket!!.send(EventSubFixtures.FOLLOW)
                stub.socket!!.send(EventSubFixtures.RAID)
                assertEquals(Call.Alert(AlertEvent.TwitchFollow("Cool_User")), harness.recorder.next())
                assertEquals(Call.Alert(AlertEvent.TwitchRaid("Cool_User", 9001)), harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(
                setOf(
                    "channel.follow",
                    "channel.chat.notification",
                    "channel.channel_points_custom_reward_redemption.add",
                    "channel.raid",
                    "channel.cheer",
                ),
                harness.types.toSet(),
            )
            assertEquals(5, harness.bodies.size)
            assertEquals(List(5) { "Bearer token" to "client" }, harness.credentials.toList())
            for (body in harness.bodies) {
                val transport = Json.parseToJsonElement(body).jsonObject.getValue("transport").jsonObject
                assertEquals("session-1", transport.getValue("session_id").jsonPrimitive.content)
            }
            assertTrue(stub.closed.await(10, TimeUnit.SECONDS))
            assertTrue(waitUntil { eventSubThreads() == 0 })
        }
    }

    @Test
    fun leavesOutSubscriptionsTheScopesDoNotCover() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness().use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client(scopes = setOf("user:read:chat", "user:write:chat"))
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(setOf("channel.chat.notification", "channel.raid"), harness.types.toSet())
        }
    }

    @Test
    fun deliversAnAlertOnlyOnceWhenTwitchSendsItAgain() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness().use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                stub.socket!!.send(EventSubFixtures.FOLLOW)
                stub.socket!!.send(EventSubFixtures.FOLLOW)
                stub.socket!!.send(EventSubFixtures.CHEER)
                assertEquals(Call.Alert(AlertEvent.TwitchFollow("Cool_User")), harness.recorder.next())
                assertEquals(Call.Alert(AlertEvent.TwitchCheer("Cool_User", 1000, "pogchamp")), harness.recorder.next())
                Thread.sleep(200)
                assertTrue(harness.recorder.isEmpty())
            } finally {
                client.stop()
            }
        }
    }

    @Test
    fun reportsAnExpiredLoginOnceAndStopsRetrying() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness(subscribeCodes = listOf(401)).use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            assertEquals(Call.Unauthorized, harness.recorder.next())
            assertTrue(stub.closed.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertTrue(harness.recorder.isEmpty())
            assertEquals(1, harness.server.requestCount - harness.bodies.size)
            client.stop()
            assertTrue(waitUntil { eventSubThreads() == 0 })
        }
    }

    @Test
    fun treatsARefusedSubscriptionAsOneToSkip() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness(subscribeCodes = listOf(403, 202)).use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(5, harness.bodies.size)
        }
    }

    @Test
    fun reconnectsAndSubscribesAgainAfterTheServerClosesTheConnection() {
        val dropped = Stub {
            it.send(EventSubFixtures.welcome("first"))
        }
        val healthy = Stub {
            it.send(EventSubFixtures.welcome("second"))
        }
        Harness().use { harness ->
            harness.stubs = listOf(dropped, healthy)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                dropped.socket!!.close(4000, "internal")
                assertEquals(Call.Disconnected("Closed by server (4000: internal)"), harness.recorder.next())
                assertEquals(Call.Connected, harness.recorder.next())
                healthy.socket!!.send(EventSubFixtures.FOLLOW)
                assertEquals(Call.Alert(AlertEvent.TwitchFollow("Cool_User")), harness.recorder.next())
            } finally {
                client.stop()
            }
            assertEquals(10, harness.bodies.size)
        }
    }

    @Test
    fun movesToTheReconnectUrlWithoutSubscribingAgain() {
        Harness().use { harness ->
            val next = Stub { it.send(EventSubFixtures.welcome("moved")) }
            val first = Stub { it.send(EventSubFixtures.welcome("first")) }
            harness.stubs = listOf(first, next)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                first.socket!!.send(EventSubFixtures.reconnect(harness.server.url("/ws2").toString()))
                assertTrue(first.closed.await(10, TimeUnit.SECONDS))
                assertTrue(waitUntil { next.socket != null })
                next.socket!!.send(EventSubFixtures.RAID)
                assertEquals(Call.Alert(AlertEvent.TwitchRaid("Cool_User", 9001)), harness.recorder.next())
                assertTrue(harness.recorder.isEmpty())
            } finally {
                client.stop()
            }
            assertEquals(5, harness.bodies.size)
        }
    }

    @Test
    fun reconnectsWhenTheKeepalivesStop() {
        val silent = Stub { it.send(EventSubFixtures.welcome("silent", keepaliveSeconds = 1)) }
        val healthy = Stub { it.send(EventSubFixtures.welcome("healthy", keepaliveSeconds = 30)) }
        Harness().use { harness ->
            harness.stubs = listOf(silent, healthy)
            val client = harness.client(keepaliveSlackMs = 0)
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                assertEquals(Call.Disconnected("Twitch stopped sending keepalives"), harness.recorder.next())
                assertEquals(Call.Connected, harness.recorder.next())
            } finally {
                client.stop()
            }
            assertTrue(silent.closed.await(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun stopsForGoodWhenAskedAndSaysNothingAfterwards() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness().use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            assertEquals(Call.Connected, harness.recorder.next())
            client.stop()
            assertTrue(stub.closed.await(10, TimeUnit.SECONDS))
            assertTrue(waitUntil { eventSubThreads() == 0 })
            Thread.sleep(300)
            assertTrue(harness.recorder.isEmpty())
            client.start()
            assertEquals(0, eventSubThreads())
            assertEquals(5, harness.bodies.size)
        }
    }

    @Test
    fun stopsWhenTwitchRevokesTheAuthorization() {
        val stub = Stub { it.send(EventSubFixtures.welcome()) }
        Harness().use { harness ->
            harness.stubs = listOf(stub)
            val client = harness.client()
            client.start()
            try {
                assertEquals(Call.Connected, harness.recorder.next())
                stub.socket!!.send(EventSubFixtures.revocation("authorization_revoked"))
                assertEquals(Call.Unauthorized, harness.recorder.next())
            } finally {
                client.stop()
            }
        }
    }
}
