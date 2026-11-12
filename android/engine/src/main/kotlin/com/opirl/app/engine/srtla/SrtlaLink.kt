package com.opirl.app.engine.srtla

import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.srt.SrtEngine
import com.opirl.app.engine.srt.SRT_HEADER_SIZE
import com.opirl.app.engine.srt.SRT_TYPE_ACK
import com.opirl.app.engine.srt.SRT_TYPE_NAK
import com.opirl.app.engine.srt.getInt
import com.opirl.app.engine.srt.getLong
import com.opirl.app.engine.srt.getShort
import com.opirl.app.engine.srt.isSrtDataPacket
import com.opirl.app.engine.srt.processSrtNak
import com.opirl.app.engine.srt.putLong
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.security.SecureRandom

private const val NANOS_PER_MS = 1_000_000L
private const val CONNECT_TIMEOUT_NANOS = 5_000 * NANOS_PER_MS
private const val SILENCE_TIMEOUT_NANOS = 5_000 * NANOS_PER_MS
private const val FAILED_RETRY_NANOS = 1_000 * NANOS_PER_MS
private const val MAXIMUM_RTT_MS = 10_000
private const val SRTLA_MAXIMUM_NAK_RANGE = 100_000

private val secureRandom: SecureRandom by lazy(LazyThreadSafetyMode.PUBLICATION) { SecureRandom() }

internal interface SrtlaLinkHost {
    val groupId: ByteArray?
    val isProbing: Boolean

    fun onLinkReady(link: SrtlaLink, now: Long)

    fun onLinkNoGroup(link: SrtlaLink, now: Long)

    fun onLinkGroupId(link: SrtlaLink, groupId: ByteArray, now: Long)

    fun onLinkRegistered(link: SrtlaLink, now: Long)

    fun onLinkSrtPacket(buffer: ByteArray, length: Int)

    fun onLinkSrtAck(sequenceNumber: Int)

    fun onLinkSrtNak(sequenceNumber: Int)

    fun onLinkSrtlaAck(sequenceNumber: Int)
}

internal class SrtlaLink(
    val networkLink: NetworkLink,
    private val server: InetSocketAddress,
    private val engine: SrtEngine,
    private val host: SrtlaLinkHost,
    private val nanoTime: () -> Long,
    private val createdAt: Long,
) {
    val id: String get() = networkLink.id
    val window = SrtlaLinkWindow()
    var priority = 1f
    var state = SrtlaLinkState.Failed
        private set
    var rttMs = 0
        private set
    var bytesSent = 0L
        private set

    @Volatile private var socket: DatagramSocket? = null
    private var reader: Thread? = null
    private val outgoing = DatagramPacket(ByteArray(0), 0)
    private val keepAlive = createSrtlaKeepAlivePacket(0)
    private var registrationId: ByteArray? = null
    private var openedAt = 0L
    private var failedAt = 0L
    private var latestReceivedAt = 0L

    val isEnabled: Boolean get() = priority > 0

    fun score(): Int = when {
        state != SrtlaLinkState.Registered -> -1
        priority == 0f -> -1
        else -> window.score(priority)
    }

    fun start(now: Long) {
        open(now)
    }

    fun stop() {
        closeSocket()
        state = SrtlaLinkState.Failed
    }

    fun probe(now: Long) {
        val id = newRegistrationId()
        registrationId = id
        rawSend(createSrtlaRegistrationPacket(SRTLA_REG2, id), SRTLA_REGISTRATION_SIZE, now)
    }

    fun createGroup(now: Long) {
        val id = newRegistrationId()
        registrationId = id
        rawSend(createSrtlaRegistrationPacket(SRTLA_REG1, id), SRTLA_REGISTRATION_SIZE, now)
    }

    fun register(groupId: ByteArray, now: Long) {
        if (state != SrtlaLinkState.Connecting && state != SrtlaLinkState.Registering) {
            return
        }
        state = SrtlaLinkState.Registering
        rawSend(createSrtlaRegistrationPacket(SRTLA_REG2, groupId), SRTLA_REGISTRATION_SIZE, now)
    }

    fun sendSrt(packet: ByteArray, length: Int, now: Long): Boolean {
        if (!rawSend(packet, length, now)) {
            return false
        }
        if (isSrtDataPacket(packet, length)) {
            window.onSend(packet.getInt(0))
            bytesSent += length
        }
        return true
    }

    fun housekeeping(now: Long) {
        when (state) {
            SrtlaLinkState.Failed -> if (now - failedAt >= FAILED_RETRY_NANOS) {
                open(now)
            }
            SrtlaLinkState.Connecting -> {
                if (now - openedAt >= CONNECT_TIMEOUT_NANOS) {
                    open(now)
                } else if (host.isProbing) {
                    probe(now)
                }
            }
            SrtlaLinkState.Registering -> {
                if (now - openedAt >= CONNECT_TIMEOUT_NANOS) {
                    open(now)
                } else {
                    host.groupId?.let { register(it, now) }
                }
            }
            SrtlaLinkState.Registered -> {
                sendKeepAlive(now)
                if (now - latestReceivedAt > SILENCE_TIMEOUT_NANOS) {
                    open(now)
                }
            }
        }
    }

    fun onPacket(buffer: ByteArray, length: Int, now: Long) {
        if (length < SRTLA_TYPE_SIZE) {
            return
        }
        latestReceivedAt = now
        if (isSrtDataPacket(buffer, length)) {
            host.onLinkSrtPacket(buffer, length)
            return
        }
        val type = buffer.getShort(0)
        when (type) {
            SRTLA_KEEPALIVE -> handleKeepAlive(buffer, length, now)
            SRTLA_ACK -> handleAck(buffer, length)
            SRTLA_REG2 -> handleReg2(buffer, length, now)
            SRTLA_REG3 -> handleReg3(now)
            SRTLA_REG_NGP -> host.onLinkNoGroup(this, now)
            SRTLA_REG1, SRTLA_REG_ERR, SRTLA_REG_NAK -> {}
            else -> {
                handleSrtControl(type and 0x7FFF, buffer, length)
                host.onLinkSrtPacket(buffer, length)
            }
        }
    }

    fun stats(): SrtlaLinkStats = SrtlaLinkStats(
        id = networkLink.id,
        name = networkLink.name,
        type = networkLink.type,
        state = state,
        enabled = isEnabled,
        priority = priority,
        bytesSent = bytesSent,
        rttMs = rttMs,
        packetsInFlight = window.packetsInFlight,
        windowPackets = window.windowSize / WINDOW_MULTIPLY.toFloat(),
    )

    private fun open(now: Long) {
        closeSocket()
        val created = try {
            networkLink.createDatagramSocket().also {
                try {
                    it.connect(networkLink.destination ?: server)
                } catch (error: Exception) {
                    it.close()
                    throw error
                }
            }
        } catch (_: Exception) {
            fail(now)
            return
        }
        socket = created
        openedAt = now
        latestReceivedAt = now
        window.reset()
        state = SrtlaLinkState.Connecting
        reader = Thread({ readLoop(created) }, "srtla-${networkLink.name}").also {
            it.isDaemon = true
            it.start()
        }
        host.onLinkReady(this, now)
    }

    private fun closeSocket() {
        val current = socket ?: return
        socket = null
        current.close()
        reader?.let {
            if (Thread.currentThread() !== it) {
                it.join(500)
            }
        }
        reader = null
    }

    private fun fail(now: Long) {
        closeSocket()
        state = SrtlaLinkState.Failed
        failedAt = now
    }

    private fun readLoop(opened: DatagramSocket) {
        val buffer = ByteArray(2048)
        val incoming = DatagramPacket(buffer, buffer.size)
        while (!opened.isClosed) {
            try {
                incoming.setData(buffer, 0, buffer.size)
                opened.receive(incoming)
            } catch (_: PortUnreachableException) {
                continue
            } catch (_: IOException) {
                if (opened.isClosed) {
                    return
                }
                Thread.sleep(50)
                continue
            }
            val copy = buffer.copyOf(incoming.length)
            engine.execute {
                if (socket === opened) {
                    onPacket(copy, copy.size, nanoTime())
                }
            }
        }
    }

    private fun rawSend(buffer: ByteArray, length: Int, now: Long): Boolean {
        val current = socket ?: return false
        outgoing.setData(buffer, 0, length)
        return try {
            current.send(outgoing)
            true
        } catch (_: PortUnreachableException) {
            false
        } catch (_: IOException) {
            fail(now)
            false
        }
    }

    private fun sendKeepAlive(now: Long) {
        keepAlive.putLong(SRTLA_TYPE_SIZE, keepAliveTimeMs(now))
        rawSend(keepAlive, keepAlive.size, now)
    }

    private fun keepAliveTimeMs(now: Long): Long = (now - createdAt) / NANOS_PER_MS

    private fun newRegistrationId(): ByteArray = ByteArray(SRTLA_ID_LENGTH).also(secureRandom::nextBytes)

    private fun handleKeepAlive(buffer: ByteArray, length: Int, now: Long) {
        if (length < SRTLA_KEEPALIVE_SIZE) {
            return
        }
        val sentAt = buffer.getLong(SRTLA_TYPE_SIZE)
        rttMs = (keepAliveTimeMs(now) - sentAt).coerceIn(0L, MAXIMUM_RTT_MS.toLong()).toInt()
    }

    private fun handleAck(buffer: ByteArray, length: Int) {
        if (length % 4 != 0) {
            return
        }
        var offset = 4
        while (offset < length) {
            host.onLinkSrtlaAck(buffer.getInt(offset))
            offset += 4
        }
    }

    private fun handleReg2(buffer: ByteArray, length: Int, now: Long) {
        if (length != SRTLA_REGISTRATION_SIZE) {
            return
        }
        val id = registrationId ?: return
        for (index in 0 until SRTLA_ID_LENGTH / 2) {
            if (buffer[SRTLA_TYPE_SIZE + index] != id[index]) {
                return
            }
        }
        host.onLinkGroupId(this, buffer.copyOfRange(SRTLA_TYPE_SIZE, length), now)
    }

    private fun handleReg3(now: Long) {
        if (state != SrtlaLinkState.Registering) {
            return
        }
        state = SrtlaLinkState.Registered
        host.onLinkRegistered(this, now)
    }

    private fun handleSrtControl(type: Int, buffer: ByteArray, length: Int) {
        if (length < SRT_HEADER_SIZE) {
            return
        }
        when (type) {
            SRT_TYPE_ACK -> if (length >= SRT_HEADER_SIZE + 4) {
                host.onLinkSrtAck(buffer.getInt(SRT_HEADER_SIZE))
            }
            SRT_TYPE_NAK -> processSrtNak(buffer, length, SRTLA_MAXIMUM_NAK_RANGE) {
                host.onLinkSrtNak(it)
            }
        }
    }
}
