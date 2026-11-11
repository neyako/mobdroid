package app.mobdroid.engine.net

import java.net.DatagramSocket
import kotlinx.coroutines.flow.StateFlow

enum class LinkType { WIFI, CELLULAR, ETHERNET, OTHER }

interface NetworkLink {
    val id: String
    val name: String
    val type: LinkType

    fun createDatagramSocket(): DatagramSocket
}

interface NetworkLinkProvider {
    val links: StateFlow<List<NetworkLink>>
}
