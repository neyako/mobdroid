package com.opirl.app.engine.moblink

import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.srt.SrtEngine
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srt.call
import com.opirl.app.engine.srtla.CollectingListener
import com.opirl.app.engine.srtla.FakeSrtlaServer
import com.opirl.app.engine.srtla.SrtlaClient
import com.opirl.app.engine.srtla.SrtlaLinkState
import com.opirl.app.engine.srtla.TestLink
import com.opirl.app.engine.srtla.TestLinkProvider
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient

private const val RELAY_ID = "0F8FAD5B-D9CB-469F-A165-70867728950E"

private class Fixture(password: String = "secret") : MoblinkStreamerListener, AutoCloseable {
    val added = LinkedBlockingQueue<MoblinkTunnel>()
    val removed = LinkedBlockingQueue<MoblinkTunnel>()
    val http = OkHttpClient()
    val streamer = MoblinkStreamer(0, "secret", this, statusMs = 100).also {
        it.start()
        await(what = "the streamer listens") { it.listening.value }
    }
    val relay = MoblinkRelay(
        http,
        "ws://127.0.0.1:${streamer.localPort}",
        password,
        RELAY_ID,
        "Blue\nignored",
        TestLink("cell", LinkType.CELLULAR, "127.0.0.2"),
        { DatagramSocket(0, InetAddress.getByName("127.0.0.1")) },
        { 80 to MoblinkThermalState.YELLOW },
        retryMs = 100,
    ).also { it.start() }

    override fun onTunnelAdded(tunnel: MoblinkTunnel) {
        added += tunnel
    }

    override fun onTunnelRemoved(tunnel: MoblinkTunnel) {
        removed += tunnel
    }

    override fun close() {
        relay.stop()
        streamer.stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

class MoblinkTest {
    @Test
    fun aRelaysTunnelJoinsTheSrtlaGroup() {
        val server = FakeSrtlaServer()
        Fixture().use { fixture ->
            fixture.streamer.startTunnels("127.0.0.1", server.address.port)
            val tunnel = assertNotNull(fixture.added.poll(5, TimeUnit.SECONDS))
            assertEquals(RELAY_ID, tunnel.relayId)
            assertEquals("Blue", tunnel.relayName)
            val moblink = object : NetworkLink {
                override val id = "moblink"
                override val name = tunnel.relayName
                override val type = LinkType.OTHER
                override val destination: InetSocketAddress = tunnel.address

                override fun createDatagramSocket() = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            }
            val engine = SrtEngine("test-moblink") {}
            val listener = CollectingListener()
            val client = SrtlaClient(engine, server.address, TestLinkProvider(TestLink("wifi", LinkType.WIFI, "127.0.0.3"), moblink))
            client.listener = listener
            engine.execute { client.start() }
            try {
                assertTrue(listener.ready.await(5, TimeUnit.SECONDS))
                await(what = "both links registered") {
                    engine.call { client.refreshStats() }
                    client.linkStats().count { it.state == SrtlaLinkState.Registered } == 2
                }
                assertEquals(2, server.registered.size)
                assertTrue(server.registered.any { (it as InetSocketAddress).address.hostAddress == "127.0.0.2" })
                await(what = "the relay's status") {
                    fixture.streamer.relayStatuses.value == listOf(MoblinkRelayStatus(RELAY_ID, "Blue", 80, MoblinkThermalState.YELLOW))
                }
                assertEquals(MoblinkRelayState.CONNECTED, fixture.relay.state.value)
            } finally {
                engine.call { client.close() }
                engine.shutdown()
                server.close()
            }
            fixture.streamer.stopTunnels()
            assertEquals(tunnel.address, assertNotNull(fixture.removed.poll(5, TimeUnit.SECONDS)).address)
        }
    }

    @Test
    fun aRelayThatConnectsWhileLiveGetsATunnelAndLosesItWhenItLeaves() {
        Fixture().use { fixture ->
            await(what = "the relay identifies") { fixture.streamer.relayStatuses.value.isNotEmpty() }
            assertNull(fixture.added.poll())
            fixture.streamer.startTunnels("127.0.0.1", 5000)
            val tunnel = assertNotNull(fixture.added.poll(5, TimeUnit.SECONDS))
            fixture.relay.stop()
            assertEquals(tunnel.address, assertNotNull(fixture.removed.poll(5, TimeUnit.SECONDS)).address)
            await(what = "the relay is gone") { fixture.streamer.relayStatuses.value.isEmpty() }
        }
    }

    @Test
    fun aWrongPasswordIsReportedAndGetsNoTunnel() {
        Fixture(password = "wrong").use { fixture ->
            fixture.streamer.startTunnels("127.0.0.1", 5000)
            await(what = "the relay reports the password") { fixture.relay.state.value == MoblinkRelayState.WRONG_PASSWORD }
            assertNull(fixture.added.poll(300, TimeUnit.MILLISECONDS))
            assertTrue(fixture.streamer.relayStatuses.value.isEmpty())
        }
    }

    @Test
    fun readsAndWritesMoblinsJson() {
        val identify = assertIs<MoblinkToStreamer.Identify>(
            decodeToStreamer("""{"identify":{"id":"$RELAY_ID","name":"Blue","authentication":"abc"}}"""),
        )
        assertEquals(listOf(RELAY_ID, "Blue", "abc"), listOf(identify.id, identify.name, identify.authentication))
        val status = assertIs<MoblinkToStreamer.Response>(decodeToStreamer("""{"response":{"id":3,"result":{"ok":{}},"data":{"status":{}}}}"""))
        assertEquals(listOf(3, "ok", null, null), listOf(status.id, status.result, status.batteryPercentage, status.thermalState))
        val tunnel = assertIs<MoblinkToStreamer.Response>(
            decodeToStreamer("""{"response":{"id":4,"result":{"ok":{}},"data":{"startTunnel":{"port":50123}}}}"""),
        )
        assertEquals(50123, tunnel.tunnelPort)
        val request = assertIs<MoblinkToRelay.StartTunnel>(
            decodeToRelay("""{"request":{"id":1,"data":{"startTunnel":{"address":"1.2.3.4","port":5000}}}}"""),
        )
        assertEquals(listOf(1, "1.2.3.4", 5000), listOf(request.id, request.address, request.port))
        assertEquals("""{"request":{"id":2,"data":{"status":{}}}}""", encode(MoblinkToRelay.Status(2)))
        assertEquals("""{"identified":{"result":{"ok":{}}}}""", encode(MoblinkToRelay.Identified("ok")))
        assertEquals(
            """{"response":{"id":5,"result":{"ok":{}},"data":{"status":{"batteryPercentage":80,"thermalState":"red"}}}}""",
            encode(MoblinkToStreamer.Response(5, "ok", status = true, batteryPercentage = 80, thermalState = MoblinkThermalState.RED)),
        )
    }

    @Test
    fun hashesThePasswordLikeMoblin() {
        assertEquals(
            "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=",
            hashPassword(
                challenge = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY=",
                salt = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
                password = "supersecretpassword",
            ),
        )
    }
}
