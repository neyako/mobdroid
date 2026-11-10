package app.mobdroid.engine.chat.kick

import app.mobdroid.engine.chat.ChatClient
import app.mobdroid.engine.chat.ChatListener
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val PUSHER_URL =
    "wss://ws-us2.pusher.com/app/34bf7a0ff419a2a775b9?protocol=7&client=js&version=7.6.0&flash=false"
private const val API_BASE = "https://kick.com"
private const val MIN_BACKOFF_MS = 500L
private const val MAX_BACKOFF_MS = 10_000L
private const val PING_INTERVAL_MS = 10_000L
private const val CALL_TIMEOUT_MS = 15_000L
private const val SUBSCRIBE_TIMEOUT_MS = 20_000L
private const val STOP_JOIN_MS = 2_000L
private const val PONG_FRAME = """{"event":"pusher:pong","data":{}}"""
private val jsonMediaType = "application/json".toMediaType()

data class KickLogin(val accessToken: String) {
    override fun toString(): String = "KickLogin(accessToken=***)"
}

private class Outcome(val reason: String, val subscribed: Boolean)

class KickChatClient internal constructor(
    channel: String,
    private val login: KickLogin?,
    private val listener: ChatListener,
    http: OkHttpClient,
    private val pusherUrl: String,
    apiBase: String,
    private val subscribeTimeoutMs: Long,
) : ChatClient {
    constructor(channel: String, login: KickLogin?, listener: ChatListener, http: OkHttpClient) :
        this(channel, login, listener, http, PUSHER_URL, API_BASE, SUBSCRIBE_TIMEOUT_MS)

    private val slugs = channel.trim().let { listOf(it, it.replace('_', '-')).distinct() }
    private val base = apiBase.toHttpUrl()
    private val api = http.newBuilder().callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
    private val sockets = http.newBuilder().pingInterval(PING_INTERVAL_MS, TimeUnit.MILLISECONDS).build()
    private val lock = Any()

    @Volatile private var current: Run? = null

    @Volatile private var chatroomId = 0L

    override val canSend: Boolean
        get() = login != null

    override fun start() {
        synchronized(lock) {
            if (current == null) {
                current = Run().also { it.thread.start() }
            }
        }
    }

    override fun stop() {
        val run = synchronized(lock) { current.also { current = null } } ?: return
        run.cancel()
        if (!Thread.holdsLock(lock)) {
            run.thread.join(STOP_JOIN_MS)
        }
    }

    override fun send(text: String, onResult: (error: String?) -> Unit) {
        val token = login?.accessToken ?: return onResult("Not logged in to Kick")
        val id = chatroomId
        if (id == 0L || current == null) {
            return onResult("Kick chat is not connected")
        }
        val request = try {
            Request.Builder()
                .url(base.newBuilder().addPathSegments("api/v2/messages/send/$id").build())
                .header("Authorization", "Bearer $token")
                .post(sendBody(text))
                .build()
        } catch (_: IllegalArgumentException) {
            return onResult("Invalid Kick login")
        }
        api.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    onResult("Message not sent: ${e.message ?: e.javaClass.simpleName}")
                }

                override fun onResponse(call: Call, response: Response) {
                    onResult(response.use { sendError(it) })
                }
            },
        )
    }

    private fun sendBody(text: String): RequestBody =
        buildJsonObject {
            put("type", "message")
            put("content", text)
        }.toString().toRequestBody(jsonMediaType)

    private fun sendError(response: Response): String? {
        if (response.isSuccessful) {
            return null
        }
        val body = try {
            response.body.string()
        } catch (_: IOException) {
            ""
        }
        return parseKickErrorMessage(body)?.let { "Kick: $it" }
            ?: "Kick rejected the message (HTTP ${response.code})"
    }

    private fun fetch(url: HttpUrl, run: Run): Pair<Int, String> {
        val call = api.newCall(Request.Builder().url(url).build())
        run.call = call
        if (run.stopped) {
            call.cancel()
        }
        return call.execute().use { it.code to it.body.string() }
    }

    private fun lookup(run: Run): Long {
        for (slug in slugs) {
            val url = base.newBuilder().addPathSegments("api/v1/channels").addPathSegment(slug).build()
            val (code, body) = try {
                fetch(url, run)
            } catch (e: IOException) {
                throw IOException("Kick channel lookup failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
            if (code in 200..299) {
                return parseKickChatroomId(body)
                    ?: throw IOException("Kick channel lookup returned an unexpected response")
            }
            if (code != 404) {
                throw IOException("Kick channel lookup failed (HTTP $code)")
            }
        }
        throw IOException("Kick channel \"${slugs.first()}\" was not found")
    }

    private inner class Run : Runnable {
        val thread = Thread(this, "kick-chat").apply { isDaemon = true }

        @Volatile var stopped = false

        @Volatile var call: Call? = null

        @Volatile private var socket: WebSocket? = null

        fun cancel() {
            stopped = true
            call?.cancel()
            socket?.cancel()
            thread.interrupt()
        }

        override fun run() {
            var backoffMs = MIN_BACKOFF_MS
            var reported: String? = null
            try {
                while (!stopped) {
                    val outcome = attempt()
                    if (stopped) {
                        break
                    }
                    if (outcome.subscribed) {
                        backoffMs = MIN_BACKOFF_MS
                        reported = null
                    }
                    if (outcome.reason != reported) {
                        reported = outcome.reason
                        synchronized(lock) {
                            if (current === this) {
                                listener.onDisconnected(outcome.reason)
                            }
                        }
                    }
                    Thread.sleep(backoffMs)
                    backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
                }
            } catch (_: InterruptedException) {
            }
        }

        private fun attempt(): Outcome {
            val id = chatroomId.takeIf { it != 0L } ?: try {
                lookup(this).also { chatroomId = it }
            } catch (e: IOException) {
                return Outcome(e.message ?: "Kick channel lookup failed", false)
            }
            val connection = Connection(id)
            val socket = sockets.newWebSocket(Request.Builder().url(pusherUrl).build(), connection)
            this.socket = socket
            if (stopped) {
                socket.cancel()
            }
            val reason = try {
                connection.await()
            } finally {
                socket.cancel()
            }
            return Outcome(reason, connection.finish())
        }

        private inline fun deliver(connection: Connection, block: ChatListener.() -> Unit) {
            synchronized(lock) {
                if (current === this && !connection.finished) {
                    listener.block()
                }
            }
        }

        private inner class Connection(private val id: Long) : WebSocketListener() {
            private val ended = CompletableFuture<String>()
            private var subscribed = false

            var finished = false
                private set

            fun await(): String =
                try {
                    ended.get(subscribeTimeoutMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    if (synchronized(lock) { subscribed }) {
                        ended.get()
                    } else {
                        "Kick did not confirm the chat subscription"
                    }
                }

            fun finish(): Boolean =
                synchronized(lock) {
                    finished = true
                    subscribed
                }

            override fun onMessage(webSocket: WebSocket, text: String) {
                when (val event = parseKickFrame(text, System.currentTimeMillis())) {
                    is KickEvent.Message -> deliver(this) { onMessage(event.message) }
                    is KickEvent.MessageDeleted -> deliver(this) { onMessageDeleted(event.messageId) }
                    is KickEvent.UserBanned -> deliver(this) { onUserMessagesDeleted(event.userId) }
                    is KickEvent.Subscribed -> onSubscribed()
                    KickEvent.ConnectionEstablished -> subscribeAll(webSocket)
                    KickEvent.Ping -> webSocket.send(PONG_FRAME)
                    null -> Unit
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                ended.complete(closedReason(code, reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ended.complete(closedReason(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ended.complete("Kick chat connection failed: ${t.message ?: t.javaClass.simpleName}")
            }

            private fun subscribeAll(webSocket: WebSocket) {
                for (channel in listOf("chatrooms.$id.v2", "chatroom_$id", "chatrooms.$id")) {
                    webSocket.send("""{"event":"pusher:subscribe","data":{"auth":"","channel":"$channel"}}""")
                }
            }

            private fun onSubscribed() {
                synchronized(lock) {
                    if (current === this@Run && !finished && !subscribed) {
                        subscribed = true
                        listener.onConnected()
                    }
                }
            }

            private fun closedReason(code: Int, reason: String): String =
                "Kick closed the chat connection (${if (reason.isEmpty()) code else "$code $reason"})"
        }
    }
}
