package com.opirl.app.engine.whip

import com.opirl.app.engine.media.EncodedFrame
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

private const val VIDEO_CLOCK_RATE = 90_000
private const val AUDIO_CLOCK_RATE = 48_000
private const val AUDIO_RESYNC_TICKS = 1920
private const val MIN_RESEND_NANOS = 10_000_000L
private const val SEND_BUFFER = RTP_PACKET_CAPACITY + 32

internal const val VIDEO_NACK_PACKETS = 8192
internal const val AUDIO_NACK_PACKETS = 512

internal class SenderTrack(
    val ssrc: Int,
    val payloadType: Int,
    val clockRate: Int,
    val rtxSsrc: Int,
    val rtxPayloadType: Int,
    historySize: Int,
    random: SecureRandom,
) {
    var sequence = random.nextInt(0x10000)
    var rtxSequence = random.nextInt(0x10000)
    val baseTimestamp = random.nextInt()
    val history = NackHistory(historySize)
    var lastTimestamp = 0
    var lastSamples = 0
    var lastPtsUs = 0L
    var packets = 0L
    var octets = 0L

    val hasRtx: Boolean
        get() = rtxSsrc != 0 && rtxPayloadType >= 0
}

internal class SendCounters {
    val bytesSent = AtomicLong()
    val retransmitted = AtomicLong()
    val rate = com.opirl.app.engine.rtmp.RateWindow()
}

internal class WhipSender(
    private val socket: DatagramSocket,
    private val remote: () -> InetSocketAddress?,
    private val srtp: SrtpContext,
    private val queue: WhipQueue,
    private val clock: MediaClock,
    private val video: SenderTrack,
    private val audio: SenderTrack,
    private val packetizer: VideoPacketizer,
    private val videoConfig: () -> ByteArray?,
    private val cname: ByteArray,
    private val counters: SendCounters,
    private val roundTripNanos: () -> Long,
    private val sayGoodbye: () -> Boolean,
    private val onFailure: (String) -> Unit,
    private val reportIntervalNanos: Long = 1_000_000_000L,
) : Runnable {
    private val audioPacket = ByteArray(SEND_BUFFER)
    private val retransmitBuffer = ByteArray(SEND_BUFFER)
    private val rtxBuffer = ByteArray(SEND_BUFFER)
    private val rtcpBuffer = ByteArray(1024)
    private val compound = RtcpCompound(rtcpBuffer)
    private val outgoing = DatagramPacket(ByteArray(0), 0)
    private var outgoingTarget: InetSocketAddress? = null
    private var videoStarted = false
    private var videoTimestamp = 0
    private val videoSink = PacketSink { buf, payloadLength, marker -> sendRtp(video, buf, payloadLength, marker, videoTimestamp) }

    override fun run() {
        val item = QueueItem()
        var nextReport = System.nanoTime() + reportIntervalNanos
        try {
            while (true) {
                when (queue.take(item, maxOf(nextReport - System.nanoTime(), 0L))) {
                    QUEUE_CLOSED -> break
                    QUEUE_ITEM -> process(item)
                }
                val now = System.nanoTime()
                if (now >= nextReport) {
                    sendReports(now, withBye = false)
                    nextReport = now + reportIntervalNanos
                }
            }
            if (sayGoodbye()) {
                sendReports(System.nanoTime(), withBye = true)
            }
        } catch (_: InterruptedException) {
        } catch (error: IOException) {
            onFailure("Send failed: ${error.message ?: error.javaClass.simpleName}")
        } catch (error: Exception) {
            onFailure("Internal error: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun process(item: QueueItem) {
        val frame = item.frame
        if (frame != null) {
            if (item.isVideo) sendVideo(frame) else sendAudio(frame)
        } else {
            retransmit(item.retransmitSsrc, item.retransmitSequences!!)
        }
    }

    private fun sendVideo(frame: EncodedFrame) {
        if (!videoStarted) {
            if (!frame.isKeyFrame) {
                return
            }
            videoStarted = true
        }
        clock.anchor(frame.ptsUs)
        videoTimestamp = (video.baseTimestamp + clock.ticks(frame.ptsUs, VIDEO_CLOCK_RATE)).toInt()
        video.lastTimestamp = videoTimestamp
        video.lastPtsUs = frame.ptsUs
        packetizer.packetize(frame.data, videoConfig(), frame.isKeyFrame, videoSink)
    }

    private fun sendAudio(frame: EncodedFrame) {
        val size = frame.data.size
        if (size == 0 || size > RTP_MAX_PAYLOAD) {
            return
        }
        clock.anchor(frame.ptsUs)
        val derived = (audio.baseTimestamp + clock.ticks(frame.ptsUs, AUDIO_CLOCK_RATE)).toInt()
        val first = audio.packets == 0L
        val timestamp = if (first) {
            derived
        } else {
            val expected = audio.lastTimestamp + audio.lastSamples
            if (abs(derived - expected) > AUDIO_RESYNC_TICKS) derived else expected
        }
        audio.lastTimestamp = timestamp
        audio.lastSamples = opusPacketSamples(frame.data)
        audio.lastPtsUs = frame.ptsUs
        System.arraycopy(frame.data, 0, audioPacket, RTP_HEADER_SIZE, size)
        sendRtp(audio, audioPacket, size, first, timestamp)
    }

    private fun sendRtp(track: SenderTrack, buf: ByteArray, payloadLength: Int, marker: Boolean, timestamp: Int) {
        val sequence = track.sequence
        track.sequence = (sequence + 1) and 0xFFFF
        writeRtpHeader(buf, marker, track.payloadType, sequence, timestamp, track.ssrc)
        val length = RTP_HEADER_SIZE + payloadLength
        track.history.put(sequence, buf, length)
        track.packets++
        track.octets += payloadLength
        transmit(buf, length)
    }

    private fun retransmit(ssrc: Int, sequences: IntArray) {
        val track = if (ssrc == video.ssrc) video else if (ssrc == audio.ssrc) audio else return
        val now = System.nanoTime()
        val interval = maxOf(roundTripNanos(), MIN_RESEND_NANOS)
        for (sequence in sequences) {
            val length = track.history.take(sequence, now, interval, retransmitBuffer)
            if (length < 0) {
                continue
            }
            if (track.hasRtx) {
                val rtxLength = buildRtxPacket(retransmitBuffer, length, track.rtxPayloadType, track.rtxSequence, track.rtxSsrc, rtxBuffer)
                if (rtxLength < 0) {
                    continue
                }
                track.rtxSequence = (track.rtxSequence + 1) and 0xFFFF
                transmit(rtxBuffer, rtxLength)
            } else {
                transmit(retransmitBuffer, length)
            }
            counters.retransmitted.incrementAndGet()
        }
    }

    private fun transmit(buf: ByteArray, length: Int) {
        val protectedLength = srtp.protectRtp(buf, length)
        if (protectedLength < 0) {
            return
        }
        send(buf, protectedLength)
    }

    private fun send(buf: ByteArray, length: Int) {
        val target = remote() ?: return
        outgoing.setData(buf, 0, length)
        if (outgoingTarget != target) {
            outgoing.socketAddress = target
            outgoingTarget = target
        }
        socket.send(outgoing)
        counters.bytesSent.addAndGet(length.toLong())
        counters.rate.add(length.toLong())
    }

    private fun sendReports(now: Long, withBye: Boolean) {
        compound.clear()
        val ntp = clock.ntpAt(now)
        val ptsNow = clock.ptsAt(now)
        val active = ArrayList<Int>(3)
        for (track in listOf(audio, video)) {
            if (track.packets == 0L) {
                continue
            }
            val timestamp = (track.lastTimestamp + (ptsNow - track.lastPtsUs) * track.clockRate / 1_000_000).toInt()
            compound.senderReport(track.ssrc, ntp, timestamp, track.packets, track.octets)
            active.add(track.ssrc)
        }
        if (active.isEmpty()) {
            return
        }
        compound.sdes(active.toIntArray(), cname)
        if (withBye) {
            if (video.hasRtx) {
                active.add(video.rtxSsrc)
            }
            compound.bye(active.toIntArray())
        }
        val length = srtp.protectRtcp(rtcpBuffer, compound.length)
        if (length > 0) {
            send(rtcpBuffer, length)
        }
    }
}
