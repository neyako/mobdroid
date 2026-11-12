package com.opirl.app.engine.alerts

import com.opirl.app.engine.chat.twitch.backoffMs
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private val stableConnectionNanos = TimeUnit.SECONDS.toNanos(60)
private const val SEEN_LIMIT = 256
private const val SCHEDULER_STOP_MS = 1_000L
private val jsonMediaType = "application/json".toMediaType()

internal class EventSubConfig(
    val url: String = "wss://eventsub.wss.twitch.tv/ws",
    val helixUrl: String = "https://api.twitch.tv/helix",
    val welcomeTimeoutMs: Long = 15_000,
    val keepaliveSlackMs: Long = 5_000,
    val reconnectDelayMs: (failures: Int) -> Long = { backoffMs(it, ThreadLocalRandom.current().nextDouble()) },
)

class TwitchEventSubClient internal constructor(
    private val userId: String,
    private val accessToken: String,
    private val clientId: String,
    private val scopes: Set<String>,
    private val listener: AlertListener,
    private val http: OkHttpClient,
    private val config: EventSubConfig,
) {
    constructor(
        userId: String,
        accessToken: String,
        clientId: String,
        scopes: Set<String>,
        listener: AlertListener,
        http: OkHttpClient,
    ) : this(userId, accessToken, clientId, scopes, listener, http, EventSubConfig())

    private val lock = ReentrantLock()
    private val seen = HashSet<String>()
    private val seenOrder = ArrayDeque<String>()
    private var started = false
    private var stopped = false
    private var scheduler: ScheduledThreadPoolExecutor? = null
    private var connection: Connection? = null
    private var pending: Connection? = null
    private var failures = 0
    private var reportedDown = false

    @Volatile private var schedulerThread: Thread? = null

    fun start() {
        lock.withLock {
            if (started || stopped) return
            started = true
            scheduler = ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { task -> Thread(task, "twitch-eventsub").also { it.isDaemon = true; schedulerThread = it } },
            ).also {
                it.removeOnCancelPolicy = true
                it.executeExistingDelayedTasksAfterShutdownPolicy = false
            }
            connect(config.url, migrating = false)
        }
    }

    fun stop() {
        val executor = lock.withLock {
            if (stopped) return
            stopped = true
            shutdown()
        }
        if (executor != null) {
            executor.shutdownNow()
            if (Thread.currentThread() !== schedulerThread && !lock.isHeldByCurrentThread) {
                try {
                    executor.awaitTermination(SCHEDULER_STOP_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun shutdown(): ScheduledThreadPoolExecutor? {
        connection?.close()
        pending?.close()
        connection = null
        pending = null
        return scheduler.also { scheduler = null }
    }

    private fun connect(url: String, migrating: Boolean) {
        val conn = Connection()
        if (migrating) pending = conn else connection = conn
        conn.socket = http.newWebSocket(Request.Builder().url(url).build(), conn)
        conn.watchdog = schedule(config.welcomeTimeoutMs) { lost(conn, "Twitch did not welcome the connection") }
    }

    private fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        scheduler?.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    private fun reconnect() {
        lock.withLock {
            if (!stopped && connection == null) connect(config.url, migrating = false)
        }
    }

    private fun received(conn: Connection, text: String) {
        lock.withLock {
            if (conn !== connection && conn !== pending) return
            val message = try {
                parseEventSub(text)
            } catch (_: RuntimeException) {
                null
            } ?: return
            when (message) {
                is EventSubMessage.Welcome -> welcomed(conn, message)
                EventSubMessage.Keepalive -> watch(conn)
                is EventSubMessage.Reconnect -> if (conn === connection && pending == null) connect(message.url, migrating = true)
                is EventSubMessage.Notification -> {
                    watch(conn)
                    notify(message)
                }
                is EventSubMessage.Revocation -> if (message.status == "authorization_revoked" || message.status == "user_removed") {
                    unauthorized()
                }
            }
        }
    }

    private fun watch(conn: Connection) {
        conn.watchdog?.cancel(false)
        conn.watchdog = schedule(conn.keepaliveMs + config.keepaliveSlackMs) { lost(conn, "Twitch stopped sending keepalives") }
    }

    private fun welcomed(conn: Connection, welcome: EventSubMessage.Welcome) {
        conn.keepaliveMs = welcome.keepaliveSeconds * 1000L
        watch(conn)
        if (conn === pending) {
            connection?.close()
            connection = conn
            pending = null
            conn.ready = true
            conn.readyAtNanos = System.nanoTime()
            return
        }
        val wanted = subscriptions(userId).filter { it.scope == null || it.scope in scopes }
        conn.remaining = wanted.size
        if (wanted.isEmpty()) {
            ready(conn)
            return
        }
        for (subscription in wanted) {
            val call = http.newCall(
                Request.Builder()
                    .url("${config.helixUrl}/eventsub/subscriptions")
                    .header("Authorization", "Bearer $accessToken")
                    .header("Client-Id", clientId)
                    .post(subscriptionBody(subscription, welcome.sessionId).toRequestBody(jsonMediaType))
                    .build(),
            )
            conn.calls += call
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        subscribed(conn, subscription, null, describe(e))
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val code = response.use { it.code }
                        subscribed(conn, subscription, code, null)
                    }
                },
            )
        }
    }

    private fun subscribed(conn: Connection, subscription: Subscription, code: Int?, failure: String?) {
        lock.withLock {
            if (conn !== connection) return
            when {
                code != null && (code in 200..299 || code == 409 || code == 403) -> {
                    if (--conn.remaining == 0) ready(conn)
                }
                code == 401 -> unauthorized()
                else -> lost(conn, failure ?: "Twitch rejected the ${subscription.type} subscription (HTTP $code)")
            }
        }
    }

    private fun ready(conn: Connection) {
        conn.readyAtNanos = System.nanoTime()
        conn.ready = true
        reportedDown = false
        listener.onConnected()
    }

    private fun notify(message: EventSubMessage.Notification) {
        if (!seen.add(message.id)) return
        seenOrder.addLast(message.id)
        if (seenOrder.size > SEEN_LIMIT) seen.remove(seenOrder.removeFirst())
        message.event?.let { listener.onAlert(it) }
    }

    private fun unauthorized() {
        if (stopped) return
        stopped = true
        val executor = shutdown()
        executor?.shutdownNow()
        listener.onUnauthorized()
    }

    private fun lost(conn: Connection, reason: String) {
        lock.withLock {
            if (conn === pending) {
                pending = null
                conn.close()
                return
            }
            if (conn !== connection) return
            connection = null
            pending?.close()
            pending = null
            conn.close()
            if (conn.ready && System.nanoTime() - conn.readyAtNanos >= stableConnectionNanos) failures = 0
            schedule(config.reconnectDelayMs(failures++)) { reconnect() }
            if (!reportedDown) {
                reportedDown = true
                listener.onDisconnected(reason)
            }
        }
    }

    private fun describe(error: Throwable, response: Response? = null): String = when {
        response != null -> "HTTP ${response.code}"
        error is UnknownHostException -> "Unable to resolve host"
        error is SocketTimeoutException -> "Timed out"
        error is EOFException -> "Connection closed by server"
        error is SSLException -> "TLS error"
        else -> error.message ?: error.javaClass.simpleName
    }

    private fun describeClose(code: Int, reason: String): String =
        if (reason.isEmpty()) "Closed by server ($code)" else "Closed by server ($code: $reason)"

    private inner class Connection : WebSocketListener() {
        lateinit var socket: WebSocket
        val calls = ArrayList<Call>()
        var watchdog: ScheduledFuture<*>? = null
        var keepaliveMs = 0L
        var remaining = 0
        var ready = false
        var readyAtNanos = 0L

        fun close() {
            watchdog?.cancel(false)
            watchdog = null
            calls.forEach { it.cancel() }
            calls.clear()
            socket.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            received(this, text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            lost(this, describeClose(code, reason))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lost(this, describeClose(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lost(this, describe(t, response))
        }
    }
}
