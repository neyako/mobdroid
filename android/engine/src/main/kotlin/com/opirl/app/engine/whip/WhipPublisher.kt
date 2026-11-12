package com.opirl.app.engine.whip

import com.opirl.app.engine.media.AudioCodec
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.media.VideoFormat
import com.opirl.app.engine.net.Publisher
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.engine.net.PublisherStats
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val MAX_BACKLOG_NANOS = 1_000_000_000L
private const val MAX_BACKLOG_BYTES = 4L * 1024 * 1024
private const val KEY_FRAME_REQUEST_NANOS = 500_000_000L
private const val STOP_JOIN_MS = 400L
private const val DELETE_TIMEOUT_MS = 1000L
private const val TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
private val SDP_TYPE = "application/sdp".toMediaType()

internal class WhipEnvironment(
    val connectTimeoutMs: Long = 10_000,
    val formatWaitMs: Long = 2000,
    val consentIntervalNanos: Long = 2_000_000_000L,
    val consentTimeoutNanos: Long = 10_000_000_000L,
    val localAddresses: (String) -> List<InetAddress> = ::gatherLocalAddresses,
)

class WhipPublisher internal constructor(
    url: String,
    private val headers: Map<String, String>,
    private val listener: PublisherListener,
    private val http: OkHttpClient,
    private val requestKeyFrame: () -> Unit,
    private val environment: WhipEnvironment,
) : Publisher {
    constructor(
        url: String,
        headers: Map<String, String>,
        listener: PublisherListener,
        http: OkHttpClient = OkHttpClient(),
        requestKeyFrame: () -> Unit = {},
    ) : this(url, headers, listener, http, requestKeyFrame, WhipEnvironment())

    private val endpoint = parseWhipUrl(url)
    private val random = SecureRandom()
    private val queue = WhipQueue(MAX_BACKLOG_NANOS, MAX_BACKLOG_BYTES)
    private val counters = SendCounters()
    private val receiverStats = StreamReceiverStats()
    private val clock = MediaClock()
    private val started = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)
    private val formatLock = Object()
    private val threads = ArrayList<Thread>(3)

    @Volatile private var videoFormat: VideoFormat? = null

    @Volatile private var audioCodec: AudioCodec? = null

    @Volatile private var live = false

    @Volatile private var audioAccepted = false

    @Volatile private var goodbye = false

    @Volatile private var socket: DatagramSocket? = null

    @Volatile private var ice: IceAgent? = null

    @Volatile private var dtlsTransport: DtlsDatagramTransport? = null

    @Volatile private var dtls: DtlsResult? = null

    @Volatile private var call: Call? = null

    @Volatile private var sessionUrl: HttpUrl? = null

    @Volatile private var videoSsrc = 0

    @Volatile private var audioSsrc = 0

    private var lastKeyFrameRequest = 0L

    override fun start() {
        if (terminated.get() || !started.compareAndSet(false, true)) {
            return
        }
        spawn("whip-session") { connect() }
    }

    override fun stop() {
        if (terminated.compareAndSet(false, true)) {
            goodbye = true
            teardown()
            deleteSession(wait = true)
        }
        val current = Thread.currentThread()
        for (thread in synchronized(threads) { threads.toList() }) {
            if (thread !== current) {
                thread.join(STOP_JOIN_MS)
            }
        }
    }

    override fun stats(): PublisherStats = PublisherStats(
        bytesSent = counters.bytesSent.get(),
        sendBitrateBps = counters.rate.bitsPerSecond(),
        rttMs = receiverStats.rttMs.takeIf { it > 0 } ?: ice?.rttMs ?: 0.0,
        queuedBytes = queue.queuedBytes,
        retransmittedPackets = counters.retransmitted.get(),
        droppedVideoFrames = queue.droppedVideoFrames,
        lostPercent = receiverStats.lostPercent,
    )

    override fun setVideoFormat(format: VideoFormat) {
        videoFormat = format
        synchronized(formatLock) { formatLock.notifyAll() }
    }

    override fun setAudioFormat(format: AudioFormat) {
        audioCodec = format.codec
    }

    override fun writeVideo(frame: EncodedFrame) {
        if (live) {
            queue.addVideo(frame)
        }
    }

    override fun writeAudio(frame: EncodedFrame) {
        if (live && audioAccepted && audioCodec == AudioCodec.OPUS) {
            queue.addAudio(frame)
        }
    }

    private fun spawn(name: String, body: () -> Unit) {
        val thread = Thread({
            try {
                body()
            } catch (_: InterruptedException) {
            } catch (error: Exception) {
                fail("WHIP internal error: ${error.message ?: error.javaClass.simpleName}")
            }
        }, name)
        thread.isDaemon = true
        synchronized(threads) { threads.add(thread) }
        thread.start()
    }

    private fun awaitVideoFormat(): VideoFormat? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(environment.formatWaitMs)
        synchronized(formatLock) {
            while (videoFormat == null && !terminated.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    break
                }
                formatLock.wait(remaining / 1_000_000 + 1)
            }
        }
        return videoFormat
    }

    private fun connect() {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(environment.connectTimeoutMs)
        val codec = awaitVideoFormat()?.codec ?: VideoCodec.H264
        if (terminated.get()) {
            return
        }
        if (codec == VideoCodec.AV1) {
            fail("AV1 is not supported over WHIP")
            return
        }
        val identity = DtlsIdentity.generate(random)
        val udp = DatagramSocket(0)
        socket = udp
        if (terminated.get()) {
            udp.close()
            return
        }
        val ufrag = token(8)
        val password = token(24)
        val candidates = gatherLocalCandidates(environment.localAddresses(endpoint.host), udp.localPort)
        val checker = IceAgent(
            udp,
            ufrag,
            password,
            candidates,
            environment.consentIntervalNanos,
            environment.consentTimeoutNanos,
            random = random,
        )
        ice = checker
        val cname = token(16)
        val streamId = token(16)
        videoSsrc = ssrc()
        audioSsrc = ssrc(videoSsrc)
        val rtxSsrc = ssrc(videoSsrc, audioSsrc)
        val offer = buildOffer(
            SdpOfferParams(
                sessionId = random.nextLong() and Long.MAX_VALUE,
                iceUfrag = ufrag,
                icePassword = password,
                fingerprint = formatFingerprint(identity.fingerprint),
                setup = DtlsSetup.ACTPASS,
                cname = cname,
                streamId = streamId,
                audioTrackId = "$streamId-audio",
                videoTrackId = "$streamId-video",
                audioSsrc = audioSsrc,
                videoCodec = codec,
                videoSsrc = videoSsrc,
                rtxSsrc = rtxSsrc,
                offerRtx = true,
                candidates = candidates,
            ),
        )
        spawn("whip-receiver") { receive(udp, checker) }
        val answer = exchange(offer, codec, deadline) ?: return
        val video = answer.video
        if (video == null || video.rejected || video.payloadType < 0) {
            fail("WHIP server refused the ${if (codec == VideoCodec.HEVC) "HEVC" else "H.264"} video")
            return
        }
        if (!DtlsFingerprint.isSupported(answer.fingerprintAlgorithm)) {
            fail("WHIP answer has an unknown fingerprint")
            return
        }
        if (answer.candidates.isEmpty()) {
            fail("WHIP answer has no candidates")
            return
        }
        checker.setRemote(answer.iceUfrag, answer.icePassword, answer.candidates, answer.iceLite)
        while (checker.state == IceState.NEW || checker.state == IceState.CHECKING) {
            if (terminated.get()) {
                return
            }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                fail("WHIP connection timed out")
                return
            }
            checker.awaitChange(minOf(remaining, 100_000_000L))
        }
        if (checker.state != IceState.CONNECTED) {
            fail(checker.failure ?: "ICE failed")
            return
        }
        val transport = DtlsDatagramTransport { buffer, offset, length ->
            val remote = checker.selectedRemote
            if (remote != null) {
                udp.send(DatagramPacket(buffer, offset, length, remote))
            }
        }
        transport.deadlineNanos = deadline
        dtlsTransport = transport
        if (terminated.get()) {
            transport.close()
            return
        }
        val handshake = DtlsEndpoint(identity, DtlsFingerprint(answer.fingerprintAlgorithm, answer.fingerprint), SrtpProfile.entries)
        val result = try {
            if (answer.setup == DtlsSetup.ACTIVE) handshake.acceptAsServer(transport) else handshake.connectAsClient(transport)
        } catch (error: Exception) {
            fail(describeDtlsFailure(error, null))
            return
        }
        transport.deadlineNanos = Long.MAX_VALUE
        dtls = result
        val audio = answer.audio
        audioAccepted = audio != null && !audio.rejected && audio.payloadType >= 0
        val videoTrack = SenderTrack(
            videoSsrc,
            video.payloadType,
            90_000,
            if (video.rtxPayloadType >= 0) rtxSsrc else 0,
            video.rtxPayloadType,
            VIDEO_NACK_PACKETS,
            random,
        )
        val audioTrack = SenderTrack(
            audioSsrc,
            if (audioAccepted) audio!!.payloadType else OPUS_PAYLOAD_TYPE,
            48_000,
            0,
            -1,
            AUDIO_NACK_PACKETS,
            random,
        )
        val sender = WhipSender(
            udp,
            { checker.selectedRemote },
            result.keys.local,
            queue,
            clock,
            videoTrack,
            audioTrack,
            if (codec == VideoCodec.HEVC) HevcPacketizer() else H264Packetizer(),
            { videoFormat?.config },
            cname.toByteArray(Charsets.UTF_8),
            counters,
            { (receiverStats.rttMs * 1_000_000).toLong() },
            { goodbye },
            { reason -> fail("WHIP ${reason.replaceFirstChar { it.lowercase() }}") },
        )
        if (terminated.get()) {
            return
        }
        spawn("whip-sender") { sender.run() }
        live = true
        requestKeyFrame()
        listener.onConnected()
        watch(result)
    }

    private fun exchange(offer: String, codec: VideoCodec, deadline: Long): SdpAnswer? {
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
        val request = Request.Builder().url(endpoint).post(offer.toRequestBody(SDP_TYPE))
        for ((name, value) in headers) {
            request.header(name, value)
        }
        val pending = http.newBuilder().callTimeout(remaining, TimeUnit.MILLISECONDS).build().newCall(request.build())
        call = pending
        if (terminated.get()) {
            pending.cancel()
            return null
        }
        val body = try {
            pending.execute().use { response ->
                if (!response.isSuccessful) {
                    fail("WHIP server returned HTTP status ${response.code}")
                    return null
                }
                response.header("Location")?.let { sessionUrl = endpoint.resolve(it) }
                response.body.string()
            }
        } catch (error: IOException) {
            fail("Sending WHIP offer failed: ${error.message ?: error.javaClass.simpleName}")
            return null
        }
        if (terminated.get()) {
            deleteSession(wait = false)
            return null
        }
        return try {
            parseAnswer(body, codec, videoPayloadType(codec))
        } catch (error: SdpException) {
            fail("Bad WHIP answer: ${error.message}")
            null
        }
    }

    private fun watch(result: DtlsResult) {
        val buffer = ByteArray(2048)
        while (!terminated.get()) {
            try {
                result.transport.receive(buffer, 0, buffer.size, 1000)
            } catch (error: Exception) {
                fail(describeDtlsFailure(error, result.peerAlert()))
                return
            }
            val alert = result.peerAlert()
            if (alert != null) {
                fail("DTLS alert from server: $alert")
                return
            }
        }
    }

    private fun receive(udp: DatagramSocket, agent: IceAgent) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!udp.isClosed) {
            val wait = agent.tick() / 1_000_000
            if (agent.state == IceState.FAILED) {
                if (live) {
                    fail(agent.failure ?: "ICE failed")
                }
                return
            }
            udp.soTimeout = wait.coerceIn(1, if (agent.state == IceState.CONNECTED) 1000 else 50).toInt()
            try {
                packet.setData(buffer, 0, buffer.size)
                udp.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                if (udp.isClosed) {
                    return
                }
                continue
            }
            val length = packet.length
            if (length < 2) {
                continue
            }
            val from = packet.socketAddress as InetSocketAddress
            val first = buffer[0].toInt() and 0xFF
            when {
                first < 4 -> agent.onPacket(buffer, length, from)
                first in 20..63 -> if (agent.isPeerAddress(from)) dtlsTransport?.offer(buffer, length)
                first in 128..191 -> if ((buffer[1].toInt() and 0xFF) in 192..223 && agent.isPeerAddress(from)) feedback(buffer, length)
            }
        }
    }

    private fun feedback(buffer: ByteArray, protectedLength: Int) {
        val keys = dtls ?: return
        val length = keys.keys.remote.unprotectRtcp(buffer, protectedLength)
        if (length < 0) {
            return
        }
        val now = System.nanoTime()
        for (message in parseRtcp(buffer, 0, length)) {
            when (message) {
                is RtcpPacket.ReceiverReport -> report(message.blocks, now)
                is RtcpPacket.SenderReport -> report(message.blocks, now)
                is RtcpPacket.Nack -> if (message.mediaSsrc == videoSsrc || message.mediaSsrc == audioSsrc) {
                    queue.postRetransmit(message.mediaSsrc, message.sequences)
                }
                is RtcpPacket.Pli, is RtcpPacket.Fir -> if (now - lastKeyFrameRequest >= KEY_FRAME_REQUEST_NANOS || lastKeyFrameRequest == 0L) {
                    lastKeyFrameRequest = now
                    requestKeyFrame()
                }
                is RtcpPacket.Bye -> {
                    fail("WHIP server ended the session")
                    return
                }
                else -> {}
            }
        }
    }

    private fun report(blocks: List<ReportBlock>, now: Long) {
        for (block in blocks) {
            if (block.ssrc != videoSsrc) {
                continue
            }
            receiverStats.lostPercent = block.fractionLost * 100.0 / 256
            receiverStats.lastReportNanos = now
            if (clock.isAnchored) {
                roundTripMs(block, clock.ntpAt(now))?.let { receiverStats.rttMs = it }
            }
        }
    }

    private fun teardown() {
        live = false
        queue.close()
        call?.cancel()
        synchronized(formatLock) { formatLock.notifyAll() }
        if (goodbye) {
            synchronized(threads) { threads.firstOrNull { it.name == "whip-sender" } }?.let {
                if (it !== Thread.currentThread()) {
                    it.join(STOP_JOIN_MS)
                }
            }
            dtls?.let { runCatching { it.transport.close() } }
        }
        dtlsTransport?.close()
        ice?.wake()
        socket?.close()
    }

    private fun deleteSession(wait: Boolean) {
        val target = sessionUrl ?: return
        sessionUrl = null
        val request = Request.Builder().url(target).delete()
        for ((name, value) in headers) {
            request.header(name, value)
        }
        val pending = http.newBuilder().callTimeout(DELETE_TIMEOUT_MS, TimeUnit.MILLISECONDS).build().newCall(request.build())
        if (wait) {
            runCatching { pending.execute().close() }
        } else {
            pending.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.close()
                }
            })
        }
    }

    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        teardown()
        deleteSession(wait = false)
        listener.onDisconnected(reason)
    }

    private fun token(length: Int): String {
        val text = StringBuilder(length)
        repeat(length) { text.append(TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)]) }
        return text.toString()
    }

    private fun ssrc(vararg taken: Int): Int {
        while (true) {
            val value = random.nextInt()
            if (value != 0 && value !in taken) {
                return value
            }
        }
    }
}
