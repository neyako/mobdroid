package com.opirl.app.engine.rist

import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.NetworkLink
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException

internal enum class RistPeerState { Connecting, Connected, Disconnected }

private val NO_BYTES = ByteArray(0)

internal class RistPeer(
    val id: String,
    val name: String,
    val type: LinkType,
    val link: NetworkLink?,
    val sourcePort: Int,
    remote: InetSocketAddress,
    simple: Boolean,
    val dataSocket: DatagramSocket,
    val controlSocket: DatagramSocket,
    val createdAt: Long,
    private val onReceive: (RistPeer, ByteArray, Int, Long) -> Unit,
) {
    private val controlRemote = if (simple) InetSocketAddress(remote.address, remote.port + 1) else remote
    private val dataPacket = DatagramPacket(NO_BYTES, 0, remote)
    private val controlPacket = DatagramPacket(NO_BYTES, 0, controlRemote)
    private var reader: Thread? = null
    private var greSequence = 0

    var state = RistPeerState.Connecting
    var lastReceivedAt = createdAt
    var greVersion = 1
    var weight = 1
    val linkWeight = RistLinkWeight()
    var rttMs = 0.0
    var hasRtt = false
    var lastRttNanos = 0L
    var usable = false
    var reportedLoss = 0.0
    var packets = 0
    var octets = 0

    @Volatile var bytesSent = 0L

    @Volatile var closed = false

    fun nextGreSequence(): Int = greSequence++

    fun start() {
        reader = Thread({ readLoop() }, "rist-udp-reader").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun sendData(buffer: ByteArray, offset: Int, length: Int): Int = send(dataSocket, dataPacket, buffer, offset, length)

    fun sendControl(buffer: ByteArray, offset: Int, length: Int): Int =
        send(controlSocket, controlPacket, buffer, offset, length)

    fun updateRtt(sampleNanos: Long) {
        lastRttNanos = sampleNanos
        val sampleMs = sampleNanos / 1e6
        rttMs = if (hasRtt) rttMs + (sampleMs - rttMs) / 8 else sampleMs
        hasRtt = true
    }

    fun close() {
        if (closed) {
            return
        }
        closed = true
        dataSocket.close()
        controlSocket.close()
        reader?.let {
            if (Thread.currentThread() !== it) {
                try {
                    it.join(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        reader = null
    }

    private fun send(socket: DatagramSocket, packet: DatagramPacket, buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) {
            return 0
        }
        packet.setData(buffer, offset, length)
        return try {
            socket.send(packet)
            val wireBytes = length + RIST_IP_UDP_OVERHEAD.toInt()
            bytesSent += wireBytes
            wireBytes
        } catch (_: IOException) {
            0
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(2048)
        val incoming = DatagramPacket(buffer, buffer.size)
        while (!controlSocket.isClosed) {
            try {
                incoming.setData(buffer, 0, buffer.size)
                controlSocket.receive(incoming)
            } catch (_: PortUnreachableException) {
                continue
            } catch (_: IOException) {
                if (controlSocket.isClosed) {
                    return
                }
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return
                }
                continue
            }
            if (incoming.port == controlRemote.port && incoming.address == controlRemote.address) {
                onReceive(this, buffer.copyOf(incoming.length), incoming.length, System.nanoTime())
            }
        }
    }
}
