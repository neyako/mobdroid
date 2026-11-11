package com.opirl.app.engine.rist

import com.opirl.app.engine.flv.GeneratedAudio
import com.opirl.app.engine.flv.GeneratedVideo
import com.opirl.app.engine.media.AudioFormat
import com.opirl.app.engine.media.EncodedFrame
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.media.VideoFormat
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.engine.srt.getInt
import com.opirl.app.engine.srt.getShort
import com.opirl.app.engine.srt.putInt
import com.opirl.app.engine.srt.putShort
import com.opirl.app.engine.srt.runTool
import com.opirl.app.engine.srt.toolWorks
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

fun ffmpegWithRist(): Boolean =
    toolWorks("ffmpeg", "-version") && toolWorks("ffprobe", "-version") &&
        runTool(10, "ffmpeg", "-hide_banner", "-protocols").output.lines().any { it.trim() == "rist" }

fun freeEvenUdpPortPair(): Int {
    repeat(100) {
        val first = DatagramSocket(0).use { it.localPort } and 1.inv()
        try {
            DatagramSocket(first).use { DatagramSocket(first + 1).close() }
            return first
        } catch (_: Exception) {
        }
    }
    error("no free even UDP port pair")
}

fun ristThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter {
    it.isAlive && (it.name == "rist-publisher" || it.name == "rist-udp-reader")
}

class RecordingListener : PublisherListener {
    val connected = CountDownLatch(1)
    val disconnected = CountDownLatch(1)
    val connects = AtomicInteger()
    val disconnects = CopyOnWriteArrayList<String>()

    override fun onConnected() {
        connects.incrementAndGet()
        connected.countDown()
    }

    override fun onDisconnected(reason: String) {
        disconnects.add(reason)
        disconnected.countDown()
    }
}

class PassThroughMuxer(private val output: (ByteArray, Int, Int) -> Unit) : MediaSink {
    override fun setVideoFormat(format: VideoFormat) {}

    override fun setAudioFormat(format: AudioFormat) {}

    override fun writeVideo(frame: EncodedFrame) {
        output(frame.data, 0, frame.data.size)
    }

    override fun writeAudio(frame: EncodedFrame) {
        output(frame.data, 0, frame.data.size)
    }
}

fun transportPayload(seed: Int, packets: Int): ByteArray {
    val bytes = ByteArray(188 * packets)
    for (packet in 0 until packets) {
        bytes[packet * 188] = 0x47
        for (index in 1 until 188) {
            bytes[packet * 188 + index] = (seed * 31 + packet * 7 + index).toByte()
        }
    }
    return bytes
}

fun feedPaced(sink: MediaSink, video: GeneratedVideo, audio: GeneratedAudio, speed: Double = 1.0, between: (Int) -> Unit = {}) {
    val endUs = video.frames.last().ptsUs
    val events = ArrayList<Pair<Long, () -> Unit>>()
    video.frames.forEachIndexed { index, frame ->
        events.add(frame.ptsUs to {
            sink.writeVideo(frame)
            between(index)
        })
    }
    audio.frames.filter { it.ptsUs <= endUs }.forEach { events.add(it.ptsUs to { sink.writeAudio(it) }) }
    events.sortBy { it.first }
    val start = System.nanoTime()
    for ((pts, action) in events) {
        val due = start + (pts * 1000 / speed).toLong()
        while (true) {
            val remaining = due - System.nanoTime()
            if (remaining <= 0) {
                break
            }
            Thread.sleep(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        action()
    }
}

fun expectedAudioFrames(video: GeneratedVideo, audio: GeneratedAudio): Int =
    audio.frames.count { it.ptsUs <= video.frames.last().ptsUs }

fun startRistReceiver(
    port: Int,
    output: File,
    log: File,
    profile: String = "main",
    bufferMs: Int = 200,
): Process = ProcessBuilder(
    "ffmpeg", "-hide_banner", "-loglevel", "info", "-y",
    "-rist_profile", profile, "-buffer_size", "$bufferMs",
    "-analyzeduration", "500000", "-probesize", "50000", "-rw_timeout", "3000000",
    "-i", "rist://@127.0.0.1:$port",
    "-c", "copy", "-f", "mpegts", output.absolutePath,
).redirectErrorStream(true).redirectOutput(log).start()

fun awaitFfmpeg(process: Process): Boolean {
    if (process.waitFor(30, TimeUnit.SECONDS)) {
        return true
    }
    process.destroyForcibly()
    return false
}

class UdpProxy(
    private val target: InetSocketAddress,
    listenPort: Int = 0,
    private val drop: (ByteArray, Int) -> Boolean = { _, _ -> false },
) : AutoCloseable {
    private val listen = DatagramSocket(listenPort, InetAddress.getByName("127.0.0.1"))
    private val upstreams = ConcurrentHashMap<SocketAddress, DatagramSocket>()
    private val threads = CopyOnWriteArrayList<Thread>()
    val port: Int = listen.localPort
    val dropped = AtomicInteger()
    val forwarded = AtomicInteger()

    init {
        spawn("udp-proxy-listen") { forwardLoop() }
    }

    override fun close() {
        listen.close()
        upstreams.values.forEach { it.close() }
        threads.forEach { it.join(1000) }
    }

    private fun spawn(name: String, body: () -> Unit) {
        threads.add(Thread(body, name).also { it.isDaemon = true; it.start() })
    }

    private fun forwardLoop() {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!listen.isClosed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                listen.receive(packet)
            } catch (_: Exception) {
                return
            }
            if (drop(buffer, packet.length)) {
                dropped.incrementAndGet()
                continue
            }
            val client = packet.socketAddress
            val upstream = upstreams.computeIfAbsent(client) {
                DatagramSocket(0, InetAddress.getByName("127.0.0.1")).also { socket ->
                    spawn("udp-proxy-return") { returnLoop(socket, client) }
                }
            }
            forwarded.incrementAndGet()
            try {
                upstream.send(DatagramPacket(buffer.copyOf(packet.length), packet.length, target))
            } catch (_: Exception) {
            }
        }
    }

    private fun returnLoop(upstream: DatagramSocket, client: SocketAddress) {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!upstream.isClosed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                upstream.receive(packet)
                listen.send(DatagramPacket(buffer.copyOf(packet.length), packet.length, client))
            } catch (_: Exception) {
                return
            }
        }
    }
}

fun mainDataPacket(buffer: ByteArray, length: Int): Boolean {
    val gre = parseGre(buffer, length) ?: return false
    return gre.frame == GreFrame.Data && length >= gre.payloadOffset + RIST_RTP_HEADER_SIZE &&
        (buffer[gre.payloadOffset + 1].toInt() and 0x7F) == RIST_PAYLOAD_TYPE_MPEGTS
}

fun simpleDataPacket(buffer: ByteArray, length: Int): Boolean =
    length >= RIST_RTP_HEADER_SIZE && (buffer[1].toInt() and 0x7F) == RIST_PAYLOAD_TYPE_MPEGTS

fun lossFilter(probability: Double, seed: Int, isData: (ByteArray, Int) -> Boolean): (ByteArray, Int) -> Boolean {
    val random = Random(seed)
    return { buffer, length -> isData(buffer, length) && random.nextDouble() < probability }
}

fun rangeNack(ssrc: Int, vararg records: Pair<Int, Int>): ByteArray {
    val out = ByteArray(12 + 4 * records.size)
    out[0] = 0x80.toByte()
    out[1] = RTCP_APPLICATION.toByte()
    out.putShort(2, 2 + records.size)
    out.putInt(4, ssrc)
    "RIST".toByteArray().copyInto(out, 8)
    records.forEachIndexed { index, (start, extra) ->
        out.putShort(12 + 4 * index, start)
        out.putShort(14 + 4 * index, extra)
    }
    return out
}

fun bitmaskNack(ssrc: Int, vararg records: Pair<Int, Int>): ByteArray {
    val out = ByteArray(12 + 4 * records.size)
    out[0] = 0x81.toByte()
    out[1] = RTCP_NACK_BITMASK.toByte()
    out.putShort(2, 2 + records.size)
    out.putInt(4, 0)
    out.putInt(8, ssrc)
    records.forEachIndexed { index, (start, mask) ->
        out.putShort(12 + 4 * index, start)
        out.putShort(14 + 4 * index, mask)
    }
    return out
}

class ReceivedDatagram(val bytes: ByteArray, val from: SocketAddress, val onControlPort: Boolean)

internal class FakeRistReceiver(private val profile: RistProfile, private val answer: Boolean = true, port: Int? = null) : AutoCloseable {
    private val first: Int = port ?: if (profile == RistProfile.Simple) freeEvenUdpPortPair() else 0
    private val data = DatagramSocket(first, InetAddress.getByName("127.0.0.1"))
    private val control = if (profile == RistProfile.Simple) DatagramSocket(data.localPort + 1, InetAddress.getByName("127.0.0.1")) else data
    private val ssrc = 0x3007edb9
    val port: Int = data.localPort
    val datagrams = CopyOnWriteArrayList<ReceivedDatagram>()
    val peers = CopyOnWriteArrayList<SocketAddress>()
    @Volatile var answering = answer
    @Volatile var echoDelayMs = 0L
    @Volatile var greVersion = 2
    private val threads = ArrayList<Thread>()
    private var sequence = 0

    init {
        threads.add(Thread({ loop(data, false) }, "fake-rist-data").also { it.isDaemon = true; it.start() })
        if (control !== data) {
            threads.add(Thread({ loop(control, true) }, "fake-rist-control").also { it.isDaemon = true; it.start() })
        }
    }

    override fun close() {
        data.close()
        control.close()
        threads.forEach { it.join(1000) }
    }

    fun dataPackets(): List<ReceivedDatagram> = datagrams.filter { isData(it) }

    fun isData(datagram: ReceivedDatagram): Boolean = if (profile == RistProfile.Simple) {
        !datagram.onControlPort && simpleDataPacket(datagram.bytes, datagram.bytes.size)
    } else {
        mainDataPacket(datagram.bytes, datagram.bytes.size)
    }

    fun rtp(datagram: ReceivedDatagram): ByteArray {
        val offset = if (profile == RistProfile.Simple) 0 else parseGre(datagram.bytes, datagram.bytes.size)!!.payloadOffset
        return datagram.bytes.copyOfRange(offset, datagram.bytes.size)
    }

    fun sequenceOf(datagram: ReceivedDatagram): Int = rtp(datagram).getShort(2)

    fun ssrcOf(datagram: ReceivedDatagram): Int = rtp(datagram).getInt(8)

    fun send(peer: SocketAddress, rtcp: ByteArray) {
        val socket = if (profile == RistProfile.Simple) control else data
        val payload = if (profile == RistProfile.Simple) {
            rtcp
        } else {
            val out = ByteArray(greHeaderSize(greVersion, GreFrame.Data) + rtcp.size)
            val at = writeGreHeader(out, 0, greVersion, GreFrame.Data, sequence++, 1969, 32769)
            rtcp.copyInto(out, at)
            out
        }
        socket.send(DatagramPacket(payload, payload.size, peer))
    }

    fun keepalive(peer: SocketAddress) {
        val out = ByteArray(greHeaderSize(greVersion, GreFrame.Keepalive) + 8)
        writeKeepalive(out, writeGreHeader(out, 0, greVersion, GreFrame.Keepalive, sequence++), ByteArray(6))
        data.send(DatagramPacket(out, out.size, peer))
    }

    private fun loop(socket: DatagramSocket, onControlPort: Boolean) {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!socket.isClosed) {
            try {
                packet.setData(buffer, 0, buffer.size)
                socket.receive(packet)
            } catch (_: Exception) {
                return
            }
            val datagram = ReceivedDatagram(buffer.copyOf(packet.length), packet.socketAddress, onControlPort || profile == RistProfile.Main)
            datagrams.add(datagram)
            if (datagram.from !in peers && (profile == RistProfile.Main || onControlPort)) {
                peers.add(datagram.from)
                if (answering && profile == RistProfile.Main) {
                    keepalive(datagram.from)
                }
            }
            if (answering && (profile == RistProfile.Main || onControlPort)) {
                respond(datagram)
            }
        }
    }

    private fun respond(datagram: ReceivedDatagram) {
        val rtcpAt = if (profile == RistProfile.Simple) {
            0
        } else {
            val gre = parseGre(datagram.bytes, datagram.bytes.size) ?: return
            if (gre.frame != GreFrame.Data) {
                return
            }
            gre.payloadOffset
        }
        for (message in parseRtcp(datagram.bytes, rtcpAt, datagram.bytes.size - rtcpAt)) {
            if (message is EchoRequest) {
                if (echoDelayMs > 0) {
                    Thread.sleep(echoDelayMs)
                }
                val reply = ByteArray(8 + 24 + 20)
                var end = writeEmptyReceiverReport(reply, 0, ssrc)
                end = writeSourceDescription(reply, end, ssrc, "fake".toByteArray())
                end = writeEchoResponse(reply, end, message.ssrc, message.ntp)
                send(datagram.from, reply.copyOf(end))
            }
        }
    }
}
