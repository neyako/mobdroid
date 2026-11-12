package com.opirl.app.engine.remote

import com.opirl.app.engine.moblink.hashPassword
import com.opirl.app.engine.moblink.randomToken
import com.opirl.app.engine.net.WebServer
import com.opirl.app.engine.net.WebServerHandler
import com.opirl.app.engine.net.WebSocketConnection
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RemoteAssistantState(
    val listening: Boolean = false,
    val connected: Boolean = false,
    val wrongPassword: Boolean = false,
    val streamer: RemoteState = RemoteState(),
    val settings: RemoteSettings? = null,
    val status: RemoteStatus = RemoteStatus(),
)

class RemoteControlAssistant(
    port: Int,
    private val password: String,
    private val onError: (String) -> Unit,
    private val retryMs: Long = 5000,
    private val pingMs: Long = 30_000,
    private val pollMs: Long = 1000,
) : WebServerHandler {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-assistant").apply { isDaemon = true } }
    private val server = WebServer(port, this, "remote-assistant")
    private val _state = MutableStateFlow(RemoteAssistantState())
    val state: StateFlow<RemoteAssistantState> = _state.asStateFlow()
    private val _preview = MutableStateFlow<ByteArray?>(null)
    val preview: StateFlow<ByteArray?> = _preview.asStateFlow()
    private val pending = HashMap<Int, (RemoteToAssistant.Response) -> Unit>()
    private var streamer: WebSocketConnection? = null
    private var identified = false
    private var challenge = ""
    private var salt = ""
    private var nextId = 0
    private var active = false
    private var pongReceived = true
    private var gotPing = true
    private var ticks = 0L

    @Volatile private var closed = false

    val localPort: Int get() = server.localPort

    fun start() {
        post(::bind)
        runCatching { executor.scheduleWithFixedDelay({ if (!closed) tick() }, pollMs, pollMs, TimeUnit.MILLISECONDS) }
    }

    fun stop() {
        closed = true
        executor.shutdownNow()
        server.stop()
        _state.value = RemoteAssistantState()
        _preview.value = null
    }

    fun setActive(on: Boolean) = post {
        if (active == on) return@post
        active = on
        if (identified) {
            send(if (on) RemoteRequest.StartPreview else RemoteRequest.StopPreview)
            if (on) refresh()
        }
        if (!on) _preview.value = null
    }

    fun request(data: RemoteRequest) = post {
        if (!identified) {
            onError("Not connected to streamer")
            return@post
        }
        send(data) { if (it.result != "ok") onError("The streamer refused the request") }
    }

    private fun post(task: () -> Unit) {
        runCatching { executor.execute { if (!closed) task() } }
    }

    private fun deliver(task: () -> Unit) {
        runCatching { executor.submit { if (!closed) task() }.get(5, TimeUnit.SECONDS) }
    }

    private fun bind() {
        try {
            server.start()
            _state.update { it.copy(listening = true) }
        } catch (_: Exception) {
            runCatching { executor.schedule({ if (!closed) bind() }, retryMs, TimeUnit.MILLISECONDS) }
        }
    }

    private fun tick() {
        ticks++
        val connection = streamer ?: return
        val perPing = (pingMs / pollMs).coerceAtLeast(1)
        if (ticks % perPing == 0L) {
            if (!pongReceived || (ticks % (perPing * 2) == 0L && !gotPing.also { gotPing = false })) {
                connection.close()
                return
            }
            pongReceived = false
            connection.ping()
        }
        if (identified && active) {
            send(RemoteRequest.GetStatus) { response -> response.status?.let { status -> _state.update { it.copy(status = it.status.merged(status)) } } }
            if (ticks % 10 == 0L) refresh()
        }
    }

    private fun refresh() {
        send(RemoteRequest.GetSettings) { response -> response.settings?.let { settings -> _state.update { it.copy(settings = settings) } } }
    }

    private fun send(data: RemoteRequest, onResponse: (RemoteToAssistant.Response) -> Unit = {}) {
        val id = ++nextId
        if (pending.size >= 64) pending.clear()
        pending[id] = onResponse
        streamer?.send(encode(RemoteToStreamer.Request(id, data)))
    }

    override fun onOpen(connection: WebSocketConnection) = post {
        streamer?.close()
        streamer = connection
        identified = false
        pending.clear()
        challenge = randomToken()
        salt = randomToken()
        pongReceived = true
        gotPing = true
        connection.send(encode(RemoteToStreamer.Hello(challenge, salt)))
    }

    override fun onText(connection: WebSocketConnection, text: String) = deliver {
        if (connection !== streamer) return@deliver
        val message = decodeToAssistant(text) ?: return@deliver
        if (message is RemoteToAssistant.Identify) {
            identify(connection, message)
            return@deliver
        }
        if (!identified) return@deliver
        when (message) {
            is RemoteToAssistant.Response -> pending.remove(message.id)?.invoke(message)
            is RemoteToAssistant.StateEvent -> _state.update { it.copy(streamer = it.streamer.merged(message.state)) }
            is RemoteToAssistant.StatusEvent -> _state.update { it.copy(status = it.status.merged(message.status)) }
            is RemoteToAssistant.Preview -> if (active) {
                runCatching { Base64.getDecoder().decode(message.preview) }.getOrNull()?.let { _preview.value = it }
            }
            RemoteToAssistant.Ping -> {
                gotPing = true
                connection.send(encode(RemoteToStreamer.Pong))
            }
            else -> Unit
        }
    }

    private fun identify(connection: WebSocketConnection, message: RemoteToAssistant.Identify) {
        if (message.authentication != hashPassword(challenge, salt, password)) {
            _state.update { it.copy(wrongPassword = true) }
            connection.send(encode(RemoteToStreamer.Identified("wrongPassword")))
            connection.close()
            return
        }
        identified = true
        _state.update { it.copy(connected = true, wrongPassword = false, streamer = RemoteState(), status = RemoteStatus()) }
        connection.send(encode(RemoteToStreamer.Identified("ok")))
        refresh()
        send(RemoteRequest.GetStatus) { response -> response.status?.let { status -> _state.update { it.copy(status = status) } } }
        send(RemoteRequest.StartStatus(1, true))
        if (active) send(RemoteRequest.StartPreview)
    }

    override fun onPong(connection: WebSocketConnection) = post {
        if (connection === streamer) pongReceived = true
    }

    override fun onClosed(connection: WebSocketConnection) = post {
        if (connection !== streamer) return@post
        streamer = null
        identified = false
        pending.clear()
        _preview.value = null
        _state.update { it.copy(connected = false, settings = null, streamer = RemoteState(), status = RemoteStatus()) }
    }
}
