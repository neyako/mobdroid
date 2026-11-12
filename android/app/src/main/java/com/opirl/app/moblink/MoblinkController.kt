package com.opirl.app.moblink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.opirl.app.R
import com.opirl.app.engine.moblink.MOBLINK_SERVICE_TYPE
import com.opirl.app.engine.moblink.MoblinkRelay
import com.opirl.app.engine.moblink.MoblinkRelayState
import com.opirl.app.engine.moblink.MoblinkRelayStatus
import com.opirl.app.engine.moblink.MoblinkStreamer
import com.opirl.app.engine.moblink.MoblinkStreamerListener
import com.opirl.app.engine.moblink.MoblinkThermalState
import com.opirl.app.engine.moblink.MoblinkTunnel
import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.settings.MoblinkSettings
import com.opirl.app.stream.AndroidNetworkLinks
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class MoblinkStatus(val text: String, val ok: Boolean)

fun relayStatusText(relay: MoblinkRelayStatus): Pair<String, Boolean> {
    val hot = relay.thermalState == MoblinkThermalState.RED
    val battery = relay.batteryPercentage
    val text = relay.name + (if (hot) "🔥" else "") + when {
        battery == null -> ""
        battery <= 10 -> "🪫$battery%"
        else -> "🔋$battery%"
    }
    return text to (!hot && (battery == null || battery > 10))
}

enum class RelaySummary { WAITING_FOR_STREAMERS, NO_INTERFACE, CONNECTING, CONNECTED, WRONG_PASSWORD, UNKNOWN_ERROR }

fun relaySummary(streamers: Int, states: List<MoblinkRelayState>): RelaySummary = when {
    streamers == 0 -> RelaySummary.WAITING_FOR_STREAMERS
    states.isEmpty() -> RelaySummary.NO_INTERFACE
    MoblinkRelayState.WRONG_PASSWORD in states -> RelaySummary.WRONG_PASSWORD
    MoblinkRelayState.UNKNOWN_ERROR in states -> RelaySummary.UNKNOWN_ERROR
    MoblinkRelayState.CONNECTING in states -> RelaySummary.CONNECTING
    else -> RelaySummary.CONNECTED
}

fun moblinkUrl(address: InetAddress, port: Int): String? {
    if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress) return null
    val host = address.hostAddress?.substringBefore('%') ?: return null
    return if (address is Inet6Address) "ws://[$host]:$port" else "ws://$host:$port"
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MoblinkController(
    context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val battery: () -> Int,
    private val thermal: () -> Int,
) : MoblinkStreamerListener {
    private val context = context.applicationContext
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val nsd = this.context.getSystemService(NsdManager::class.java)
    private val _links = MutableStateFlow<List<NetworkLink>>(emptyList())
    val links: StateFlow<List<NetworkLink>> = _links.asStateFlow()
    private val _status = MutableStateFlow<MoblinkStatus?>(null)
    val status: StateFlow<MoblinkStatus?> = _status.asStateFlow()
    private val _relays = MutableStateFlow<List<MoblinkRelayStatus>>(emptyList())
    val relays: StateFlow<List<MoblinkRelayStatus>> = _relays.asStateFlow()
    private val _relayRunning = MutableStateFlow(false)
    val relayRunning: StateFlow<Boolean> = _relayRunning.asStateFlow()

    private var settings = MoblinkSettings()
    private var streamer: MoblinkStreamer? = null
    private var streamerJob: Job? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var destination: InetSocketAddress? = null
    private val streamerRelays = MutableStateFlow<StateFlow<List<MoblinkRelayStatus>>?>(null)

    private var relayJob: Job? = null
    private var relaySettings: MoblinkSettings? = null
    private var relayLinks: AndroidNetworkLinks? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private val wifi = MutableStateFlow<Network?>(null)
    private var discovery: NsdManager.DiscoveryListener? = null
    private val discovered = MutableStateFlow<Map<String, String>>(emptyMap())
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val running = HashMap<Pair<String, String>, Running>()
    private val relayStates = MutableStateFlow<List<StateFlow<MoblinkRelayState>>>(emptyList())
    private val relayStreamers = MutableStateFlow(0)

    private class Running(val relay: MoblinkRelay, val wifi: Network?)

    init {
        scope.launch {
            combine(
                streamerRelays.flatMapLatest { it ?: flowOf(null) },
                relayStates.flatMapLatest { states -> if (states.isEmpty()) flowOf(emptyList()) else combine(states) { it.toList() } },
                relayStreamers,
                _relayRunning,
            ) { relays, states, streamers, relayOn ->
                _relays.value = relays.orEmpty()
                val parts = ArrayList<String>()
                var ok = true
                relays?.forEach {
                    val (text, fine) = relayStatusText(it)
                    parts += text
                    ok = ok && fine
                }
                if (relayOn) {
                    val summary = relaySummary(streamers, states)
                    parts += text(summary)
                    ok = ok && (summary == RelaySummary.CONNECTED || summary == RelaySummary.WAITING_FOR_STREAMERS)
                }
                if (relays == null && !relayOn) null else MoblinkStatus(parts.joinToString(", "), ok)
            }.collect { _status.value = it }
        }
    }

    private fun text(summary: RelaySummary) = context.getString(
        when (summary) {
            RelaySummary.WAITING_FOR_STREAMERS -> R.string.moblink_waiting_for_streamers
            RelaySummary.NO_INTERFACE -> R.string.moblink_no_interface
            RelaySummary.CONNECTING -> R.string.connecting
            RelaySummary.CONNECTED -> R.string.connected
            RelaySummary.WRONG_PASSWORD -> R.string.message_wrong_password
            RelaySummary.UNKNOWN_ERROR -> R.string.moblink_unknown_error
        },
    )

    @Synchronized
    fun sync(next: MoblinkSettings, active: Boolean) {
        val previous = settings
        settings = next
        val streamerWanted = active && next.streamer && next.streamerPort in 1..65535 && next.password.isNotEmpty()
        val streamerChanged = previous.streamerPort != next.streamerPort || previous.password != next.password
        if (streamer != null && (!streamerWanted || streamerChanged)) stopStreamer()
        if (streamerWanted && streamer == null) startStreamer(next)

        val relayWanted = next.relay && (!next.relayManual || (next.relayUrl.isNotBlank() && next.password.isNotEmpty()))
        val relayChanged = previous.copy(streamer = next.streamer, streamerPort = next.streamerPort) != next
        if (_relayRunning.value && (!relayWanted || relayChanged)) stopRelay()
        if (relayWanted && !_relayRunning.value && active) startRelay(next)
    }

    @Synchronized
    fun startTunnels(address: InetSocketAddress) {
        destination = address
        streamer?.startTunnels(address.address.hostAddress ?: return, address.port)
    }

    @Synchronized
    fun stopTunnels() {
        destination = null
        streamer?.stopTunnels()
    }

    private fun startStreamer(settings: MoblinkSettings) {
        val created = MoblinkStreamer(settings.streamerPort, settings.password, this)
        streamer = created
        streamerRelays.value = created.relayStatuses
        created.start()
        destination?.let { created.startTunnels(it.address.hostAddress ?: return@let, it.port) }
        streamerJob = scope.launch {
            created.listening.collect { listening -> if (listening) register(created, settings.relayId) }
        }
    }

    private fun stopStreamer() {
        streamerJob?.cancel()
        streamerJob = null
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
        streamer?.stop()
        streamer = null
        streamerRelays.value = null
        _links.value = emptyList()
    }

    @Synchronized
    private fun register(running: MoblinkStreamer, id: String) {
        if (streamer !== running || registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = id
            serviceType = MOBLINK_SERVICE_TYPE
            port = running.localPort
            setAttribute("name", deviceName())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Moblink streamer is not advertised: $code")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit

            override fun onServiceRegistered(info: NsdServiceInfo) = Unit

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        }
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { registration = listener }
            .onFailure { Log.w(TAG, "Moblink streamer is not advertised", it) }
    }

    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    override fun onTunnelAdded(tunnel: MoblinkTunnel) {
        _links.update { links -> links.filterNot { (it as TunnelLink).tunnel.relayId == tunnel.relayId } + TunnelLink(tunnel) }
    }

    override fun onTunnelRemoved(tunnel: MoblinkTunnel) {
        _links.update { links -> links.filterNot { (it as TunnelLink).tunnel === tunnel } }
    }

    private inner class TunnelLink(val tunnel: MoblinkTunnel) : NetworkLink {
        override val id = "moblink-${tunnel.relayId}-${tunnel.address.port}"
        override val name = tunnel.relayName
        override val type = LinkType.OTHER
        override val destination = tunnel.address

        override fun createDatagramSocket(): DatagramSocket {
            val socket = DatagramSocket()
            try {
                networkOf(tunnel.local)?.bindSocket(socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
            return socket
        }
    }

    @Suppress("DEPRECATION")
    private fun networkOf(local: InetAddress): Network? = connectivity.allNetworks.firstOrNull { network ->
        connectivity.getLinkProperties(network)?.linkAddresses?.any { it.address == local } == true
    }

    private fun startRelay(settings: MoblinkSettings) {
        _relayRunning.value = true
        relaySettings = settings
        val destinations = AndroidNetworkLinks(context).also { it.start(setOf(LinkType.CELLULAR, LinkType.ETHERNET)) }
        relayLinks = destinations
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                wifi.value = network
            }

            override fun onLost(network: Network) {
                wifi.compareAndSet(network, null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivity.registerNetworkCallback(request, callback) }.onSuccess { wifiCallback = callback }
        if (settings.relayManual) {
            discovered.value = mapOf("manual" to settings.relayUrl.trim())
        } else {
            discover(settings.relayId)
        }
        relayJob = scope.launch {
            combine(discovered, destinations.links, wifi) { urls, links, network -> Triple(urls.values.distinct(), links, network) }
                .collect { (urls, links, network) -> reconcile(settings, urls, links, network) }
        }
    }

    private fun stopRelay() {
        relayJob?.cancel()
        relayJob = null
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        resolveQueue.clear()
        resolving = false
        discovered.value = emptyMap()
        wifiCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        wifiCallback = null
        wifi.value = null
        relayLinks?.stop()
        relayLinks = null
        running.values.forEach { it.relay.stop() }
        running.clear()
        relayStates.value = emptyList()
        relayStreamers.value = 0
        relaySettings = null
        _relayRunning.value = false
    }

    @Synchronized
    private fun reconcile(settings: MoblinkSettings, urls: List<String>, links: List<NetworkLink>, network: Network?) {
        if (relaySettings !== settings) return
        val wanted = urls.flatMap { url -> links.map { url to it } }.associateBy { (url, link) -> url to link.id }
        running.entries.removeAll { (key, current) ->
            val keep = wanted[key]?.second === current.relay.destination && current.wifi == network
            if (!keep) current.relay.stop()
            !keep
        }
        val client = if (network == null) http else http.newBuilder().socketFactory(network.socketFactory).build()
        for ((key, pair) in wanted) {
            if (key in running) continue
            val (url, link) = pair
            val cellular = link.type == LinkType.CELLULAR
            val relay = MoblinkRelay(
                client,
                url,
                settings.password,
                if (cellular) settings.relayId else UUID.nameUUIDFromBytes((settings.relayId + link.name).toByteArray()).toString(),
                if (cellular) settings.relayName else "${settings.relayName}-${link.name}",
                link,
                { DatagramSocket().also { socket -> network?.bindSocket(socket) } },
                {
                    battery() to when {
                        thermal() >= PowerManager.THERMAL_STATUS_CRITICAL -> MoblinkThermalState.RED
                        thermal() >= PowerManager.THERMAL_STATUS_SEVERE -> MoblinkThermalState.YELLOW
                        else -> MoblinkThermalState.WHITE
                    }
                },
            )
            running[key] = Running(relay, network)
            relay.start()
        }
        relayStates.value = running.values.map { it.relay.state }
        relayStreamers.value = urls.size
    }

    private fun discover(ownId: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w(TAG, "Moblink discovery failed: $code")
            }

            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit

            override fun onDiscoveryStarted(type: String) = Unit

            override fun onDiscoveryStopped(type: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.equals(ownId, ignoreCase = true)) resolve(this, info)
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                discovered.update { it - info.serviceName }
            }
        }
        runCatching { nsd.discoverServices(MOBLINK_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { discovery = listener }
            .onFailure { Log.w(TAG, "Moblink discovery failed", it) }
    }

    @Synchronized
    private fun resolve(owner: NsdManager.DiscoveryListener, info: NsdServiceInfo?) {
        if (info != null) resolveQueue.addLast(info)
        if (resolving) return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val done = { resolved: NsdServiceInfo? ->
            synchronized(this) {
                resolving = false
                if (discovery === owner) {
                    @Suppress("DEPRECATION")
                    val url = resolved?.host?.takeIf { address -> !isLocal(address) }?.let { moblinkUrl(it, resolved.port) }
                    if (url != null) discovered.update { it + (resolved.serviceName to url) }
                    resolve(owner, null)
                }
            }
        }
        @Suppress("DEPRECATION")
        runCatching {
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, code: Int) = done(null)

                    override fun onServiceResolved(info: NsdServiceInfo) = done(info)
                },
            )
        }.onFailure { done(null) }
    }

    private fun isLocal(address: InetAddress): Boolean =
        runCatching { NetworkInterface.getByInetAddress(address) != null }.getOrDefault(false)

    private companion object {
        const val TAG = "Moblink"
    }
}
