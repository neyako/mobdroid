package app.mobdroid.engine.srtla

import app.mobdroid.engine.net.LinkType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SrtlaLinkWindowTest {
    private fun windowOf(size: Int): SrtlaLinkWindow {
        val window = SrtlaLinkWindow()
        repeat((20_000 - size) / 100) {
            window.onSend(it)
            window.onNak(it)
        }
        assertEquals(size, window.windowSize)
        return window
    }

    @Test
    fun startsWithDefaultWindow() {
        val window = SrtlaLinkWindow()
        assertEquals(20_000, window.windowSize)
        assertEquals(20_000, window.score(1f))
        assertEquals(60_000, window.score(3f))
    }

    @Test
    fun scoreDividesWindowByPacketsInFlightPlusOne() {
        val window = SrtlaLinkWindow()
        repeat(4) { window.onSend(it) }
        assertEquals(4, window.packetsInFlight)
        assertEquals(4_000, window.score(1f))
    }

    @Test
    fun priorityMultipliesTheScoreOfALinkWithAGrownWindow() {
        val window = SrtlaLinkWindow()
        repeat(40_000) { window.onSrtlaAck(it) }
        assertEquals(60_000, window.windowSize)
        assertEquals(180_000, window.score(3f))
    }

    @Test
    fun priorityIsScaledLinearlyInTheStableBand() {
        assertEquals(30_000, windowOf(15_000).score(3f))
        assertEquals(20_000, windowOf(20_000).score(1f))
        assertEquals(10_000, windowOf(10_000).score(9f))
    }

    @Test
    fun priorityIsIgnoredBelowTheStableMinimum() {
        assertEquals(9_000, windowOf(9_000).score(5f))
    }

    @Test
    fun nakShrinksWindowOnlyForPacketsSentOnThisLinkAndStopsAtMinimum() {
        val window = SrtlaLinkWindow()
        window.onSend(7)
        window.onNak(8)
        assertEquals(20_000, window.windowSize)
        window.onNak(7)
        assertEquals(19_900, window.windowSize)
        assertEquals(0, window.packetsInFlight)
        repeat(300) {
            window.onSend(it)
            window.onNak(it)
        }
        assertEquals(1_000, window.windowSize)
    }

    @Test
    fun srtlaAckGrowsEveryWindowSlowlyAndTheSendingLinkFaster() {
        val sender = SrtlaLinkWindow()
        val bystander = SrtlaLinkWindow()
        repeat(30) { sender.onSend(it) }
        sender.onSrtlaAck(0)
        bystander.onSrtlaAck(0)
        assertEquals(20_000 + 29 + 1, sender.windowSize)
        assertEquals(20_000 + 1, bystander.windowSize)
        assertEquals(29, sender.packetsInFlight)
    }

    @Test
    fun windowIsCapped() {
        val window = SrtlaLinkWindow()
        repeat(50_000) { window.onSrtlaAck(it) }
        assertEquals(60_000, window.windowSize)
    }

    @Test
    fun srtAckDropsEverythingBeforeTheAcknowledgedSequence() {
        val window = SrtlaLinkWindow()
        listOf(10, 11, 12, 20).forEach(window::onSend)
        window.onSrtAck(12)
        assertEquals(2, window.packetsInFlight)
        window.onSrtAck(100)
        assertEquals(0, window.packetsInFlight)
    }

    @Test
    fun srtAckSurvivesSequenceWrap() {
        val window = SrtlaLinkWindow()
        listOf(0x7ffffffe, 0x7fffffff, 0, 1).forEach(window::onSend)
        window.onSrtAck(0)
        assertEquals(2, window.packetsInFlight)
    }

    @Test
    fun retransmissionOfTheSamePacketIsNotCountedTwice() {
        val window = SrtlaLinkWindow()
        window.onSend(5)
        window.onSend(5)
        assertEquals(1, window.packetsInFlight)
    }

    @Test
    fun inFlightSetIsBounded() {
        val window = SrtlaLinkWindow()
        repeat(10_000) { window.onSend(it) }
        assertTrue(window.packetsInFlight <= 4096)
        window.onSend(9_999)
        assertTrue(window.packetsInFlight <= 4096)
    }

    @Test
    fun resetRestoresTheDefaults() {
        val window = SrtlaLinkWindow()
        window.onSend(1)
        window.onNak(1)
        window.reset()
        assertEquals(20_000, window.windowSize)
        assertEquals(0, window.packetsInFlight)
    }
}

class SrtlaPrioritiesTest {
    private fun priorities(vararg entries: Pair<LinkType, LinkPriority>, enabled: Boolean = true) =
        SrtlaPriorities(enabled, entries.toMap())

    @Test
    fun everythingIsEqualWhileDisabled() {
        val disabled = priorities(
            LinkType.CELLULAR to LinkPriority(5),
            LinkType.WIFI to LinkPriority(1, false),
            enabled = false,
        )
        assertEquals(1f, disabled.priorityOf(LinkType.CELLULAR))
        assertEquals(1f, disabled.priorityOf(LinkType.WIFI))
        assertEquals(1f, SrtlaPriorities().priorityOf(LinkType.CELLULAR))
    }

    @Test
    fun lowestEnabledPriorityBecomesOneAndOthersAreRelative() {
        val enabled = priorities(LinkType.CELLULAR to LinkPriority(3), LinkType.WIFI to LinkPriority(7))
        assertEquals(1f, enabled.priorityOf(LinkType.CELLULAR))
        assertEquals(5f, enabled.priorityOf(LinkType.WIFI))
    }

    @Test
    fun disabledEntryNeverCarriesTraffic() {
        val enabled = priorities(LinkType.CELLULAR to LinkPriority(2), LinkType.WIFI to LinkPriority(1, false))
        assertEquals(1f, enabled.priorityOf(LinkType.CELLULAR))
        assertEquals(0f, enabled.priorityOf(LinkType.WIFI))
    }

    @Test
    fun unlistedTypesAndAllDisabledFallBackToOne() {
        val enabled = priorities(LinkType.CELLULAR to LinkPriority(4))
        assertEquals(1f, enabled.priorityOf(LinkType.ETHERNET))
        val none = priorities(LinkType.CELLULAR to LinkPriority(4, false), LinkType.WIFI to LinkPriority(1, false))
        assertEquals(1f, none.priorityOf(LinkType.CELLULAR))
        assertEquals(1f, none.priorityOf(LinkType.WIFI))
    }
}
