package com.opirl.app.engine.remote

import com.opirl.app.engine.moblink.hashPassword
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

interface RemoteControlTarget {
    fun status(): RemoteStatus

    fun settings(): RemoteSettings

    fun state(): RemoteState

    fun handle(request: RemoteRequest): Boolean
}

internal fun answer(id: Int, request: RemoteRequest, target: RemoteControlTarget): RemoteToAssistant.Response = when (request) {
    RemoteRequest.GetStatus -> RemoteToAssistant.Response(id, "ok", status = target.status())
    RemoteRequest.GetSettings -> RemoteToAssistant.Response(id, "ok", settings = target.settings())
    is RemoteRequest.Unknown -> RemoteToAssistant.Response(id, "unknownRequest")
    else -> RemoteToAssistant.Response(id, if (target.handle(request)) "ok" else "error")
}

enum class RemoteStreamerState { OFF, CONNECTING, CONNECTED, WRONG_PASSWORD }

class RemoteControlStreamer(
    http: OkHttpClient,
    private val url: String,
    private val password: String,
    private val streamerId: String,
    private val target: RemoteControlTarget,
    private val onPreviewRequested: (Boolean) -> Unit,
    private val retryMs: Long = 5000,
    private val keepAliveMs: Long = 30_000,
) : WebSocketListener() {
    private val sockets = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-streamer").apply { isDaemon = true } }
    private val _state = MutableStateFlow(RemoteStreamerState.CONNECTING)
    val state: StateFlow<RemoteStreamerState> = _state.asStateFlow()

    @Volatile private var closed = false

    @Volatile private var socket: WebSocket? = null

    @Volatile private var identified = false
    private var gotPong = true
    private var statusRequested: RemoteRequest.StartStatus? = null
    private var previewRequested = false
    private var seconds = 0L

    fun start() {
        post(::connect)
        runCatching { executor.scheduleWithFixedDelay({ if (!closed) tick() }, 1000, 1000, TimeUnit.MILLISECONDS) }
    }

    fun stop() {
        closed = true
        executor.shutdownNow()
        socket?.close(1000, null)
        _state.value = RemoteStreamerState.OFF
    }

    fun stateChanged(changes: RemoteState) {
        if (identified) socket?.send(encode(RemoteToAssistant.StateEvent(changes)))
    }

    fun sendPreview(jpeg: ByteArray) {
        val current = socket
        if (identified && current != null && current.queueSize() < 512 * 1024) {
            current.send(encode(RemoteToAssistant.Preview(Base64.getEncoder().encodeToString(jpeg))))
        }
    }

    fun log(entry: String) {
        if (identified) socket?.send(encode(RemoteToAssistant.LogEvent(entry)))
    }

    private fun post(task: () -> Unit) {
        runCatching { executor.execute { if (!closed) task() } }
    }

    private fun connect() {
        val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return
        gotPong = true
        val created = sockets.newWebSocket(request, this)
        socket = created
        if (closed) created.cancel()
    }

    private fun tick() {
        seconds++
        val current = socket ?: return
        if (!identified) return
        val wanted = statusRequested
        if (wanted != null && seconds % wanted.interval.coerceAtLeast(1) == 0L) {
            val status = target.status()
            current.send(
                encode(RemoteToAssistant.StatusEvent(if (wanted.topRight) RemoteStatus(topRight = status.topRight) else status)),
            )
        }
        if (seconds % (keepAliveMs / 1000).coerceAtLeast(1) == 0L) {
            if (gotPong) {
                gotPong = false
                current.send(encode(RemoteToAssistant.Ping))
            } else {
                lost(current)
            }
        }
    }

    private fun lost(webSocket: WebSocket, state: RemoteStreamerState = RemoteStreamerState.CONNECTING) {
        if (webSocket !== socket) return
        socket = null
        webSocket.cancel()
        identified = false
        statusRequested = null
        if (previewRequested) {
            previewRequested = false
            onPreviewRequested(false)
        }
        _state.value = state
        runCatching { executor.schedule({ if (!closed) connect() }, retryMs, TimeUnit.MILLISECONDS) }
    }

    override fun onMessage(webSocket: WebSocket, text: String) = post {
        if (webSocket !== socket) return@post
        when (val message = decodeToStreamer(text)) {
            is RemoteToStreamer.Hello -> webSocket.send(
                encode(RemoteToAssistant.Identify(streamerId, hashPassword(message.challenge, message.salt, password))),
            )
            is RemoteToStreamer.Identified -> if (message.result == "ok") {
                identified = true
                _state.value = RemoteStreamerState.CONNECTED
                webSocket.send(encode(RemoteToAssistant.StateEvent(target.state())))
            } else {
                lost(webSocket, if (message.result == "wrongPassword") RemoteStreamerState.WRONG_PASSWORD else RemoteStreamerState.CONNECTING)
            }
            is RemoteToStreamer.Request -> if (identified) request(webSocket, message)
            RemoteToStreamer.Pong -> gotPong = true
            null -> Unit
        }
    }

    private fun request(webSocket: WebSocket, message: RemoteToStreamer.Request) {
        when (val data = message.data) {
            is RemoteRequest.StartStatus -> statusRequested = data
            RemoteRequest.StopStatus -> statusRequested = null
            RemoteRequest.StartPreview, RemoteRequest.StopPreview -> {
                val wanted = data == RemoteRequest.StartPreview
                if (wanted != previewRequested) {
                    previewRequested = wanted
                    onPreviewRequested(wanted)
                }
            }
            else -> {
                webSocket.send(encode(answer(message.id, data, target)))
                return
            }
        }
        webSocket.send(encode(RemoteToAssistant.Response(message.id, "ok")))
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        post { lost(webSocket) }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post { lost(webSocket) }
}
