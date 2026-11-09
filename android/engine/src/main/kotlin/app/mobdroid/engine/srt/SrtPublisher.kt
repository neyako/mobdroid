package app.mobdroid.engine.srt

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoFormat
import app.mobdroid.engine.net.NetworkLinkProvider
import app.mobdroid.engine.net.Publisher
import app.mobdroid.engine.net.PublisherListener
import app.mobdroid.engine.net.PublisherStats
import app.mobdroid.engine.srtla.SrtlaClient
import app.mobdroid.engine.srtla.SrtlaLinkStats
import app.mobdroid.engine.srtla.SrtlaPriorities
import app.mobdroid.engine.ts.MpegTsMuxer
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

private const val DEFAULT_MAXIMUM_QUEUED_BYTES = 4L * 1024 * 1024
private const val STOP_TIMEOUT_MS = 1500L
private const val DRAIN_BATCH = 8

data class SrtOptions(val enhanced: Boolean = false, val bigPackets: Boolean = true)

data class SrtStats(
    val retransmittedPackets: Long,
    val receivedNaks: Long,
    val droppedPackets: Long,
    val packetsInFlight: Int,
    val queuedPackets: Int,
    val rttMs: Double,
    val mbpsSendRate: Double,
)

internal class SrtEnvironment(
    val muxerFactory: (output: (ByteArray, Int, Int) -> Unit) -> MediaSink = { MpegTsMuxer(it) },
    val transportFactory: ((SrtEngine, InetSocketAddress, SrtUrl) -> SrtTransport)? = null,
    val resolver: (String, Int) -> InetSocketAddress = ::resolveServer,
    val maximumQueuedBytes: Long = DEFAULT_MAXIMUM_QUEUED_BYTES,
)

internal fun resolveServer(host: String, port: Int): InetSocketAddress {
    val addresses = InetAddress.getAllByName(host)
    return InetSocketAddress(addresses.firstOrNull { it is Inet4Address } ?: addresses.first(), port)
}

class SrtPublisher internal constructor(
    private val url: String,
    private val listener: PublisherListener,
    private val links: NetworkLinkProvider?,
    private val options: SrtOptions,
    private val environment: SrtEnvironment,
) : Publisher, SrtSessionListener {
    constructor(
        url: String,
        listener: PublisherListener,
        links: NetworkLinkProvider? = null,
        options: SrtOptions = SrtOptions(),
    ) : this(url, listener, links, options, SrtEnvironment())

    private val queue = FrameQueue(environment.maximumQueuedBytes)
    private val engine = SrtEngine("srt-publisher") { failure ->
        fail("SRT internal error: ${failure.message ?: failure.javaClass.simpleName}")
    }
    private val started = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)
    private val drainTask = Runnable { drain() }
    private var muxer: MediaSink? = null
    private var inAudio = false

    @Volatile private var session: SrtSession? = null

    @Volatile private var priorities = SrtlaPriorities()

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
            session?.transport?.close()
        }
    }

    override fun stats(): PublisherStats {
        val counters = session?.counters()
        return PublisherStats(
            bytesSent = counters?.bytesSent ?: 0,
            sendBitrateBps = ((counters?.mbpsSendRate ?: 0.0) * 1_000_000).toLong(),
            rttMs = counters?.rttMs ?: 0.0,
            queuedBytes = queue.queuedBytes + (counters?.queuedBytes ?: 0),
            packetsInFlight = counters?.packetsInFlight ?: 0,
            retransmittedPackets = counters?.retransmittedPackets ?: 0,
            droppedVideoFrames = queue.droppedVideoFrames,
        )
    }

    fun srtStats(): SrtStats? {
        val counters = session?.counters() ?: return null
        return SrtStats(
            retransmittedPackets = counters.retransmittedPackets,
            receivedNaks = counters.receivedNaks,
            droppedPackets = counters.droppedPackets,
            packetsInFlight = counters.packetsInFlight,
            queuedPackets = counters.queuedPackets,
            rttMs = counters.rttMs,
            mbpsSendRate = counters.mbpsSendRate,
        )
    }

    fun linkStats(): List<SrtlaLinkStats> =
        (session?.transport as? SrtlaClient)?.linkStats() ?: emptyList()

    fun setLinkPriorities(newPriorities: SrtlaPriorities) {
        priorities = newPriorities
        engine.execute { (session?.transport as? SrtlaClient)?.setPriorities(newPriorities) }
    }

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

    override fun onSessionConnected() {
        queue.open()
        listener.onConnected()
    }

    override fun onSessionDisconnected(reason: String) {
        fail(reason)
    }

    private fun begin() {
        if (terminated.get()) {
            return
        }
        val target = try {
            SrtUrl.parse(url)
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid SRT URL")
            return
        }
        if (target.bonded && links == null) {
            fail("srtla:// needs network links")
            return
        }
        val config = try {
            SrtConfig(
                target.streamId,
                target.latencyMs,
                options.enhanced,
                if (options.bigPackets) SRT_DEFAULT_PAYLOAD_SIZE else SRT_DEFAULT_PAYLOAD_SIZE / 7 * 6,
            )
        } catch (error: IllegalArgumentException) {
            fail(error.message ?: "Invalid SRT settings")
            return
        }
        val address = try {
            environment.resolver(target.host, target.port)
        } catch (_: Exception) {
            fail("Failed to resolve ${target.host}")
            return
        }
        val transport = environment.transportFactory?.invoke(engine, address, target)
            ?: if (target.bonded) {
                SrtlaClient(engine, address, links!!, priorities)
            } else {
                UdpSrtTransport(engine, address)
            }
        val created = SrtSession(engine, transport, config, this)
        session = created
        muxer = environment.muxerFactory { buffer, offset, length ->
            created.write(buffer, offset, length, inAudio)
        }
        created.start()
        engine.execute(drainTask)
    }

    private fun drain() {
        val current = session ?: return
        val target = muxer ?: return
        if (terminated.get()) {
            return
        }
        for (count in 0 until DRAIN_BATCH) {
            when (val item = queue.poll() ?: return) {
                is VideoFormat -> target.setVideoFormat(item)
                is AudioFormat -> target.setAudioFormat(item)
                is VideoItem -> {
                    inAudio = false
                    target.writeVideo(item.frame)
                    current.flush()
                }
                is AudioItem -> {
                    inAudio = true
                    target.writeAudio(item.frame)
                    current.flush()
                }
            }
        }
        engine.execute(drainTask)
    }

    private fun teardown() {
        session?.stop()
        engine.shutdown()
    }

    private fun fail(reason: String) {
        if (!terminated.compareAndSet(false, true)) {
            return
        }
        queue.close()
        session?.stop()
        engine.shutdown()
        listener.onDisconnected(reason)
    }
}
