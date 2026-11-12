package com.opirl.app.engine.remote

import com.opirl.app.engine.json.obj
import com.opirl.app.engine.json.string
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val MAXIMUM_CONNECTIONS = 5

class RemoteControlBridge(
    http: OkHttpClient,
    private val baseUrl: String,
    private val bridgeId: String,
    private val assistantUrl: String,
    private val retryMs: Long = 5000,
) {
    private val sockets = http.newBuilder().pingInterval(20, TimeUnit.SECONDS).build()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "remote-bridge").apply { isDaemon = true } }
    private val connections = ArrayDeque<Connection>()
    private var control: WebSocket? = null

    @Volatile private var closed = false

    fun start() = post(::connect)

    fun stop() {
        closed = true
        executor.shutdownNow()
        synchronized(connections) {
            control?.cancel()
            connections.forEach { it.close() }
            connections.clear()
        }
    }

    private fun post(task: () -> Unit) {
        runCatching { executor.execute { if (!closed) task() } }
    }

    private fun open(url: String, listener: WebSocketListener): WebSocket? =
        runCatching { sockets.newWebSocket(Request.Builder().url(url).build(), listener) }.getOrNull()

    private fun connect() {
        val created = open(
            "$baseUrl/bridge/control/$bridgeId",
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = post { if (webSocket === control) message(text) }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    post { lost(webSocket) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post { lost(webSocket) }
            },
        )
        synchronized(connections) {
            control = created
            if (closed) created?.cancel()
        }
    }

    private fun lost(webSocket: WebSocket) {
        if (webSocket !== control) return
        synchronized(connections) {
            control = null
            connections.forEach { it.close() }
            connections.clear()
        }
        runCatching { executor.schedule({ if (!closed) connect() }, retryMs, TimeUnit.MILLISECONDS) }
    }

    private fun message(text: String) {
        val message = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        if (message.string("type") != "connect") return
        val connectionId = message.obj("data")?.string("connectionId") ?: return
        val connection = Connection(connectionId)
        synchronized(connections) {
            if (closed) return
            connections.addLast(connection)
            if (connections.size > MAXIMUM_CONNECTIONS) connections.removeFirst().close()
        }
        connection.start()
    }

    private inner class Connection(private val connectionId: String) {
        @Volatile private var relay: WebSocket? = null

        @Volatile private var assistant: WebSocket? = null

        @Volatile private var done = false

        fun start() {
            relay = open(
                "$baseUrl/bridge/data/$bridgeId/$connectionId",
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        assistant = open(assistantUrl, pipe { relay })
                        if (done) close()
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        assistant?.send(text)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = close()

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = close()
                },
            )
            if (done) close()
        }

        private fun pipe(to: () -> WebSocket?) = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                to()?.send(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = close()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = close()
        }

        fun close() {
            done = true
            relay?.cancel()
            assistant?.cancel()
        }
    }
}
