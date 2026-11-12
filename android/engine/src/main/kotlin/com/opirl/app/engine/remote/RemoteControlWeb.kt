package com.opirl.app.engine.remote

import com.opirl.app.engine.net.HttpRequest
import com.opirl.app.engine.net.HttpResponse
import com.opirl.app.engine.net.WebServer
import com.opirl.app.engine.net.WebServerHandler
import com.opirl.app.engine.net.WebSocketConnection
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val CONTENT_TYPES = mapOf(
    "html" to "text/html; charset=utf-8",
    "css" to "text/css; charset=utf-8",
    "mjs" to "text/javascript; charset=utf-8",
    "js" to "text/javascript; charset=utf-8",
    "json" to "application/json",
    "ico" to "image/x-icon",
    "png" to "image/png",
)

fun webContentType(path: String): String = CONTENT_TYPES[path.substringAfterLast('.', "")] ?: "application/octet-stream"

class RemoteControlWeb(
    private val port: Int,
    private val target: RemoteControlTarget,
    private val file: (String) -> ByteArray?,
    private val onPreviewRequested: (Boolean) -> Unit,
    private val retryMs: Long = 5000,
) : WebServerHandler {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-web").apply { isDaemon = true } }
    private val pages = WebServer(port, this, "remote-web")
    private val sockets = WebServer(if (port == 0) 0 else port + 1, this, "remote-web-ws")
    private val connections = ConcurrentHashMap.newKeySet<WebSocketConnection>()
    private val previews = HashSet<WebSocketConnection>()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()
    private val _clients = MutableStateFlow(0)
    val clients: StateFlow<Int> = _clients.asStateFlow()

    @Volatile private var closed = false

    val localPort: Int get() = pages.localPort

    fun start() = post(::bind)

    fun stop() {
        closed = true
        executor.shutdownNow()
        pages.stop()
        sockets.stop()
        _listening.value = false
        _clients.value = 0
    }

    fun stateChanged(changes: RemoteState) {
        if (connections.isEmpty()) return
        val text = encode(RemoteToAssistant.StateEvent(changes))
        connections.forEach { it.send(text) }
    }

    fun sendPreview(jpeg: ByteArray) = post {
        if (previews.isEmpty()) return@post
        val text = encode(RemoteToAssistant.Preview(Base64.getEncoder().encodeToString(jpeg)))
        previews.forEach { it.send(text) }
    }

    private fun post(task: () -> Unit) {
        runCatching { executor.execute { if (!closed) task() } }
    }

    private fun bind() {
        try {
            pages.start()
            sockets.start()
            _listening.value = true
        } catch (_: Exception) {
            pages.stop()
            sockets.stop()
            runCatching { executor.schedule({ if (!closed) bind() }, retryMs, TimeUnit.MILLISECONDS) }
        }
    }

    override fun onHttp(request: HttpRequest): HttpResponse? {
        if (request.method != "GET") return null
        val path = if (request.path == "/") "/index.html" else request.path
        if (path == "/js/config.mjs") {
            return HttpResponse(200, webContentType(path), "export const websocketPort = ${sockets.localPort};".toByteArray())
        }
        if (".." in path) return null
        val body = file(path.removePrefix("/")) ?: return null
        return HttpResponse(200, webContentType(path), body)
    }

    override fun onOpen(connection: WebSocketConnection) {
        connections += connection
        _clients.value = connections.size
        connection.send(encode(RemoteToAssistant.StateEvent(target.state())))
    }

    override fun onText(connection: WebSocketConnection, text: String) {
        val message = decodeToStreamer(text) as? RemoteToStreamer.Request ?: return
        when (val data = message.data) {
            RemoteRequest.StartPreview, RemoteRequest.StopPreview -> {
                post { setPreview(connection, data == RemoteRequest.StartPreview) }
                connection.send(encode(RemoteToAssistant.Response(message.id, "ok")))
            }
            is RemoteRequest.StartStatus, RemoteRequest.StopStatus -> connection.send(encode(RemoteToAssistant.Response(message.id, "ok")))
            else -> connection.send(encode(answer(message.id, data, target)))
        }
    }

    override fun onClosed(connection: WebSocketConnection) {
        connections -= connection
        _clients.value = connections.size
        post { setPreview(connection, false) }
    }

    private fun setPreview(connection: WebSocketConnection, on: Boolean) {
        val before = previews.isNotEmpty()
        if (on && connection in connections) previews += connection else previews -= connection
        if (before != previews.isNotEmpty()) onPreviewRequested(previews.isNotEmpty())
    }
}
