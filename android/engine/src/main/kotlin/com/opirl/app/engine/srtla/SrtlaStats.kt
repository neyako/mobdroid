package com.opirl.app.engine.srtla

import com.opirl.app.engine.net.LinkType

enum class SrtlaLinkState { Connecting, Registering, Registered, Failed }

data class SrtlaLinkStats(
    val id: String,
    val name: String,
    val type: LinkType,
    val state: SrtlaLinkState,
    val enabled: Boolean,
    val priority: Float,
    val bytesSent: Long,
    val rttMs: Int,
    val packetsInFlight: Int,
    val windowPackets: Float,
)

data class LinkPriority(val priority: Int = 1, val enabled: Boolean = true)

data class SrtlaPriorities(
    val enabled: Boolean = false,
    val byType: Map<LinkType, LinkPriority> = mapOf(
        LinkType.CELLULAR to LinkPriority(),
        LinkType.WIFI to LinkPriority(),
    ),
) {
    internal fun priorityOf(type: LinkType): Float {
        if (!enabled) {
            return 1f
        }
        val lowest = byType.values.filter { it.enabled }.minOfOrNull { it.priority } ?: return 1f
        val entry = byType[type] ?: return 1f
        return if (entry.enabled) (entry.priority - lowest + 1).toFloat() else 0f
    }
}
