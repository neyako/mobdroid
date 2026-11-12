package com.opirl.app.engine.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.flow.StateFlow

enum class LinkType { WIFI, CELLULAR, ETHERNET, OTHER }

interface NetworkLink {
    val id: String
    val name: String
    val type: LinkType
    val destination: InetSocketAddress? get() = null

    fun createDatagramSocket(): DatagramSocket

    fun resolve(host: String): InetAddress = InetAddress.getByName(host)
}

interface NetworkLinkProvider {
    val links: StateFlow<List<NetworkLink>>
}
