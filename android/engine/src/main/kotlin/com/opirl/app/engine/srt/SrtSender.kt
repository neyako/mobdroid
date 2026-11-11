package com.opirl.app.engine.srt

import java.util.concurrent.ThreadLocalRandom

internal const val DEFAULT_SRT_LATENCY_MS = 3000
internal const val NO_DEADLINE = Long.MAX_VALUE

private const val NANOS_PER_MS = 1_000_000L
private const val HANDSHAKE_RETRY_NANOS = 250 * NANOS_PER_MS
private const val CONNECT_TIMEOUT_NANOS = 5_000 * NANOS_PER_MS
private const val PEER_IDLE_NANOS = 5_000 * NANOS_PER_MS
private const val KEEPALIVE_NANOS = 1_000 * NANOS_PER_MS
private const val OUTPUT_INTERVAL_NANOS = 2 * NANOS_PER_MS
private const val RATE_INTERVAL_NANOS = 200 * NANOS_PER_MS
private const val MAXIMUM_RETRANSMIT_QUEUE = 1000
private const val MAXIMUM_QUEUED_PACKETS = 16384
private const val POOL_LIMIT = 1024
private const val MAXIMUM_TRANSMISSION_UNIT = 1500
private const val HANDSHAKE_CIF_SIZE = 48
private const val HANDSHAKE_BUFFER_SIZE = 1024
private const val SRT_SOCKET_ID_LIMIT = 0x40000000
private const val INITIAL_SEQUENCE_LIMIT = 10000

internal class SrtConfig(
    val streamId: String? = null,
    val latencyMs: Int = DEFAULT_SRT_LATENCY_MS,
    val enhanced: Boolean = false,
    val payloadSize: Int = SRT_DEFAULT_PAYLOAD_SIZE,
) {
    init {
        require(latencyMs in 0..65535) { "SRT latency must be between 0 and 65535 ms" }
        require(payloadSize in 188..SRT_DEFAULT_PAYLOAD_SIZE && payloadSize % 188 == 0) {
            "SRT payload size must be a multiple of 188 up to $SRT_DEFAULT_PAYLOAD_SIZE"
        }
        require(streamId == null || streamId.toByteArray(Charsets.UTF_8).size <= SRT_MAX_STREAM_ID_BYTES) {
            "SRT stream id is longer than $SRT_MAX_STREAM_ID_BYTES bytes"
        }
    }
}

internal interface SrtSenderListener {
    fun onSrtConnected()

    fun onSrtDisconnected(reason: String)
}

internal fun interface SrtOutput {
    fun send(packet: ByteArray, length: Int)
}

internal class SrtDataPacket(payloadCapacity: Int) {
    val buffer = ByteArray(SRT_HEADER_SIZE + payloadCapacity)
    var payloadLength = 0
    var sequenceNumber = 0
    var createdAt = 0L
    var retransmittedAt = 0L
    var retransmitted = false
    var containsAudio = false
    var nakQueued = false

    val length: Int get() = SRT_HEADER_SIZE + payloadLength

    fun reset() {
        payloadLength = 0
        retransmitted = false
        containsAudio = false
        nakQueued = false
    }
}

internal class SrtCounters(
    val retransmittedPackets: Long,
    val receivedNaks: Long,
    val droppedPackets: Long,
    val packetsInFlight: Int,
    val rttMs: Double,
    val mbpsSendRate: Double,
    val bytesSent: Long,
    val queuedBytes: Long,
    val queuedPackets: Int,
)

private enum class SenderState { Idle, Connecting, Connected, Disconnected }

private enum class HandshakePhase { Induction, Conclusion }

internal class SrtSender(
    private val config: SrtConfig,
    private val output: SrtOutput,
    private val listener: SrtSenderListener,
    private val socketId: Int = ThreadLocalRandom.current().nextInt(1, SRT_SOCKET_ID_LIMIT),
    initialSequenceNumber: Int = ThreadLocalRandom.current().nextInt(0, INITIAL_SEQUENCE_LIMIT),
) {
    private var state = SenderState.Idle
    private var phase = HandshakePhase.Induction
    private var clockStart = 0L
    private var peerSocketId = 0
    private var nextSequenceNumber = initialSequenceNumber and SRT_SEQUENCE_MASK
    private val packetsToSend = ArrayDeque<SrtDataPacket>()
    private val packetsInFlight = ArrayDeque<SrtDataPacket>()
    private val audioRetransmit = IntRing(MAXIMUM_RETRANSMIT_QUEUE + 24)
    private val videoRetransmit = IntRing(MAXIMUM_RETRANSMIT_QUEUE + 24)
    private val pool = ArrayDeque<SrtDataPacket>()
    private var pending: SrtDataPacket? = null
    private val handshake = ByteArray(HANDSHAKE_BUFFER_SIZE)
    private var handshakeLength = 0
    private val ackAck = ByteArray(SRT_HEADER_SIZE + 4)
    private val keepAlive = ByteArray(SRT_HEADER_SIZE)
    private val shutdown = ByteArray(SRT_HEADER_SIZE + 4)
    private val latencyNanos = config.latencyMs * NANOS_PER_MS
    private val inFlightDropNanos = if (config.enhanced) latencyNanos * 2 / 3 else latencyNanos * 3 / 2
    private val toSendDropNanos = if (config.enhanced) latencyNanos * 4 / 5 else latencyNanos * 5 / 4

    private var connectStartedAt = 0L
    private var handshakeSentAt = 0L
    private var latestReceivedAt = 0L
    private var lastSentAt = 0L
    private var latestOutputAt = 0L
    private var latestRateAt = 0L
    private var rateBytes = 0L
    private var rttNanos = 0L

    @Volatile private var retransmittedPackets = 0L

    @Volatile private var receivedNaks = 0L

    @Volatile private var droppedPackets = 0L

    @Volatile private var flightSize = 0

    @Volatile private var rttUs = 0L

    @Volatile private var mbpsSendRate = 0.0

    @Volatile private var bytesSent = 0L

    @Volatile private var queuedBytes = 0L

    @Volatile private var queuedPackets = 0

    init {
        writeControlHeader(ackAck, SRT_TYPE_ACKACK)
        writeControlHeader(keepAlive, SRT_TYPE_KEEPALIVE)
        writeControlHeader(shutdown, SRT_TYPE_SHUTDOWN)
    }

    val isConnected: Boolean get() = state == SenderState.Connected

    fun counters(): SrtCounters = SrtCounters(
        retransmittedPackets = retransmittedPackets,
        receivedNaks = receivedNaks,
        droppedPackets = droppedPackets,
        packetsInFlight = flightSize,
        rttMs = rttUs / 1000.0,
        mbpsSendRate = mbpsSendRate,
        bytesSent = bytesSent,
        queuedBytes = queuedBytes,
        queuedPackets = queuedPackets,
    )

    fun start(now: Long) {
        clockStart = now
        connectStartedAt = now
        latestReceivedAt = now
        lastSentAt = now
        latestRateAt = now
        latestOutputAt = now - 2 * OUTPUT_INTERVAL_NANOS
        state = SenderState.Connecting
        phase = HandshakePhase.Induction
        buildInductionHandshake()
        sendHandshake(now)
    }

    fun stop(now: Long) {
        if (state == SenderState.Connected) {
            sendShutdown(now)
        }
        state = SenderState.Disconnected
        releaseAll()
    }

    fun write(buffer: ByteArray, offset: Int, length: Int, containsAudio: Boolean, now: Long) {
        if (state != SenderState.Connected) {
            return
        }
        var position = offset
        var remaining = length
        while (remaining > 0) {
            val packet = pending ?: acquirePacket().also { pending = it }
            val count = minOf(config.payloadSize - packet.payloadLength, remaining)
            System.arraycopy(buffer, position, packet.buffer, SRT_HEADER_SIZE + packet.payloadLength, count)
            packet.payloadLength += count
            position += count
            remaining -= count
            if (containsAudio) {
                packet.containsAudio = true
            }
            if (packet.payloadLength == config.payloadSize) {
                pending = null
                enqueue(packet, now)
            }
        }
    }

    fun flush(now: Long) {
        if (state != SenderState.Connected) {
            return
        }
        pending?.let {
            pending = null
            enqueue(it, now)
        }
        send(now)
    }

    fun input(packet: ByteArray, length: Int, now: Long) {
        latestReceivedAt = now
        if (state == SenderState.Idle || state == SenderState.Disconnected) {
            return
        }
        if (length < SRT_HEADER_SIZE || isSrtDataPacket(packet, length)) {
            return
        }
        when (packet.getShort(0) and 0x7FFF) {
            SRT_TYPE_HANDSHAKE -> handleHandshake(packet, length, now)
            SRT_TYPE_KEEPALIVE -> if (state == SenderState.Connected) handleKeepAlive(now)
            SRT_TYPE_ACK -> if (state == SenderState.Connected) handleAck(packet, length, now)
            SRT_TYPE_NAK -> if (state == SenderState.Connected) handleNak(packet, length)
            SRT_TYPE_SHUTDOWN -> if (state == SenderState.Connected) {
                disconnect("SRT server closed the connection", false, now)
            }
            else -> return
        }
        if (state == SenderState.Connected) {
            outputPackets(now)
        }
    }

    fun onTimer(now: Long) {
        when (state) {
            SenderState.Connecting -> {
                if (now - connectStartedAt >= CONNECT_TIMEOUT_NANOS) {
                    disconnect("SRT handshake timed out", false, now)
                } else if (now - handshakeSentAt >= HANDSHAKE_RETRY_NANOS) {
                    sendHandshake(now)
                }
            }
            SenderState.Connected -> {
                send(now)
                if (state == SenderState.Connected && now - lastSentAt >= KEEPALIVE_NANOS) {
                    sendKeepAlive(now)
                }
            }
            else -> {}
        }
    }

    fun nextDeadline(): Long = when (state) {
        SenderState.Connecting ->
            minOf(connectStartedAt + CONNECT_TIMEOUT_NANOS, handshakeSentAt + HANDSHAKE_RETRY_NANOS)
        SenderState.Connected -> {
            var deadline = minOf(latestReceivedAt + PEER_IDLE_NANOS + 1, lastSentAt + KEEPALIVE_NANOS)
            if (packetsToSend.isNotEmpty() || audioRetransmit.size + videoRetransmit.size > 0) {
                deadline = minOf(deadline, latestOutputAt + OUTPUT_INTERVAL_NANOS)
            }
            packetsInFlight.firstOrNull()?.let {
                deadline = minOf(deadline, it.createdAt + inFlightDropNanos + 1)
            }
            packetsToSend.firstOrNull()?.let {
                deadline = minOf(deadline, it.createdAt + toSendDropNanos + 1)
            }
            deadline
        }
        else -> NO_DEADLINE
    }

    private fun send(now: Long) {
        outputPackets(now)
        dropOldPackets(now)
        checkPeerIdle(now)
    }

    private fun timestamp(now: Long): Int = ((now - clockStart) / 1000).toInt()

    private fun acquirePacket(): SrtDataPacket =
        pool.removeLastOrNull() ?: SrtDataPacket(config.payloadSize)

    private fun recycle(packet: SrtDataPacket) {
        packet.reset()
        if (pool.size < POOL_LIMIT) {
            pool.addLast(packet)
        }
    }

    private fun releaseAll() {
        packetsToSend.clear()
        packetsInFlight.clear()
        audioRetransmit.clear()
        videoRetransmit.clear()
        pool.clear()
        pending = null
        queuedBytes = 0
        queuedPackets = 0
        flightSize = 0
    }

    private fun enqueue(packet: SrtDataPacket, now: Long) {
        if (packetsToSend.size >= MAXIMUM_QUEUED_PACKETS) {
            dropOldestToSend()
        }
        val sequenceNumber = nextSequenceNumber
        nextSequenceNumber = nextSrtSn(sequenceNumber)
        packet.sequenceNumber = sequenceNumber
        packet.createdAt = now
        if (!config.enhanced) {
            packet.containsAudio = true
        }
        val buffer = packet.buffer
        buffer.putInt(0, sequenceNumber)
        buffer.putInt(4, 0xE000_0001.toInt())
        buffer.putInt(8, timestamp(now))
        buffer.putInt(12, peerSocketId)
        packetsToSend.addLast(packet)
        queuedBytes += packet.length
        queuedPackets = packetsToSend.size
    }

    private fun dropOldestToSend() {
        val packet = packetsToSend.removeFirst()
        queuedBytes -= packet.length
        queuedPackets = packetsToSend.size
        droppedPackets += 1
        recycle(packet)
    }

    private fun dropOldPackets(now: Long) {
        while (true) {
            val packet = packetsInFlight.firstOrNull() ?: break
            if (now - packet.createdAt <= inFlightDropNanos) {
                break
            }
            packetsInFlight.removeFirst()
            droppedPackets += 1
            recycle(packet)
        }
        flightSize = packetsInFlight.size
        while (true) {
            val packet = packetsToSend.firstOrNull() ?: break
            if (now - packet.createdAt <= toSendDropNanos) {
                break
            }
            dropOldestToSend()
        }
    }

    private fun checkPeerIdle(now: Long) {
        if (now - latestReceivedAt > PEER_IDLE_NANOS) {
            disconnect("SRT server stopped responding", true, now)
        }
    }

    private fun disconnect(reason: String, notifyPeer: Boolean, now: Long) {
        if (state == SenderState.Disconnected) {
            return
        }
        if (notifyPeer && state == SenderState.Connected) {
            sendShutdown(now)
        }
        state = SenderState.Disconnected
        releaseAll()
        listener.onSrtDisconnected(reason)
    }

    private fun outputPackets(now: Long) {
        if (now - latestOutputAt < OUTPUT_INTERVAL_NANOS) {
            return
        }
        latestOutputAt = now
        var count = audioRetransmit.size + videoRetransmit.size + packetsToSend.size
        count = maxOf(count / 10, minOf(count, 10))
        var retransmitted = 0
        for (index in 0 until count) {
            if (!config.enhanced || retransmitted < (count + 1) / 2) {
                if (retransmitIfNeeded(audioRetransmit, now) || retransmitIfNeeded(videoRetransmit, now)) {
                    retransmitted += 1
                    continue
                }
            }
            val packet = packetsToSend.removeFirstOrNull() ?: break
            queuedBytes -= packet.length
            queuedPackets = packetsToSend.size
            outputRaw(packet.buffer, packet.length, now)
            if (packetsInFlight.size >= MAXIMUM_QUEUED_PACKETS) {
                recycle(packetsInFlight.removeFirst())
                droppedPackets += 1
            }
            packetsInFlight.addLast(packet)
        }
        flightSize = packetsInFlight.size
    }

    private fun retransmitIfNeeded(queue: IntRing, now: Long): Boolean {
        while (queue.size > 0) {
            val packet = findInFlight(queue.removeFirst()) ?: continue
            packet.nakQueued = false
            if (packet.retransmitted && now - packet.retransmittedAt < rttNanos) {
                continue
            }
            packet.retransmitted = true
            packet.retransmittedAt = now
            packet.buffer[4] = (packet.buffer[4].toInt() or 0x04).toByte()
            retransmittedPackets += 1
            outputRaw(packet.buffer, packet.length, now)
            return true
        }
        return false
    }

    private fun outputRaw(buffer: ByteArray, length: Int, now: Long) {
        output.send(buffer, length)
        val wireBytes = length + SRT_IP_UDP_HEADER_SIZE
        bytesSent += wireBytes
        rateBytes += wireBytes
        lastSentAt = now
    }

    private fun findInFlight(sequenceNumber: Int): SrtDataPacket? {
        var low = 0
        var high = packetsInFlight.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val packet = packetsInFlight[middle]
            val order = compareSrtSn(packet.sequenceNumber, sequenceNumber)
            when {
                order == 0 -> return packet
                order < 0 -> low = middle + 1
                else -> high = middle - 1
            }
        }
        return null
    }

    private fun writeControlHeader(buffer: ByteArray, type: Int) {
        buffer.putShort(0, 0x8000 or type)
    }

    private fun buildHandshakeHeader(now: Long, ack: Int) {
        writeControlHeader(handshake, SRT_TYPE_HANDSHAKE)
        handshake.putShort(2, 0)
        handshake.putInt(4, ack)
        handshake.putInt(8, timestamp(now))
        handshake.putInt(12, 0)
    }

    private fun buildHandshakeCif(version: Int, extensionField: Int, type: Int, peerSocket: Int, cookie: Int) {
        var offset = SRT_HEADER_SIZE
        handshake.putInt(offset, version)
        handshake.putShort(offset + 4, 0)
        handshake.putShort(offset + 6, extensionField)
        handshake.putInt(offset + 8, nextSequenceNumber)
        handshake.putInt(offset + 12, MAXIMUM_TRANSMISSION_UNIT)
        handshake.putInt(offset + 16, SRT_MAX_FLOW_WINDOW_PACKETS)
        handshake.putInt(offset + 20, type)
        handshake.putInt(offset + 24, peerSocket)
        handshake.putInt(offset + 28, cookie)
        handshake.fill(0, offset + 32, offset + HANDSHAKE_CIF_SIZE)
        handshake[offset + 32] = 1
        handshake[offset + 35] = 127
        offset += HANDSHAKE_CIF_SIZE
        handshakeLength = offset
    }

    private fun buildInductionHandshake() {
        buildHandshakeHeader(clockStart, 0)
        buildHandshakeCif(4, 2, SRT_HANDSHAKE_INDUCTION, socketId, 0)
    }

    private fun buildConclusionHandshake(peerSocket: Int, cookie: Int) {
        val streamId = config.streamId?.takeIf { it.isNotEmpty() }
        buildHandshakeHeader(clockStart, 0)
        buildHandshakeCif(5, if (streamId != null) 5 else 1, SRT_HANDSHAKE_CONCLUSION, peerSocket, cookie)
        var offset = handshakeLength
        handshake.putShort(offset, 1)
        handshake.putShort(offset + 2, 3)
        handshake.putInt(offset + 4, 0x0001_0503)
        handshake.putInt(offset + 8, 0xBF)
        handshake.putShort(offset + 12, config.latencyMs)
        handshake.putShort(offset + 14, config.latencyMs)
        offset += 16
        if (streamId != null) {
            val bytes = streamId.toByteArray(Charsets.UTF_8)
            val words = (bytes.size + 3) / 4
            handshake.putShort(offset, 5)
            handshake.putShort(offset + 2, words)
            offset += 4
            for (word in 0 until words) {
                for (index in 0 until 4) {
                    val source = word * 4 + 3 - index
                    handshake[offset + word * 4 + index] = if (source < bytes.size) bytes[source] else 0
                }
            }
            offset += words * 4
        }
        handshakeLength = offset
    }

    private fun sendHandshake(now: Long) {
        handshake.putInt(8, timestamp(now))
        handshakeSentAt = now
        outputRaw(handshake, handshakeLength, now)
    }

    private fun sendKeepAlive(now: Long) {
        keepAlive.putInt(8, timestamp(now))
        keepAlive.putInt(12, peerSocketId)
        outputRaw(keepAlive, keepAlive.size, now)
    }

    private fun sendShutdown(now: Long) {
        shutdown.putInt(8, timestamp(now))
        shutdown.putInt(12, peerSocketId)
        outputRaw(shutdown, shutdown.size, now)
    }

    private fun handleHandshake(packet: ByteArray, length: Int, now: Long) {
        if (length < SRT_HEADER_SIZE + HANDSHAKE_CIF_SIZE || state != SenderState.Connecting) {
            return
        }
        val type = packet.getInt(SRT_HEADER_SIZE + 20)
        val peerSocket = packet.getInt(SRT_HEADER_SIZE + 24)
        val cookie = packet.getInt(SRT_HEADER_SIZE + 28)
        when {
            type == SRT_HANDSHAKE_INDUCTION && phase == HandshakePhase.Induction -> {
                buildConclusionHandshake(peerSocket, cookie)
                phase = HandshakePhase.Conclusion
                sendHandshake(now)
            }
            type == SRT_HANDSHAKE_CONCLUSION && phase == HandshakePhase.Conclusion -> {
                peerSocketId = peerSocket
                state = SenderState.Connected
                latestReceivedAt = now
                lastSentAt = now
                latestRateAt = now
                rateBytes = 0
                listener.onSrtConnected()
            }
            type >= SRT_HANDSHAKE_REJECT_MINIMUM -> {
                disconnect("SRT server rejected the connection (reason $type)", false, now)
            }
        }
    }

    private fun handleKeepAlive(now: Long) {
        sendKeepAlive(now)
    }

    private fun handleAck(packet: ByteArray, length: Int, now: Long) {
        if (length < SRT_HEADER_SIZE + 4) {
            return
        }
        val ackNumber = packet.getInt(4)
        removeAckedPackets(packet.getInt(SRT_HEADER_SIZE) and SRT_SEQUENCE_MASK)
        if (ackNumber != 0 && length >= SRT_HEADER_SIZE + 8) {
            val rtt = packet.getInt(SRT_HEADER_SIZE + 4).toLong() and 0xFFFFFFFFL
            rttNanos = rtt * 1000
            rttUs = rtt
            updateSendRate(now)
            ackAck.putInt(4, ackNumber)
            ackAck.putInt(8, timestamp(now))
            ackAck.putInt(12, peerSocketId)
            outputRaw(ackAck, ackAck.size, now)
        }
    }

    private fun updateSendRate(now: Long) {
        val duration = now - latestRateAt
        if (duration <= RATE_INTERVAL_NANOS) {
            return
        }
        latestRateAt = now
        val latest = 8.0 * rateBytes / (duration / 1e9) / 1_000_000
        rateBytes = 0
        mbpsSendRate = 0.7 * mbpsSendRate + 0.3 * latest
    }

    private fun removeAckedPackets(ackSn: Int) {
        while (true) {
            val packet = packetsInFlight.firstOrNull() ?: break
            if (!isSrtSnAcked(packet.sequenceNumber, ackSn)) {
                break
            }
            packetsInFlight.removeFirst()
            recycle(packet)
        }
        flightSize = packetsInFlight.size
    }

    private fun handleNak(packet: ByteArray, length: Int) {
        processSrtNak(packet, length, SRT_MAX_FLOW_WINDOW_PACKETS) { sequenceNumber ->
            if (audioRetransmit.size + videoRetransmit.size < MAXIMUM_RETRANSMIT_QUEUE) {
                queueRetransmit(sequenceNumber)
            }
        }
        receivedNaks += 1
    }

    private fun queueRetransmit(sequenceNumber: Int) {
        val packet = findInFlight(sequenceNumber) ?: return
        if (packet.nakQueued) {
            return
        }
        packet.nakQueued = true
        if (packet.containsAudio) {
            audioRetransmit.addLast(sequenceNumber)
        } else {
            videoRetransmit.addLast(sequenceNumber)
        }
    }
}
