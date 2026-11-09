package app.mobdroid.engine.srtla

import app.mobdroid.engine.net.LinkType
import app.mobdroid.engine.net.NetworkLink
import app.mobdroid.engine.net.NetworkLinkProvider
import app.mobdroid.engine.srt.SrtTransportListener
import app.mobdroid.engine.srt.getInt
import app.mobdroid.engine.srt.getShort
import app.mobdroid.engine.srt.isSrtDataPacket
import app.mobdroid.engine.srt.putInt
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap

internal class TestLink(
    override val id: String,
    override val type: LinkType,
    private val localAddress: String,
    override val name: String = id,
) : NetworkLink {
    val sockets = CopyOnWriteArrayList<DatagramSocket>()

    override fun createDatagramSocket(): DatagramSocket =
        DatagramSocket(0, InetAddress.getByName(localAddress)).also { sockets.add(it) }

    val openSockets: Int get() = sockets.count { !it.isClosed }

    fun currentAddress(): SocketAddress = sockets.last { !it.isClosed }.localSocketAddress
}

internal class TestLinkProvider(vararg initial: NetworkLink) : NetworkLinkProvider {
    override val links = MutableStateFlow<List<NetworkLink>>(initial.toList())

    fun set(vararg current: NetworkLink) {
        links.value = current.toList()
    }
}

internal class CollectingListener : SrtTransportListener {
    val ready = CountDownLatch(1)
    val packets = CopyOnWriteArrayList<ByteArray>()
    val errors = CopyOnWriteArrayList<String>()

    override fun onReady() {
        ready.countDown()
    }

    override fun onPacket(buffer: ByteArray, length: Int) {
        packets.add(buffer.copyOf(length))
    }

    override fun onError(reason: String) {
        errors.add(reason)
    }
}

internal class FakeSrtlaServer : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
    private val serverHalf = ByteArray(128).also { SecureRandom().nextBytes(it) }
    private val recentSequences = ConcurrentHashMap<SocketAddress, MutableList<Int>>()
    val address = InetSocketAddress("127.0.0.1", socket.localPort)
    val registered: MutableSet<SocketAddress> = ConcurrentHashMap.newKeySet()
    val dataCounts = ConcurrentHashMap<SocketAddress, AtomicInteger>()
    val keepAlives = ConcurrentHashMap<SocketAddress, AtomicInteger>()
    val controlPackets = CopyOnWriteArrayList<ByteArray>()
    val events = CopyOnWriteArrayList<String>()
    @Volatile var silent = false
    @Volatile var autoAck = false
    @Volatile var echoDelayMs = 0L
    @Volatile private var group: ByteArray? = null
    private val thread = Thread({ loop() }, "fake-srtla-server").also {
        it.isDaemon = true
        it.start()
    }

    fun dataCount(link: SocketAddress): Int = dataCounts[link]?.get() ?: 0

    fun sendTo(target: SocketAddress, bytes: ByteArray) {
        socket.send(DatagramPacket(bytes, bytes.size, target))
    }

    fun forgetGroup() {
        group = null
        registered.clear()
    }

    override fun close() {
        socket.close()
        thread.join(1000)
    }

    private fun loop() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!socket.isClosed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            handle(packet.socketAddress, buffer.copyOf(packet.length))
        }
    }

    private fun handle(from: SocketAddress, data: ByteArray) {
        if (silent || data.size < 2) {
            return
        }
        val type = data.getShort(0)
        when {
            isSrtDataPacket(data, data.size) -> onData(from, data)
            type == SRTLA_REG1 && data.size == SRTLA_REGISTRATION_SIZE -> {
                events.add("REG1")
                val id = ByteArray(SRTLA_ID_LENGTH)
                System.arraycopy(data, SRTLA_TYPE_SIZE, id, 0, SRTLA_ID_LENGTH / 2)
                System.arraycopy(serverHalf, 0, id, SRTLA_ID_LENGTH / 2, SRTLA_ID_LENGTH / 2)
                group = id
                sendTo(from, createSrtlaRegistrationPacket(SRTLA_REG2, id))
            }
            type == SRTLA_REG2 && data.size == SRTLA_REGISTRATION_SIZE -> {
                val known = group
                if (known != null && data.copyOfRange(SRTLA_TYPE_SIZE, data.size).contentEquals(known)) {
                    events.add("REG2")
                    registered.add(from)
                    sendTo(from, byteArrayOf(0x92.toByte(), 0x02))
                } else {
                    events.add("PROBE")
                    sendTo(from, byteArrayOf(0x92.toByte(), 0x11))
                }
            }
            type == SRTLA_KEEPALIVE -> if (from in registered) {
                keepAlives.getOrPut(from) { AtomicInteger() }.incrementAndGet()
                if (echoDelayMs > 0) {
                    Thread.sleep(echoDelayMs)
                }
                sendTo(from, data)
            }
            from in registered -> controlPackets.add(data)
        }
    }

    private fun onData(from: SocketAddress, data: ByteArray) {
        if (from !in registered) {
            return
        }
        dataCounts.getOrPut(from) { AtomicInteger() }.incrementAndGet()
        if (!autoAck) {
            return
        }
        val recent = recentSequences.getOrPut(from) { ArrayList() }
        recent.add(data.getInt(0))
        if (recent.size == 10) {
            val ack = ByteArray(44)
            ack.putInt(0, SRTLA_ACK shl 16)
            recent.forEachIndexed { index, sequence -> ack.putInt(4 + 4 * index, sequence) }
            recent.clear()
            sendTo(from, ack)
        }
    }
}
