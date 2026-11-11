package app.mobdroid.stream

import app.mobdroid.engine.net.LinkType
import app.mobdroid.engine.srtla.SrtlaLinkState
import app.mobdroid.engine.srtla.SrtlaLinkStats
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkSharesTest {
    private fun link(id: String, type: LinkType, bytesSent: Long) =
        SrtlaLinkStats(id, id, type, SrtlaLinkState.Registered, true, 1f, bytesSent, 0, 0, 0f)

    @Test
    fun `shares are the bytes each link sent since the last sample`() {
        val shares = linkShares(
            mapOf("wifi" to 1000L, "cell" to 4000L),
            listOf(link("wifi", LinkType.WIFI, 4000), link("cell", LinkType.CELLULAR, 5000)),
        )
        assertEquals(listOf(LinkShare("Wi-Fi", 75), LinkShare("Cell", 25)), shares)
    }

    @Test
    fun `a link whose counter restarted counts as idle`() {
        val shares = linkShares(
            mapOf("wifi" to 9000L),
            listOf(link("wifi", LinkType.WIFI, 100), link("eth", LinkType.ETHERNET, 500)),
        )
        assertEquals(listOf(LinkShare("Wi-Fi", 0), LinkShare("Ethernet", 100)), shares)
    }

    @Test
    fun `nothing sent gives no shares`() {
        assertEquals(emptyList(), linkShares(mapOf("wifi" to 100L), listOf(link("wifi", LinkType.WIFI, 100))))
    }
}
