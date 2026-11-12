package com.opirl.app.engine.moblink

import com.opirl.app.engine.net.WebServer
import com.opirl.app.engine.net.WebServerHandler
import com.opirl.app.engine.net.WebSocketConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MoblinkTunnel(val relayId: String, val relayName: String, val address: InetSocketAddress, val local: InetAddress)

data class MoblinkRelayStatus(
    val id: String,
    val name: String,
    val batteryPercentage: Int? = null,
    val thermalState: MoblinkThermalState? = null,
)

interface MoblinkStreamerListener {
    fun onTunnelAdded(tunnel: MoblinkTunnel)

    fun onTunnelRemoved(tunnel: MoblinkTunnel)
}

class MoblinkStreamer(
    port: Int,
    private val password: String,
    private val listener: MoblinkStreamerListener,
    private val statusMs: Long = 5000,
    private val retryMs: Long = 5000,
) : WebServerHandler {
    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "moblink-streamer").apply { isDaemon = true } }
    private val server = WebServer(port, this, "moblink")
    private val relays = LinkedHashMap<WebSocketConnection, Relay>()
    private var destination: Pair<String, Int>? = null
    private val _relays = MutableStateFlow<List<MoblinkRelayStatus>>(emptyList())
    val relayStatuses: StateFlow<List<MoblinkRelayStatus>> = _relays.asStateFlow()
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    @Volatile private var closed = false

    val localPort: Int get() = server.localPort

    fun start() {
        post(::bind)
        runCatching { executor.scheduleWithFixedDelay({ if (!closed) tick() }, statusMs, statusMs, TimeUnit.MILLISECONDS) }
    }

    fun stop() {
        closed = true
        executor.shutdownNow()
        server.stop()
        _listening.value = false
        _relays.value = emptyList()
    }

    fun startTunnels(address: String, port: Int) = post {
        destination = address to port
        relays.values.forEach { it.startTunnel() }
    }

    fun stopTunnels() = post {
        destination = null
        relays.values.forEach { it.removeTunnel() }
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
            _listening.value = true
        } catch (_: Exception) {
            runCatching { executor.schedule({ if (!closed) bind() }, retryMs, TimeUnit.MILLISECONDS) }
        }
    }

    private fun tick() {
        for (relay in relays.values.toList()) {
            if (!relay.pongReceived) {
                relay.connection.close()
                continue
            }
            relay.pongReceived = false
            relay.connection.ping()
            relay.requestStatus()
        }
    }

    private fun publish() {
        _relays.value = relays.values.filter { it.identified }.sortedBy { it.name }.map {
            MoblinkRelayStatus(it.id, it.name, it.batteryPercentage, it.thermalState)
        }
    }

    override fun onOpen(connection: WebSocketConnection) = post {
        val relay = Relay(connection)
        relays[connection] = relay
        connection.send(encode(MoblinkToRelay.Hello(relay.challenge, relay.salt)))
    }

    override fun onText(connection: WebSocketConnection, text: String) = deliver {
        val relay = relays[connection] ?: return@deliver
        when (val message = decodeToStreamer(text)) {
            is MoblinkToStreamer.Identify -> relay.identify(message)
            is MoblinkToStreamer.Response -> relay.respond(message)
            null -> connection.close()
        }
    }

    override fun onPong(connection: WebSocketConnection) = post {
        relays[connection]?.pongReceived = true
    }

    override fun onClosed(connection: WebSocketConnection) = post {
        relays.remove(connection)?.removeTunnel()
        publish()
    }

    private inner class Relay(val connection: WebSocketConnection) {
        val challenge = randomToken()
        val salt = randomToken()
        var identified = false
        var id = ""
        var name = ""
        var batteryPercentage: Int? = null
        var thermalState: MoblinkThermalState? = null
        var pongReceived = true
        private var nextId = 0
        private val pending = HashMap<Int, (MoblinkToStreamer.Response) -> Unit>()
        private var tunnel: MoblinkTunnel? = null

        fun identify(message: MoblinkToStreamer.Identify) {
            if (message.authentication != hashPassword(challenge, salt, password)) {
                connection.send(encode(MoblinkToRelay.Identified("wrongPassword")))
                connection.close()
                return
            }
            relays.values.filter { it !== this && it.identified && it.id.equals(message.id, ignoreCase = true) }.forEach {
                it.connection.close()
            }
            id = message.id
            name = message.name.lineSequence().firstOrNull().orEmpty().trim().take(30)
            identified = true
            connection.send(encode(MoblinkToRelay.Identified("ok")))
            startTunnel()
            requestStatus()
            publish()
        }

        fun respond(message: MoblinkToStreamer.Response) {
            if (!identified) {
                connection.close()
                return
            }
            val callback = pending.remove(message.id) ?: return
            if (message.result == "ok") callback(message)
        }

        fun startTunnel() {
            val (address, port) = destination ?: return
            if (!identified) return
            removeTunnel()
            request({ MoblinkToRelay.StartTunnel(it, address, port) }) { response ->
                val tunnelPort = response.tunnelPort ?: return@request
                if (destination != address to port || relays[connection] !== this) return@request
                removeTunnel()
                val added = MoblinkTunnel(id, name, InetSocketAddress(connection.remoteAddress, tunnelPort), connection.localAddress)
                tunnel = added
                listener.onTunnelAdded(added)
            }
        }

        fun removeTunnel() {
            tunnel?.let(listener::onTunnelRemoved)
            tunnel = null
        }

        fun requestStatus() {
            if (!identified) return
            request({ MoblinkToRelay.Status(it) }) { response ->
                batteryPercentage = response.batteryPercentage
                thermalState = response.thermalState
                publish()
            }
        }

        private fun request(message: (Int) -> MoblinkToRelay, onResponse: (MoblinkToStreamer.Response) -> Unit) {
            val requestId = ++nextId
            if (pending.size >= 32) pending.clear()
            pending[requestId] = onResponse
            connection.send(encode(message(requestId)))
        }
    }
}
