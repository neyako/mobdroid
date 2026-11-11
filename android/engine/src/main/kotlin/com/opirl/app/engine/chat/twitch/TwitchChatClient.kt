package com.opirl.app.engine.chat.twitch

import com.opirl.app.engine.chat.ChatClient
import com.opirl.app.engine.chat.ChatListener
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

private const val PING_INTERVAL_MS = 30_000L
private val stableConnectionNanos = TimeUnit.SECONDS.toNanos(60)
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L
private const val BACKOFF_JITTER = 0.25
private const val BACKOFF_MAX_SHIFT = 10
private const val MAX_PENDING_SENDS = 8
private const val MAX_RESPONSE_BYTES = 8_192L
private const val SCHEDULER_STOP_MS = 1_000L
private val channelPattern = Regex("[a-z0-9_]{1,25}")
private val jsonMediaType = "application/json".toMediaType()

data class TwitchLogin(
    val userLogin: String,
    val accessToken: String,
    val clientId: String,
    val userId: String,
) {
    override fun toString() = "TwitchLogin(userLogin=$userLogin, userId=$userId)"
}

internal class TwitchChatConfig(
    val ircUrl: String = "wss://irc-ws.chat.twitch.tv",
    val helixUrl: String = "https://api.twitch.tv/helix",
    val joinTimeoutMs: Long = 20_000,
    val reconnectDelayMs: (failures: Int) -> Long = { backoffMs(it, ThreadLocalRandom.current().nextDouble()) },
)

internal fun backoffMs(failures: Int, jitter: Double): Long {
    val base = minOf(BACKOFF_BASE_MS shl minOf(failures, BACKOFF_MAX_SHIFT), BACKOFF_MAX_MS)
    return base + (base * BACKOFF_JITTER * jitter).toLong()
}

class TwitchChatClient internal constructor(
    channel: String,
    private val login: TwitchLogin?,
    private val listener: ChatListener,
    private val http: OkHttpClient,
    private val config: TwitchChatConfig,
) : ChatClient {
    constructor(channel: String, login: TwitchLogin?, listener: ChatListener, http: OkHttpClient) :
        this(channel, login, listener, http, TwitchChatConfig())

    private class PendingSend(val call: Call, val onResult: (String?) -> Unit)

    private val channel = channel.trim().removePrefix("#").lowercase()
    private val socketClient = http.newBuilder().pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS).build()
    private val lock = ReentrantLock()
    private val pendingSends = HashSet<PendingSend>()
    private var started = false
    private var stopped = false
    private var scheduler: ScheduledThreadPoolExecutor? = null
    private var connection: Connection? = null
    private var failures = 0
    private var reportedDown = false

    @Volatile private var schedulerThread: Thread? = null

    @Volatile private var roomId: String? = null

    override val canSend: Boolean
        get() = login != null

    override fun start() {
        lock.withLock {
            if (started || stopped) {
                return
            }
            started = true
            scheduler = ScheduledThreadPoolExecutor(
                1,
                ThreadFactory { task -> Thread(task, "twitch-chat").also { it.isDaemon = true; schedulerThread = it } },
            ).also {
                it.removeOnCancelPolicy = true
                it.executeExistingDelayedTasksAfterShutdownPolicy = false
            }
            if (channelPattern.matches(channel)) {
                connect()
            } else {
                schedule(0) { invalidChannel() }
            }
        }
    }

    private fun invalidChannel() {
        lock.withLock {
            if (!stopped) {
                listener.onDisconnected("Invalid Twitch channel name")
            }
        }
    }

    override fun stop() {
        val executor: ScheduledThreadPoolExecutor?
        val unfinished: List<PendingSend>
        lock.withLock {
            if (stopped) {
                return
            }
            stopped = true
            connection?.let {
                it.joinTimeout?.cancel(false)
                it.socket.cancel()
            }
            connection = null
            executor = scheduler
            scheduler = null
            unfinished = pendingSends.toList()
            pendingSends.clear()
            unfinished.forEach { it.call.cancel() }
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
        unfinished.forEach { it.onResult("Chat is stopped") }
    }

    override fun send(text: String, onResult: (String?) -> Unit) {
        val error = lock.withLock { startSend(text, onResult) }
        if (error != null) {
            onResult(error)
        }
    }

    private fun startSend(text: String, onResult: (String?) -> Unit): String? {
        val login = login ?: return "Not logged in to Twitch"
        val message = truncateMessage(text)
        if (message.isBlank()) {
            return "Message is empty"
        }
        if (stopped) {
            return "Chat is stopped"
        }
        if (pendingSends.size >= MAX_PENDING_SENDS) {
            return "Too many messages are being sent"
        }
        val ownChannel = channel == login.userLogin.lowercase()
        val broadcasterId = (if (ownChannel) login.userId else roomId) ?: return "Chat is not connected yet"
        val request = Request.Builder()
            .url("${config.helixUrl}/chat/messages")
            .header("Authorization", "Bearer ${login.accessToken}")
            .header("Client-Id", login.clientId)
            .post(twitchSendBody(broadcasterId, login.userId, message).toRequestBody(jsonMediaType))
            .build()
        val call = http.newCall(request)
        val send = PendingSend(call, onResult)
        pendingSends.add(send)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                finishSend(send, describe(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val error = try {
                    response.use { twitchSendError(it.code, it.peekBody(MAX_RESPONSE_BYTES).string()) }
                } catch (e: IOException) {
                    describe(e)
                }
                finishSend(send, error)
            }
        })
        return null
    }

    private fun finishSend(send: PendingSend, error: String?) {
        lock.withLock {
            if (pendingSends.remove(send)) {
                send.onResult(error)
            }
        }
    }

    private fun connect() {
        val request = Request.Builder().url(config.ircUrl).build()
        val conn = Connection()
        connection = conn
        conn.socket = socketClient.newWebSocket(request, conn)
        conn.joinTimeout = schedule(config.joinTimeoutMs) { joinTimedOut(conn) }
    }

    private fun schedule(delayMs: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        scheduler?.schedule(Runnable { task() }, delayMs, TimeUnit.MILLISECONDS)
    } catch (_: RejectedExecutionException) {
        null
    }

    private fun reconnect() {
        lock.withLock {
            if (!stopped) {
                connect()
            }
        }
    }

    private fun joinTimedOut(conn: Connection) {
        lock.withLock {
            if (connection === conn && !conn.joined) {
                lost(conn, "Could not join #$channel")
            }
        }
    }

    private fun opened(conn: Connection) {
        lock.withLock {
            if (connection !== conn) {
                return
            }
            conn.socket.send("CAP REQ :twitch.tv/tags twitch.tv/commands")
            conn.socket.send("PASS oauth:SCHMOOPIIE")
            conn.socket.send("NICK justinfan${ThreadLocalRandom.current().nextInt(10_000, 100_000)}")
            conn.socket.send("JOIN #$channel")
        }
    }

    private fun received(conn: Connection, text: String) {
        lock.withLock {
            var start = 0
            while (start < text.length) {
                if (connection !== conn) {
                    return
                }
                var end = text.indexOf('\n', start)
                if (end < 0) {
                    end = text.length
                }
                val lineEnd = if (end > start && text[end - 1] == '\r') end - 1 else end
                if (lineEnd > start) {
                    handle(conn, if (start == 0 && lineEnd == text.length) text else text.substring(start, lineEnd))
                }
                start = end + 1
            }
        }
    }

    private fun handle(conn: Connection, raw: String) {
        val event = try {
            parseIrcLine(raw)?.let { twitchEvent(it, System.currentTimeMillis()) }
        } catch (_: RuntimeException) {
            null
        }
        when (event) {
            is TwitchEvent.Message -> listener.onMessage(event.message)
            is TwitchEvent.MessageDeleted -> listener.onMessageDeleted(event.messageId)
            is TwitchEvent.UserMessagesDeleted -> listener.onUserMessagesDeleted(event.userId)
            is TwitchEvent.Ping -> conn.socket.send("PONG :${event.payload}")
            is TwitchEvent.Joined -> joined(conn, event.roomId)
            TwitchEvent.Reconnect -> lost(conn, "Twitch asked to reconnect")
            null -> Unit
        }
    }

    private fun joined(conn: Connection, joinedRoomId: String?) {
        if (joinedRoomId != null) {
            roomId = joinedRoomId
            listener.onChannelId(joinedRoomId)
        }
        if (conn.joined) {
            return
        }
        conn.joined = true
        conn.joinedAtNanos = System.nanoTime()
        conn.joinTimeout?.cancel(false)
        conn.joinTimeout = null
        reportedDown = false
        listener.onConnected()
    }

    private fun lost(conn: Connection, reason: String) {
        lock.withLock {
            if (connection !== conn) {
                return
            }
            connection = null
            conn.joinTimeout?.cancel(false)
            conn.socket.cancel()
            if (conn.joined && System.nanoTime() - conn.joinedAtNanos >= stableConnectionNanos) {
                failures = 0
            }
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
        var joined = false
        var joinedAtNanos = 0L
        var joinTimeout: ScheduledFuture<*>? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened(this)
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
