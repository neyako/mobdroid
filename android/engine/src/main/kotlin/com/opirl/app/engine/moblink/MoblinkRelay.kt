package com.opirl.app.engine.moblink

import com.opirl.app.engine.net.NetworkLink
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketAddress
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

enum class MoblinkRelayState { CONNECTING, CONNECTED, WRONG_PASSWORD, UNKNOWN_ERROR }

class MoblinkRelay(
    http: OkHttpClient,
    val streamerUrl: String,
    private val password: String,
    private val id: String,
    private val name: String,
    val destination: NetworkLink,
    private val createStreamerSocket: () -> DatagramSocket,
    private val status: () -> Pair<Int?, MoblinkThermalState?>,
    private val retryMs: Long = 5000,
) : WebSocketListener() {
    private val sockets = http.newBuilder().pingInterval(10, TimeUnit.SECONDS).build()
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "moblink-relay").apply { isDaemon = true } }
    private val _state = MutableStateFlow(MoblinkRelayState.CONNECTING)
    val state: StateFlow<MoblinkRelayState> = _state.asStateFlow()

    @Volatile private var closed = false

    @Volatile private var socket: WebSocket? = null
    private var tunnel: Tunnel? = null

    fun start() = post(::connect)

    fun stop() {
        closed = true
        executor.shutdownNow()
        socket?.cancel()
        synchronized(this) {
            tunnel?.close()
            tunnel = null
        }
    }

    private fun post(task: () -> Unit) {
        runCatching { executor.execute { if (!closed) task() } }
    }

    private fun deliver(task: () -> Unit) {
        runCatching { executor.submit { if (!closed) task() }.get(5, TimeUnit.SECONDS) }
    }

    private fun connect() {
        val request = runCatching { Request.Builder().url(streamerUrl).build() }.getOrNull()
        if (request == null) {
            _state.value = MoblinkRelayState.UNKNOWN_ERROR
            return
        }
        val created = sockets.newWebSocket(request, this)
        socket = created
        if (closed) created.cancel()
    }

    private fun reconnect(webSocket: WebSocket, state: MoblinkRelayState = MoblinkRelayState.CONNECTING) {
        if (webSocket !== socket) return
        socket = null
        webSocket.cancel()
        stopTunnel()
        _state.value = state
        runCatching { executor.schedule({ if (!closed) connect() }, retryMs, TimeUnit.MILLISECONDS) }
    }

    private fun stopTunnel() = synchronized(this) {
        tunnel?.close()
        tunnel = null
    }

    override fun onMessage(webSocket: WebSocket, text: String) = deliver {
        if (webSocket !== socket) return@deliver
        when (val message = decodeToRelay(text)) {
            is MoblinkToRelay.Hello -> webSocket.send(
                encode(MoblinkToStreamer.Identify(id, name, hashPassword(message.challenge, message.salt, password))),
            )
            is MoblinkToRelay.Identified -> when (message.result) {
                "ok" -> _state.value = MoblinkRelayState.CONNECTED
                "wrongPassword" -> reconnect(webSocket, MoblinkRelayState.WRONG_PASSWORD)
                else -> reconnect(webSocket, MoblinkRelayState.UNKNOWN_ERROR)
            }
            is MoblinkToRelay.StartTunnel -> startTunnel(webSocket, message)
            is MoblinkToRelay.Status -> {
                val (battery, thermal) = status()
                webSocket.send(encode(MoblinkToStreamer.Response(message.id, "ok", status = true, batteryPercentage = battery, thermalState = thermal)))
            }
            null -> Unit
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
        post { reconnect(webSocket) }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post { reconnect(webSocket) }

    private fun startTunnel(webSocket: WebSocket, message: MoblinkToRelay.StartTunnel) {
        stopTunnel()
        val created = try {
            val server = InetSocketAddress(destination.resolve(message.address), message.port)
            val toStreamer = createStreamerSocket()
            val toDestination = try {
                destination.createDatagramSocket().also { it.connect(server) }
            } catch (e: Exception) {
                toStreamer.close()
                throw e
            }
            Tunnel(toStreamer, toDestination) { post { reconnect(webSocket) } }
        } catch (_: Exception) {
            reconnect(webSocket)
            return
        }
        synchronized(this) {
            if (closed) {
                created.close()
                return
            }
            tunnel = created
        }
        created.start()
        webSocket.send(encode(MoblinkToStreamer.Response(message.id, "ok", tunnelPort = created.port)))
    }
}

private class Tunnel(
    private val toStreamer: DatagramSocket,
    private val toDestination: DatagramSocket,
    private val onError: () -> Unit,
) {
    @Volatile private var streamer: SocketAddress? = null

    val port: Int get() = toStreamer.localPort

    fun start() {
        thread("moblink-up") {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            pump(toStreamer) {
                packet.length = 2048
                toStreamer.receive(packet)
                streamer = packet.socketAddress
                packet.socketAddress = toDestination.remoteSocketAddress
                toDestination.send(packet)
            }
        }
        thread("moblink-down") {
            val packet = DatagramPacket(ByteArray(2048), 2048)
            pump(toDestination) {
                packet.length = 2048
                toDestination.receive(packet)
                val target = streamer
                if (target != null) {
                    packet.socketAddress = target
                    toStreamer.send(packet)
                }
            }
        }
    }

    fun close() {
        toStreamer.close()
        toDestination.close()
    }

    private fun thread(name: String, block: () -> Unit) = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    private fun pump(socket: DatagramSocket, step: () -> Unit) {
        while (!socket.isClosed) {
            try {
                step()
            } catch (_: PortUnreachableException) {
            } catch (_: IOException) {
                if (toStreamer.isClosed || toDestination.isClosed) return
                onError()
                return
            }
        }
    }
}
