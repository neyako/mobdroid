package com.opirl.app.stream

import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.LinkUsage

data class LinkShare(val name: String, val percent: Int, val rttMs: Int = 0)

fun linkShares(previous: Map<String, Long>, links: List<LinkUsage>): List<LinkShare> {
    val sent = links.map { maxOf(it.bytesSent - (previous[it.id] ?: 0), 0) }
    val total = sent.sum()
    if (total == 0L) return emptyList()
    return links.mapIndexed { index, link ->
        val name = when (link.type) {
            LinkType.WIFI -> "Wi-Fi"
            LinkType.CELLULAR -> "Cell"
            LinkType.ETHERNET -> "Ethernet"
            LinkType.OTHER -> link.name
        }
        LinkShare(name, Math.round(sent[index] * 100f / total), link.rttMs)
    }
}
