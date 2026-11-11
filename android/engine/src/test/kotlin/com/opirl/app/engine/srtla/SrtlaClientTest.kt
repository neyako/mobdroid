package com.opirl.app.engine.srtla

import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.srt.SRT_TYPE_ACK
import com.opirl.app.engine.srt.SRT_TYPE_NAK
import com.opirl.app.engine.srt.SrtEngine
import com.opirl.app.engine.srt.await
import com.opirl.app.engine.srt.call
import com.opirl.app.engine.srt.ackPacket
import com.opirl.app.engine.srt.controlType
import com.opirl.app.engine.srt.ms
import com.opirl.app.engine.srt.nakPacket
import com.opirl.app.engine.srt.putInt
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Fixture(
    val provider: TestLinkProvider,
    priorities: SrtlaPriorities = SrtlaPriorities(),
    connectTimeoutMs: Long = 6000,
    nanoTime: () -> Long = System::nanoTime,
    housekeeping: Boolean = true,
) : AutoCloseable {
    val server = FakeSrtlaServer()
    val failures = CopyOnWriteArrayList<Throwable>()
    val engine = SrtEngine("test-srtla-engine") { failures.add(it) }
    val listener = CollectingListener()
    val client = SrtlaClient(engine, server.address, provider, priorities, nanoTime, connectTimeoutMs, housekeeping)

    fun start() {
        client.listener = listener
        engine.execute { client.start() }
    }

    fun startAndWaitUntilRegistered(count: Int) {
        start()
        assertTrue(listener.ready.await(5, TimeUnit.SECONDS), "SRTLA transport becomes ready")
        await(what = "$count registered links") { stats().count { it.state == SrtlaLinkState.Registered } == count }
    }

    fun stats(): List<SrtlaLinkStats> {
        engine.call { client.refreshStats() }
        return client.linkStats()
    }

    fun statsOf(id: String): SrtlaLinkStats = stats().first { it.id == id }

    fun send(vararg sequenceNumbers: Int) {
        engine.call {
            for (sequenceNumber in sequenceNumbers) {
                val packet = ByteArray(1332)
                packet.putInt(0, sequenceNumber)
                client.send(packet, packet.size)
            }
        }
    }

    override fun close() {
        engine.call { client.close() }
        engine.shutdown()
        engine.awaitTermination(2000)
        server.close()
        assertTrue(failures.isEmpty(), "engine failures: $failures")
    }
}

private fun cell(address: String = "127.0.0.2") = TestLink("cell", LinkType.CELLULAR, address)

private fun wifi(address: String = "127.0.0.3") = TestLink("wifi", LinkType.WIFI, address)

private fun srtlaAck(vararg sequenceNumbers: Int): ByteArray {
    val packet = ByteArray(4 + 4 * sequenceNumbers.size)
    packet.putInt(0, SRTLA_ACK shl 16)
    sequenceNumbers.forEachIndexed { index, value -> packet.putInt(4 + 4 * index, value) }
    return packet
}

class SrtlaClientTest {
    @Test
    fun registersEveryLinkAndBecomesReady() {
        Fixture(TestLinkProvider(wifi(), cell())).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            assertEquals(listOf("cell", "wifi"), fixture.stats().map { it.id })
            assertEquals(1, fixture.server.events.count { it == "REG1" })
            assertEquals(2, fixture.server.events.count { it == "REG2" })
            assertTrue(fixture.server.events.count { it == "PROBE" } >= 1)
            assertEquals(2, fixture.server.registered.size)
        }
    }

    @Test
    fun startsWithoutLinksAndRegistersWhenOneAppears() {
        val provider = TestLinkProvider()
        Fixture(provider).use { fixture ->
            fixture.start()
            Thread.sleep(150)
            assertEquals(1L, fixture.listener.ready.count)
            provider.set(cell())
            assertTrue(fixture.listener.ready.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun linkAddedAtRuntimeJoinsTheExistingGroup() {
        val cell = cell()
        val provider = TestLinkProvider(cell)
        Fixture(provider).use { fixture ->
            fixture.startAndWaitUntilRegistered(1)
            provider.set(cell, wifi())
            await(what = "second link registered") {
                fixture.stats().count { it.state == SrtlaLinkState.Registered } == 2
            }
            assertEquals(1, fixture.server.events.count { it == "REG1" })
            assertEquals(2, fixture.server.registered.size)
        }
    }

    @Test
    fun removedLinkReleasesItsSocketAndCarriesNoMoreTraffic() {
        val cell = cell()
        val wifi = wifi()
        val provider = TestLinkProvider(cell, wifi)
        Fixture(provider).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            val wifiAddress = wifi.currentAddress()
            provider.set(wifi)
            await(what = "cell socket closed") { cell.openSockets == 0 }
            await(what = "stats without cell") { fixture.stats().map { it.id } == listOf("wifi") }
            fixture.send(*IntArray(10) { 100 + it })
            await(what = "packets on wifi") { fixture.server.dataCount(wifiAddress) == 10 }
        }
    }

    @Test
    fun linkThatComesBackRegistersAgainWithoutANewGroup() {
        val cell = cell()
        val wifi = wifi()
        val provider = TestLinkProvider(cell, wifi)
        Fixture(provider).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            provider.set(cell)
            await(what = "wifi removed") { wifi.openSockets == 0 }
            provider.set(cell, wifi)
            await(what = "wifi registered again") {
                fixture.stats().count { it.state == SrtlaLinkState.Registered } == 2
            }
            assertEquals(2, wifi.sockets.size)
            assertEquals(1, fixture.server.events.count { it == "REG1" })
        }
    }

    @Test
    fun sameLinkIdWithNewInstanceReplacesTheSocket() {
        val cell = cell()
        val wifi = wifi()
        val provider = TestLinkProvider(cell, wifi)
        Fixture(provider).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            val handedOver = TestLink("wifi", LinkType.WIFI, "127.0.0.4")
            provider.set(cell, handedOver)
            await(what = "old socket closed and new registered") {
                wifi.openSockets == 0 && handedOver.openSockets == 1 &&
                    fixture.server.registered.contains(handedOver.currentAddress())
            }
            assertEquals(1, fixture.server.events.count { it == "REG1" })
            await(what = "both links registered") {
                fixture.stats().count { it.state == SrtlaLinkState.Registered } == 2
            }
        }
    }

    @Test
    fun equalLinksShareTrafficEvenly() {
        val cell = cell()
        val wifi = wifi()
        Fixture(TestLinkProvider(cell, wifi)).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.send(*IntArray(40) { it })
            await(what = "all packets received") {
                fixture.server.dataCount(cell.currentAddress()) + fixture.server.dataCount(wifi.currentAddress()) == 40
            }
            assertEquals(20, fixture.server.dataCount(cell.currentAddress()))
            assertEquals(20, fixture.server.dataCount(wifi.currentAddress()))
            assertEquals(20, fixture.statsOf("cell").packetsInFlight)
        }
    }

    @Test
    fun higherPriorityLinkCarriesProportionallyMoreTraffic() {
        val cell = cell()
        val wifi = wifi()
        val priorities = SrtlaPriorities(
            enabled = true,
            byType = mapOf(LinkType.CELLULAR to LinkPriority(3), LinkType.WIFI to LinkPriority(1)),
        )
        Fixture(TestLinkProvider(cell, wifi), priorities).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.send(*IntArray(40) { it })
            await(what = "all packets received") {
                fixture.server.dataCount(cell.currentAddress()) + fixture.server.dataCount(wifi.currentAddress()) == 40
            }
            val cellular = fixture.server.dataCount(cell.currentAddress())
            val wifiCount = fixture.server.dataCount(wifi.currentAddress())
            assertTrue(cellular in 28..32, "cellular carried $cellular of 40")
            assertEquals(40, cellular + wifiCount)
            assertEquals(3f, fixture.statsOf("cell").priority)
        }
    }

    @Test
    fun disabledLinkNeverCarriesTrafficAndPrioritiesCanChangeLive() {
        val cell = cell()
        val wifi = wifi()
        val disabled = SrtlaPriorities(
            enabled = true,
            byType = mapOf(LinkType.CELLULAR to LinkPriority(1), LinkType.WIFI to LinkPriority(1, false)),
        )
        Fixture(TestLinkProvider(cell, wifi), disabled).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            assertEquals(false, fixture.statsOf("wifi").enabled)
            fixture.send(*IntArray(20) { it })
            await(what = "packets on cell") { fixture.server.dataCount(cell.currentAddress()) == 20 }
            assertEquals(0, fixture.server.dataCount(wifi.currentAddress()))
            fixture.engine.call { fixture.client.setPriorities(SrtlaPriorities()) }
            assertEquals(true, fixture.statsOf("wifi").enabled)
            fixture.send(*IntArray(10) { 100 + it })
            await(what = "wifi carries traffic again") { fixture.server.dataCount(wifi.currentAddress()) > 0 }
        }
    }

    @Test
    fun nakShrinksTheWindowOfTheLinkThatSentThePacketAndAcksGrowIt() {
        val cell = cell()
        val wifi = wifi()
        Fixture(TestLinkProvider(cell, wifi)).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.send(100, 101)
            await(what = "two packets in flight") {
                fixture.statsOf("cell").packetsInFlight == 1 && fixture.statsOf("wifi").packetsInFlight == 1
            }
            fixture.server.sendTo(cell.currentAddress(), nakPacket(100))
            await(what = "window shrinks") { fixture.statsOf("cell").windowPackets == 19.9f }
            assertEquals(20f, fixture.statsOf("wifi").windowPackets)
            assertEquals(0, fixture.statsOf("cell").packetsInFlight)
            fixture.server.sendTo(wifi.currentAddress(), srtlaAck(101))
            await(what = "window grows") { fixture.statsOf("wifi").windowPackets == 20.001f }
            assertEquals(0, fixture.statsOf("wifi").packetsInFlight)
            assertEquals(19.901f, fixture.statsOf("cell").windowPackets)
        }
    }

    @Test
    fun srtAckClearsInFlightOnEveryLinkAndOnlySrtPacketsReachTheSrtLayer() {
        val cell = cell()
        val wifi = wifi()
        Fixture(TestLinkProvider(cell, wifi)).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.send(500, 501, 502, 503)
            await(what = "four packets in flight") { fixture.stats().sumOf { it.packetsInFlight } == 4 }
            fixture.server.sendTo(cell.currentAddress(), srtlaAck(500))
            fixture.server.sendTo(wifi.currentAddress(), byteArrayOf(0x90.toByte(), 0x00, 0, 0, 0, 0, 0, 0, 0, 0))
            fixture.server.sendTo(cell.currentAddress(), ackPacket(1, 504, 10_000))
            await(what = "SRT ACK forwarded") { fixture.listener.packets.any { controlType(it) == SRT_TYPE_ACK } }
            await(what = "in flight cleared") { fixture.stats().sumOf { it.packetsInFlight } == 0 }
            fixture.server.sendTo(wifi.currentAddress(), nakPacket(9999))
            await(what = "NAK forwarded") { fixture.listener.packets.any { controlType(it) == SRT_TYPE_NAK } }
            assertEquals(2, fixture.listener.packets.size)
        }
    }

    @Test
    fun keepAlivesFlowOnEveryRegisteredLinkAndMeasureRtt() {
        val clock = AtomicLong(0)
        val cell = cell()
        Fixture(TestLinkProvider(cell), nanoTime = clock::get, housekeeping = false).use { fixture ->
            fixture.server.echoDelayMs = 150
            fixture.startAndWaitUntilRegistered(1)
            clock.set(ms(1000))
            fixture.engine.call { fixture.client.housekeeping(clock.get()) }
            clock.set(ms(1042))
            await(what = "keepalive echoed") { fixture.statsOf("cell").rttMs == 42 }
            assertEquals(1, fixture.server.keepAlives.values.sumOf { it.get() })
        }
    }

    @Test
    fun silentLinkIsReconnectedWithANewSocketAndRegistersAgain() {
        val clock = AtomicLong(0)
        val cell = cell()
        val wifi = wifi()
        Fixture(TestLinkProvider(cell, wifi), nanoTime = clock::get, housekeeping = false).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.server.silent = true
            clock.set(ms(5001))
            fixture.engine.call { fixture.client.housekeeping(clock.get()) }
            assertEquals(2, cell.sockets.size)
            assertEquals(1, cell.openSockets)
            assertEquals(SrtlaLinkState.Registering, fixture.statsOf("cell").state)
            fixture.server.silent = false
            clock.set(ms(6001))
            fixture.engine.call { fixture.client.housekeeping(clock.get()) }
            await(what = "link registered again") {
                fixture.stats().count { it.state == SrtlaLinkState.Registered } == 2
            }
            assertEquals(1, fixture.server.events.count { it == "REG1" })
        }
    }

    @Test
    fun registrationTimeoutIsReported() {
        Fixture(TestLinkProvider(cell()), connectTimeoutMs = 300).use { fixture ->
            fixture.server.silent = true
            fixture.start()
            await(what = "timeout error") { fixture.listener.errors.isNotEmpty() }
            assertEquals(listOf("SRTLA registration timed out"), fixture.listener.errors)
            assertEquals(1L, fixture.listener.ready.count)
        }
    }

    @Test
    fun closeReleasesSocketsAndReaderThreads() {
        val cell = cell()
        val wifi = wifi()
        Fixture(TestLinkProvider(cell, wifi)).use { fixture ->
            fixture.startAndWaitUntilRegistered(2)
            fixture.engine.call { fixture.client.close() }
            assertEquals(0, cell.openSockets)
            assertEquals(0, wifi.openSockets)
            assertTrue(fixture.stats().isEmpty())
            await(what = "reader threads end") {
                Thread.getAllStackTraces().keys.none { it.isAlive && it.name.startsWith("srtla-") }
            }
        }
    }

    @Test
    fun linksThatCannotOpenASocketAreRetriedLater() {
        var failing = true
        val flaky = object : NetworkLink {
            override val id = "flaky"
            override val name = "flaky"
            override val type = LinkType.CELLULAR

            override fun createDatagramSocket(): DatagramSocket {
                if (failing) {
                    throw SocketException("network is gone")
                }
                return DatagramSocket(0, InetAddress.getByName("127.0.0.2"))
            }
        }
        val clock = AtomicLong(0)
        Fixture(TestLinkProvider(flaky), nanoTime = clock::get, housekeeping = false).use { fixture ->
            fixture.start()
            await(what = "link listed as failed") { fixture.stats().singleOrNull()?.state == SrtlaLinkState.Failed }
            failing = false
            clock.set(ms(1000))
            fixture.engine.call { fixture.client.housekeeping(clock.get()) }
            assertTrue(fixture.listener.ready.await(5, TimeUnit.SECONDS))
        }
    }
}
