package com.opirl.app.moblink

import com.opirl.app.engine.moblink.MoblinkRelayState
import com.opirl.app.engine.moblink.MoblinkRelayStatus
import com.opirl.app.engine.moblink.MoblinkThermalState
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class MoblinkStatusTest {
    @Test
    fun `a relay is shown with its battery and flagged when hot or nearly empty`() {
        assertEquals("Blue🔋80%" to true, relayStatusText(MoblinkRelayStatus("1", "Blue", 80, MoblinkThermalState.WHITE)))
        assertEquals("Blue🪫10%" to false, relayStatusText(MoblinkRelayStatus("1", "Blue", 10, MoblinkThermalState.YELLOW)))
        assertEquals("Blue🔥🔋50%" to false, relayStatusText(MoblinkRelayStatus("1", "Blue", 50, MoblinkThermalState.RED)))
        assertEquals("Blue" to true, relayStatusText(MoblinkRelayStatus("1", "Blue")))
    }

    @Test
    fun `the relay summary reports the worst of its connections`() {
        val connected = MoblinkRelayState.CONNECTED
        assertEquals(RelaySummary.WAITING_FOR_STREAMERS, relaySummary(0, emptyList()))
        assertEquals(RelaySummary.NO_INTERFACE, relaySummary(1, emptyList()))
        assertEquals(RelaySummary.CONNECTED, relaySummary(1, listOf(connected, connected)))
        assertEquals(RelaySummary.CONNECTING, relaySummary(2, listOf(connected, MoblinkRelayState.CONNECTING)))
        assertEquals(RelaySummary.WRONG_PASSWORD, relaySummary(2, listOf(MoblinkRelayState.CONNECTING, MoblinkRelayState.WRONG_PASSWORD)))
    }

    @Test
    fun `a discovered streamer becomes a websocket URL unless it is not reachable`() {
        assertEquals("ws://192.168.1.20:7777", moblinkUrl(InetAddress.getByName("192.168.1.20"), 7777))
        assertEquals("ws://[2001:db8:0:0:0:0:0:1]:7777", moblinkUrl(InetAddress.getByName("2001:db8::1"), 7777))
        assertEquals(null, moblinkUrl(InetAddress.getByName("127.0.0.1"), 7777))
        assertEquals(null, moblinkUrl(InetAddress.getByName("fe80::1"), 7777))
        assertEquals(null, moblinkUrl(InetAddress.getByName("169.254.3.4"), 7777))
    }
}
