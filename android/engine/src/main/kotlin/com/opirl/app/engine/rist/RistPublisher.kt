package com.opirl.app.engine.rist

import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoFormat
import com.opirl.app.engine.net.BondedPublisher
import com.opirl.app.engine.net.LinkType
import com.opirl.app.engine.net.LinkUsage
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.net.NetworkLinkProvider
import com.opirl.app.engine.net.Publisher
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.engine.net.PublisherStats
import com.opirl.app.engine.srt.AudioItem
import com.opirl.app.engine.srt.FrameQueue
import com.opirl.app.engine.srt.SrtEngine
import com.opirl.app.engine.srt.VideoItem
import com.opirl.app.engine.srt.resolveServer
import com.opirl.app.engine.ts.MpegTsMuxer
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MAXIMUM_QUEUED_BYTES = 4L * 1024 * 1024
private const val STOP_TIMEOUT_MS = 1500L
private const val DRAIN_BATCH = 8
private const val TICK_MS = 100L
private const val CONNECT_TIMEOUT_MS = 5000L
private const val STALL_NANOS = 300_000_000L
private const val MAXIMUM_RETRANSMITS = 20
private const val RETRANSMITS_PER_RUN = 256
private const val MINIMUM_NACK_SPACING_NANOS = 5_000_000L
private const val MAXIMUM_NACK_SPACING_NANOS = 500_000_000L
private const val MAXIMUM_ECHO_NANOS = 10_000_000_000L
private const val SOCKET_BUFFER_BYTES = 1 shl 20
private const val DEFAULT_CNAME = "opirl"
private const val DEFAULT_LINK_ID = "default"
private const val NONE = Long.MIN_VALUE
private const val STARTUP_KEEPALIVES = 3
private const val STARTUP_ECHOES = 3
private const val VIRTUAL_PORT_SPAN = 28000

internal class RistEnvironment(
    val muxerFactory: (output: (ByteArray, Int, Int) -> Unit) -> MediaSink = { MpegTsMuxer(it) },
    val resolver: (String, Int) -> InetSocketAddress = ::resolveServer,
    val socketFactory: () -> DatagramSocket = { DatagramSocket() },
    val connectTimeoutMs: Long = CONNECT_TIMEOUT_MS,
)

class RistPublisher internal constructor(
    url: String,
    private val listener: PublisherListener,
    private val links: NetworkLinkProvider?,
    private val environment: RistEnvironment,
) : Publisher, BondedPublisher {
    constructor(url: String, listener: PublisherListener, links: NetworkLinkProvider? = null) :
        this(url, listener, links, RistEnvironment())

    private val target = RistUrl.parse(url)
    private val simple = target.profile == RistProfile.Simple
    private val origin = System.nanoTime()
    private val flowId = ThreadLocalRandom.current().nextInt() and 1.inv()
    private val cname = (target.cname ?: DEFAULT_CNAME).toByteArray(Charsets.UTF_8)
    private val mac = ByteArray(6).also {
        ThreadLocalRandom.current().nextBytes(it)
        it[0] = ((it[0].toInt() and 0xFC) or 0x02).toByte()
    }
    private val livenessNanos = millis(maxOf(target.sessionTimeoutMs, 2 * target.bufferMs).toLong())
    private val connectTimeoutNanos = millis(environment.connectTimeoutMs)
    private val retentionMs = target.bufferMs + 2 * maxOf(5, target.bufferMs / 20)
    private val bandwidthBps = target.bandwidthKbps * 1000L
    private val queue = FrameQueue(MAXIMUM_QUEUED_BYTES)
    private val engine = SrtEngine("rist-publisher") { failure ->
        fail("RIST internal error: ${failure.message ?: failure.javaClass.simpleName}")
    }
    private val started = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)
    private val drainTask = Runnable { drain() }
    private val tickTask = Runnable { tick() }
    private val sockets: MutableSet<DatagramSocket> = ConcurrentHashMap.newKeySet()
    private val peers = ArrayList<RistPeer>(4)
    private val retransmit = RistRetransmitBuffer(millis(retentionMs.toLong()))
    private val retries = RistRetryQueue<RistPeer>()
    private val selector = RistWeightedSelector()
    private val rtcpBuffer = ByteArray(RIST_HEADROOM + 512)
    private var weights = IntArray(4)
    private var usable = BooleanArray(4)
    private var muxer: MediaSink? = null
    private var address: InetSocketAddress? = null
    private var scope: CoroutineScope? = null
    private var timer: ScheduledFuture<*>? = null
    private var desiredLinks: List<NetworkLink> = emptyList()
    private var nextPeerIndex = 0
    private var ticks = 0L
    private var connected = false
    private var idleSince = NONE
    private var rateAt = 0L
    private var rateBytes = 0L
    private var ratePackets = 0
    private var rateRequests = 0
    private var smoothLoss = 0.0
    private var smoothBitrate = 0.0

    @Volatile private var totalBytesSent = 0L

    @Volatile private var retransmitted = 0L

    @Volatile private var sendBitrate = 0L

    @Volatile private var bestRttMs = 0.0

    @Volatile private var lostPercent = 0.0

    @Volatile private var usage: List<LinkUsage> = emptyList()

    override fun start() {
        if (terminated.get() || !started.compareAndSet(false, true)) {
            return
        }
        engine.execute { begin() }
    }

    override fun stop() {
        if (terminated.compareAndSet(false, true)) {
            queue.close()
            when {
                !started.get() -> engine.shutdown()
                engine.isEngineThread() -> teardown()
                else -> engine.execute { teardown() }
            }
        }
        if (!engine.isEngineThread() && !engine.awaitTermination(STOP_TIMEOUT_MS)) {
            sockets.toList().forEach { it.close() }
        }
    }

    override fun stats(): PublisherStats = PublisherStats(
        bytesSent = totalBytesSent,
        sendBitrateBps = sendBitrate,
        rttMs = bestRttMs,
        queuedBytes = queue.queuedBytes,
        packetsInFlight = 0,
        retransmittedPackets = retransmitted,
        droppedVideoFrames = queue.droppedVideoFrames,
        lostPercent = lostPercent,
    )

    override fun linkUsage(): List<LinkUsage> = usage

    override fun setVideoFormat(format: VideoFormat) {
        if (queue.addFormat(format)) {
            engine.execute(drainTask)
        }
    }

    override fun setAudioFormat(format: AudioFormat) {
        if (queue.addFormat(format)) {
            engine.execute(drainTask)
        }
    }

    override fun writeVideo(frame: EncodedFrame) {
        if (queue.addVideo(frame)) {
            engine.execute(drainTask)
        }
    }

    override fun writeAudio(frame: EncodedFrame) {
        if (queue.addAudio(frame)) {
            engine.execute(drainTask)
        }
    }

    private fun begin() {
        if (terminated.get()) {
            return
        }
        val resolved = try {
            environment.resolver(target.host, target.port)
        } catch (_: Exception) {
            fail("Failed to resolve ${target.host}")
            return
        }
        if (terminated.get()) {
            return
        }
        address = resolved
        muxer = environment.muxerFactory { buffer, offset, length -> sendMedia(buffer, offset, length) }
        val now = System.nanoTime()
        idleSince = now
        rateAt = now
        if (links == null) {
            addPeer(null, DEFAULT_LINK_ID, DEFAULT_LINK_ID, LinkType.OTHER, now)
        } else {
            scope = CoroutineScope(SupervisorJob() + engine.dispatcher).also {
                it.launch {
                    links.links.collect { current ->
                        desiredLinks = current
                        syncLinks()
                    }
                }
            }
        }
        timer = engine.scheduleWithFixedDelay(TICK_MS, tickTask)
        engine.execute(drainTask)
    }

    private fun drain() {
        val target = muxer ?: return
        if (terminated.get()) {
            return
        }
        for (count in 0 until DRAIN_BATCH) {
            when (val item = queue.poll() ?: return) {
                is VideoFormat -> target.setVideoFormat(item)
                is AudioFormat -> target.setAudioFormat(item)
                is VideoItem -> target.writeVideo(item.frame)
                is AudioItem -> target.writeAudio(item.frame)
            }
        }
        sendRetransmits(System.nanoTime())
        engine.execute(drainTask)
    }

    private fun sendMedia(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0 || length > RIST_MAXIMUM_PAYLOAD) {
            return
        }
        val index = selector.pick(weights, usable, peers.size)
        if (index < 0) {
            return
        }
        val peer = peers[index]
        val now = System.nanoTime()
        val slot = retransmit.next(now)
        val data = slot.data
        System.arraycopy(buffer, offset, data, RIST_HEADROOM + RIST_RTP_HEADER_SIZE, length)
        slot.length = length
        writeRtpHeader(data, RIST_HEADROOM, slot.sequence, rtpTimestampOf(now - origin), flowId)
        account(transmit(peer, slot))
        data[RIST_HEADROOM + RIST_RTP_HEADER_SIZE - 1] = (flowId or 1).toByte()
        peer.packets += 1
        peer.octets += length
        ratePackets += 1
    }

    private fun transmit(peer: RistPeer, slot: RistSlot): Int {
        val end = RIST_HEADROOM + RIST_RTP_HEADER_SIZE + slot.length
        if (simple) {
            return peer.sendData(slot.data, RIST_HEADROOM, end - RIST_HEADROOM)
        }
        val start = RIST_HEADROOM - greHeaderSize(peer.greVersion, GreFrame.Data)
        writeGreHeader(
            slot.data,
            start,
            peer.greVersion,
            GreFrame.Data,
            peer.nextGreSequence(),
            peer.sourcePort,
            target.virtualDestinationPort,
        )
        return peer.sendData(slot.data, start, end - start)
    }

    private fun account(wireBytes: Int) {
        totalBytesSent += wireBytes
        rateBytes += wireBytes
    }

    private fun handlePacket(peer: RistPeer, bytes: ByteArray, length: Int, receivedAt: Long) {
        if (terminated.get() || peer.closed) {
            return
        }
        if (receivedAt > peer.lastReceivedAt) {
            peer.lastReceivedAt = receivedAt
        }
        var rtcpAt = 0
        if (!simple) {
            val gre = parseGre(bytes, length) ?: return
            if (gre.version > peer.greVersion) {
                peer.greVersion = gre.version
            }
            if (gre.frame != GreFrame.Data) {
                return
            }
            rtcpAt = gre.payloadOffset
        }
        val messages = parseRtcp(bytes, rtcpAt, length - rtcpAt)
        if (messages.isEmpty()) {
            return
        }
        if (peer.state != RistPeerState.Connected) {
            peerAnswered(peer)
        }
        for (message in messages) {
            when (message) {
                is EchoRequest -> respondToEcho(peer, message)
                is EchoResponse -> measureRtt(peer, message, receivedAt)
                is ReceiverReport -> peer.reportedLoss = message.fractionLost * 100.0 / 256
                is Nack -> handleNack(peer, message, System.nanoTime())
                SourceDescription -> {}
            }
        }
        if (retries.size > 0) {
            sendRetransmits(System.nanoTime())
        }
    }

    private fun peerAnswered(peer: RistPeer) {
        peer.state = RistPeerState.Connected
        refreshUsable(System.nanoTime())
        if (!connected) {
            connected = true
            idleSince = NONE
            queue.open()
            listener.onConnected()
        }
    }

    private fun measureRtt(peer: RistPeer, response: EchoResponse, receivedAt: Long) {
        if (response.ssrc != flowId) {
            return
        }
        val rtt = receivedAt - (nanosOf(response.ntp) + origin) - response.delayMicros * 1000L
        if (rtt in 0..MAXIMUM_ECHO_NANOS) {
            peer.updateRtt(rtt)
        }
    }

    private fun handleNack(peer: RistPeer, nack: Nack, now: Long) {
        val spacing = peer.lastRttNanos.coerceIn(MINIMUM_NACK_SPACING_NANOS, MAXIMUM_NACK_SPACING_NANOS)
        for (sequence in nack.sequences) {
            if (retransmit.request(sequence, now, spacing, MAXIMUM_RETRANSMITS) != RistRequestResult.Accepted) {
                continue
            }
            rateRequests += 1
            if (!retries.add(sequence, peer)) {
                retransmit.find(sequence, now)?.queued = false
            }
        }
    }

    private fun sendRetransmits(now: Long) {
        var budget = RETRANSMITS_PER_RUN
        while (retries.size > 0 && budget > 0) {
            val sequence = retries.firstSequence()
            val requester = retries.firstRequester()
            retries.removeFirst()
            val slot = retransmit.find(sequence, now) ?: continue
            slot.queued = false
            if (sendBitrate > bandwidthBps) {
                continue
            }
            val egress = if (requester.usable && !requester.closed) requester else peers.firstOrNull { it.usable } ?: continue
            budget -= 1
            slot.retransmits += 1
            retransmitted += 1
            account(transmit(egress, slot))
        }
    }

    private fun respondToEcho(peer: RistPeer, request: EchoRequest) {
        var end = writeEmptyReceiverReport(rtcpBuffer, RIST_HEADROOM, flowId)
        end = writeSourceDescription(rtcpBuffer, end, flowId, cname)
        end = writeEchoResponse(rtcpBuffer, end, request.ssrc, request.ntp)
        sendRtcp(peer, end)
    }

    private fun sendPeriodicRtcp(peer: RistPeer, now: Long) {
        val elapsed = now - origin
        var end = writeSenderReport(
            rtcpBuffer,
            RIST_HEADROOM,
            flowId,
            ntpOf(System.currentTimeMillis() * 1_000_000L),
            rtpTimestampOf(elapsed),
            peer.packets,
            peer.octets,
        )
        end = writeSourceDescription(rtcpBuffer, end, flowId, cname)
        end = writeEchoRequest(rtcpBuffer, end, flowId, ntpOf(elapsed))
        sendRtcp(peer, end)
    }

    private fun sendEchoRequest(peer: RistPeer, now: Long) {
        var end = writeEmptyReceiverReport(rtcpBuffer, RIST_HEADROOM, flowId)
        end = writeSourceDescription(rtcpBuffer, end, flowId, cname)
        end = writeEchoRequest(rtcpBuffer, end, flowId, ntpOf(now - origin))
        sendRtcp(peer, end)
    }

    private fun sendRtcp(peer: RistPeer, end: Int) {
        if (simple) {
            account(peer.sendControl(rtcpBuffer, RIST_HEADROOM, end - RIST_HEADROOM))
            return
        }
        val start = RIST_HEADROOM - greHeaderSize(peer.greVersion, GreFrame.Data)
        writeGreHeader(
            rtcpBuffer,
            start,
            peer.greVersion,
            GreFrame.Data,
            peer.nextGreSequence(),
            peer.sourcePort,
            target.virtualDestinationPort + 1,
        )
        account(peer.sendControl(rtcpBuffer, start, end - start))
    }

    private fun sendKeepalive(peer: RistPeer, version: Int) {
        val start = RIST_HEADROOM - greHeaderSize(version, GreFrame.Keepalive)
        val payloadAt = writeGreHeader(rtcpBuffer, start, version, GreFrame.Keepalive, peer.nextGreSequence())
        account(peer.sendControl(rtcpBuffer, start, writeKeepalive(rtcpBuffer, payloadAt, mac) - start))
    }

    private fun sendBufferNegotiation(peer: RistPeer) {
        val start = RIST_HEADROOM - greHeaderSize(2, GreFrame.BufferNegotiation)
        val payloadAt = writeGreHeader(rtcpBuffer, start, 2, GreFrame.BufferNegotiation, peer.nextGreSequence())
        account(peer.sendControl(rtcpBuffer, start, writeBufferNegotiation(rtcpBuffer, payloadAt, retentionMs) - start))
    }

    private fun startPeer(peer: RistPeer, now: Long) {
        peer.start()
        if (!simple) {
            repeat(STARTUP_KEEPALIVES) { sendKeepalive(peer, 2) }
            repeat(STARTUP_KEEPALIVES) { sendKeepalive(peer, 1) }
            repeat(STARTUP_KEEPALIVES) { sendBufferNegotiation(peer) }
        }
        sendPeriodicRtcp(peer, now)
        repeat(STARTUP_ECHOES) { sendEchoRequest(peer, now) }
    }

    private fun tick() {
        if (terminated.get()) {
            return
        }
        val now = System.nanoTime()
        ticks += 1
        for (index in peers.indices) {
            updateState(peers[index], now)
        }
        for (index in peers.indices) {
            val peer = peers[index]
            sendPeriodicRtcp(peer, now)
            if (!simple && ticks % 10 == 0L) {
                sendKeepalive(peer, peer.greVersion)
            }
        }
        if (ticks % 10 == 0L) {
            syncLinks()
        }
        if (ticks % 2 == 0L && peers.size > 1) {
            updateWeights()
        }
        refreshUsable(now)
        sendRetransmits(now)
        updateRates(now)
        publish()
        evaluate(now)
    }

    private fun updateState(peer: RistPeer, now: Long) {
        when (peer.state) {
            RistPeerState.Connecting -> if (now - peer.createdAt >= connectTimeoutNanos) {
                peer.state = RistPeerState.Disconnected
            }
            RistPeerState.Connected -> if (now - peer.lastReceivedAt > livenessNanos) {
                peer.state = RistPeerState.Disconnected
            }
            RistPeerState.Disconnected -> {}
        }
    }

    private fun updateWeights() {
        for (index in peers.indices) {
            val peer = peers[index]
            if (peer.state == RistPeerState.Connected && peer.hasRtt) {
                peer.linkWeight.update(peer.rttMs)
                peer.weight = peer.linkWeight.weight
            }
        }
    }

    private fun refreshUsable(now: Long) {
        if (weights.size < peers.size) {
            weights = IntArray(peers.size)
            usable = BooleanArray(peers.size)
        }
        var healthy = false
        for (index in peers.indices) {
            val peer = peers[index]
            healthy = healthy || (peer.state == RistPeerState.Connected && now - peer.lastReceivedAt <= STALL_NANOS)
        }
        for (index in peers.indices) {
            val peer = peers[index]
            val silent = now - peer.lastReceivedAt > STALL_NANOS
            peer.usable = peer.state == RistPeerState.Connected && (!silent || !healthy)
            weights[index] = peer.weight
            usable[index] = peer.usable
        }
    }

    private fun updateRates(now: Long) {
        val elapsed = now - rateAt
        if (elapsed <= 0) {
            return
        }
        rateAt = now
        smoothBitrate = 0.7 * smoothBitrate + 0.3 * (rateBytes * 8e9 / elapsed)
        val latestLoss = if (ratePackets > 0) minOf(100.0, 100.0 * rateRequests / ratePackets) else 0.0
        smoothLoss = 0.7 * smoothLoss + 0.3 * latestLoss
        rateBytes = 0
        ratePackets = 0
        rateRequests = 0
    }

    private fun publish() {
        sendBitrate = smoothBitrate.toLong()
        var best = 0.0
        var reported = 0.0
        val current = ArrayList<LinkUsage>(peers.size)
        for (index in peers.indices) {
            val peer = peers[index]
            if (peer.state == RistPeerState.Connected && peer.hasRtt && (best == 0.0 || peer.rttMs < best)) {
                best = peer.rttMs
            }
            reported = maxOf(reported, peer.reportedLoss)
            current.add(LinkUsage(peer.id, peer.name, peer.type, peer.bytesSent, peer.rttMs.roundToInt()))
        }
        bestRttMs = best
        lostPercent = maxOf(smoothLoss, reported)
        usage = current
    }

    private fun evaluate(now: Long) {
        if (peers.any { it.state != RistPeerState.Disconnected }) {
            idleSince = NONE
            return
        }
        if (idleSince == NONE) {
            idleSince = now
        }
        if (peers.isNotEmpty() || now - idleSince >= connectTimeoutNanos) {
            fail(if (connected) "RIST receiver stopped answering" else "RIST handshake timed out")
        }
    }

    private fun syncLinks() {
        if (links == null || terminated.get()) {
            return
        }
        val now = System.nanoTime()
        var changed = false
        val iterator = peers.iterator()
        while (iterator.hasNext()) {
            val peer = iterator.next()
            val link = desiredLinks.firstOrNull { it.id == peer.id }
            if (link == null || link !== peer.link) {
                closePeer(peer)
                iterator.remove()
                changed = true
            }
        }
        for (link in desiredLinks) {
            if (peers.none { it.id == link.id }) {
                changed = addPeer(link, link.id, link.name, link.type, now) || changed
            }
        }
        if (changed) {
            selector.reset()
            refreshUsable(now)
            publish()
        }
    }

    private fun addPeer(link: NetworkLink?, id: String, name: String, type: LinkType, now: Long): Boolean {
        val remote = address ?: return false
        val factory = if (link != null) link::createDatagramSocket else environment.socketFactory
        val control = try {
            configure(factory())
        } catch (_: Exception) {
            return false
        }
        val data = if (simple) {
            try {
                configure(factory())
            } catch (_: Exception) {
                closeSocket(control)
                return false
            }
        } else {
            control
        }
        nextPeerIndex += 1
        val peer = RistPeer(
            id,
            name,
            type,
            link,
            RIST_FIRST_VIRTUAL_SOURCE_PORT + nextPeerIndex % VIRTUAL_PORT_SPAN,
            remote,
            simple,
            data,
            control,
            now,
        ) { source, bytes, length, receivedAt ->
            engine.execute { handlePacket(source, bytes, length, receivedAt) }
        }
        peers.add(peer)
        startPeer(peer, now)
        return true
    }

    private fun configure(socket: DatagramSocket): DatagramSocket {
        sockets.add(socket)
        try {
            socket.sendBufferSize = SOCKET_BUFFER_BYTES
            socket.receiveBufferSize = SOCKET_BUFFER_BYTES
        } catch (_: Exception) {
        }
        return socket
    }

    private fun closeSocket(socket: DatagramSocket) {
        sockets.remove(socket)
        socket.close()
    }

    private fun closePeer(peer: RistPeer) {
        peer.close()
        sockets.remove(peer.dataSocket)
        sockets.remove(peer.controlSocket)
    }

    private fun teardown() {
        scope?.cancel()
        scope = null
        timer?.cancel(false)
        for (peer in peers) {
            closePeer(peer)
        }
        peers.clear()
        retries.clear()
        engine.shutdown()
    }

    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        queue.close()
        teardown()
        listener.onDisconnected(reason)
    }

    private fun millis(value: Long): Long = TimeUnit.MILLISECONDS.toNanos(value)
}
