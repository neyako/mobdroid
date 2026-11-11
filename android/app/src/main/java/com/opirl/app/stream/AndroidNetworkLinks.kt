package com.opirl.app.stream

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.net.NetworkLinkProvider
import java.net.DatagramSocket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class AndroidNetworkLinks(context: Context) : NetworkLinkProvider {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _links = MutableStateFlow<List<NetworkLink>>(emptyList())
    override val links: StateFlow<List<NetworkLink>> = _links
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    private class Link(val network: Network, override val type: LinkType, override val name: String) : NetworkLink {
        override val id = "${type.name}-$network"

        override fun createDatagramSocket(): DatagramSocket {
            val socket = DatagramSocket()
            try {
                network.bindSocket(socket)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
            return socket
        }
    }

    fun start(types: Set<LinkType>) {
        if (callbacks.isNotEmpty()) return
        for ((transport, type) in TRANSPORTS) {
            if (type !in types) continue
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                    val name = properties.interfaceName ?: type.name.lowercase()
                    _links.update { list ->
                        if (list.any { (it as Link).network == network }) list else list + Link(network, type, name)
                    }
                }

                override fun onLost(network: Network) {
                    _links.update { list -> list.filterNot { (it as Link).network == network } }
                }
            }
            val request = NetworkRequest.Builder()
                .addTransportType(transport)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            runCatching { connectivity.requestNetwork(request, callback) }.onSuccess { callbacks += callback }
        }
    }

    fun stop() {
        callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        callbacks.clear()
        _links.value = emptyList()
    }

    private companion object {
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_WIFI to LinkType.WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR to LinkType.CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET to LinkType.ETHERNET,
        )
    }
}
