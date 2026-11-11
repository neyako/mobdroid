package app.mobdroid.engine.rtmp

import app.mobdroid.engine.flv.ByteWriter
import app.mobdroid.engine.flv.FOURCC_AV1
import app.mobdroid.engine.flv.FOURCC_HEVC
import app.mobdroid.engine.flv.FlvAudioTags
import app.mobdroid.engine.flv.FlvVideoTags
import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import app.mobdroid.engine.net.Publisher
import app.mobdroid.engine.net.PublisherListener
import app.mobdroid.engine.net.PublisherStats
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.withLock

private const val VIDEO_FORMAT_WAIT_MS = 2_000L
private const val OUTGOING_CHUNK_SIZE = 4096
private const val OUR_WINDOW_ACK_SIZE = 100_000L
private const val MAX_QUEUED_BYTES = 16L * 1024 * 1024
private const val STOP_GRACE_MS = 300L
private const val STOP_JOIN_MS = 1_500L
private const val HANDSHAKE_SIZE = 1536
private const val FLASH_VERSION = "FMLE/3.0 (compatible; FMSc/1.0)"
private val failureSuffixes = listOf(".Failed", ".Rejected", ".BadName", ".Denied", ".Closed")

private class RtmpException(message: String) : IOException(message)

private class Command(val name: String, val transaction: Int, val arguments: List<Any?>) {
    val info: Map<*, *>?
        get() = arguments.getOrNull(1) as? Map<*, *>

    val code: String?
        get() = info?.get("code") as? String
}

class RtmpPublisher(
    url: String,
    streamKey: String,
    private val listener: PublisherListener,
    private val sslSocketFactory: SSLSocketFactory? = null,
    maxBacklogMs: Long = 3_000,
    private val timeoutMs: Long = 10_000,
) : Publisher {
    private val target = RtmpUrl.parse(url, streamKey)
    private val queue = SendQueue(TimeUnit.MILLISECONDS.toNanos(maxBacklogMs), MAX_QUEUED_BYTES)
    private val rate = RateWindow()
    private val acks = AckTracker()
    private val bytesSent = AtomicLong()
    private val started = AtomicBoolean()
    private val finished = AtomicBoolean()
    private val formatLock = ReentrantLock()
    private val formatChanged = formatLock.newCondition()

    @Volatile private var stopRequested = false

    @Volatile private var pendingFailure: String? = null

    @Volatile private var rawSocket: Socket? = null

    @Volatile private var tlsSocket: Socket? = null

    @Volatile private var videoFormat: VideoFormat? = null

    @Volatile private var audioFormat: AudioFormat? = null

    @Volatile private var writeStartedAt = 0L

    @Volatile private var streaming = false

    @Volatile private var writerThread: Thread? = null

    @Volatile private var readerThread: Thread? = null
    private lateinit var input: BufferedInputStream
    private lateinit var output: OutputStream
    private lateinit var reader: RtmpChunkReader
    private val chunkWriter = RtmpChunkWriter()
    private val out = ByteWriter(1 shl 16)
    private val body = ByteWriter(1 shl 16)
    private var totalWritten = 0L
    private var nextTransaction = 1
    private var streamId = 0
    private var serverWindow = 0L
    private var lastAckSent = 0L
    private var baseUs = Long.MIN_VALUE
    private var videoStarted = false
    private var lastVideoMs = 0L
    private var lastAudioMs = 0L
    private var videoTags: FlvVideoTags? = null
    private var audioTags: FlvAudioTags? = null
    private var sentVideoFormat: VideoFormat? = null
    private var sentAudioFormat: AudioFormat? = null

    override fun setVideoFormat(format: VideoFormat) {
        formatLock.withLock {
            videoFormat = format
            formatChanged.signalAll()
        }
    }

    override fun setAudioFormat(format: AudioFormat) {
        audioFormat = format
    }

    override fun writeVideo(frame: EncodedFrame) {
        failIfSendStalled()
        if (frame.data.isNotEmpty()) {
            queue.addVideo(frame)
        }
    }

    override fun writeAudio(frame: EncodedFrame) {
        failIfSendStalled()
        if (frame.data.isNotEmpty()) {
            queue.addAudio(frame)
        }
    }

    override fun start() {
        check(started.compareAndSet(false, true)) { "RtmpPublisher is one-shot" }
        writerThread = Thread(::runWriter, "rtmp-writer").apply {
            isDaemon = true
            start()
        }
    }

    override fun stop() {
        if (stopRequested) {
            return
        }
        stopRequested = true
        queue.close()
        formatLock.withLock { formatChanged.signalAll() }
        val writer = writerThread
        if (writer != null && writer !== Thread.currentThread()) {
            writer.join(STOP_GRACE_MS)
            if (writer.isAlive) {
                closeSockets()
                writer.join(STOP_JOIN_MS)
            }
            readerThread?.takeIf { it !== Thread.currentThread() }?.join(STOP_JOIN_MS)
        }
        finished.set(true)
        closeSockets()
    }

    override fun stats(): PublisherStats =
        PublisherStats(
            bytesSent = bytesSent.get(),
            sendBitrateBps = rate.bitsPerSecond(),
            rttMs = acks.rttMs,
            queuedBytes = queue.queuedBytes,
            packetsInFlight = acks.packetsInFlight,
            droppedVideoFrames = queue.droppedVideoFrames,
        )

    private fun failIfSendStalled() {
        val startedAt = writeStartedAt
        if (startedAt != 0L && System.nanoTime() - startedAt > TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
            pendingFailure = "Send stalled for more than $timeoutMs ms"
            runCatching { rawSocket?.close() }
        }
    }

    private fun fail(reason: String) {
        if (finished.compareAndSet(false, true)) {
            queue.close()
            closeSockets()
            if (!stopRequested) {
                listener.onDisconnected(pendingFailure ?: reason)
            }
        }
    }

    private fun closeSockets() {
        runCatching { rawSocket?.close() }
        runCatching { tlsSocket?.close() }
    }

    private fun describe(error: Throwable): String =
        pendingFailure ?: when (error) {
            is RtmpException -> error.message ?: "RTMP error"
            is SocketTimeoutException -> "Timed out"
            is EOFException -> "Connection closed by server"
            is SSLException -> "TLS error: ${error.message}"
            else -> error.message ?: error.javaClass.simpleName
        }

    private fun runWriter() {
        try {
            openConnection()
            handshake()
            publish()
            if (stopRequested) {
                throw RtmpException("Stopped")
            }
            queue.trimToLatestKeyFrame()
            streaming = true
            listener.onConnected()
            readerThread = Thread(::runReader, "rtmp-reader").apply {
                isDaemon = true
                start()
            }
            pump()
            if (stopRequested) {
                goodbye()
            }
        } catch (error: Throwable) {
            fail(describe(error))
        } finally {
            closeSockets()
        }
    }

    private fun runReader() {
        try {
            while (true) {
                val command = process(reader.read()) ?: continue
                if (command.name == "onStatus") {
                    throwIfFailure(command)
                }
            }
        } catch (error: Throwable) {
            fail(describe(error))
        }
    }

    private fun openConnection() {
        var failure: IOException? = null
        var socket: Socket? = null
        for (address in InetAddress.getAllByName(target.host)) {
            val candidate = Socket()
            rawSocket = candidate
            if (stopRequested) {
                throw RtmpException("Stopped")
            }
            try {
                candidate.tcpNoDelay = true
                candidate.keepAlive = true
                candidate.connect(InetSocketAddress(address, target.port), timeoutMs.toInt())
                socket = candidate
                break
            } catch (error: IOException) {
                failure = error
                runCatching { candidate.close() }
            }
        }
        val connected = socket ?: throw failure ?: RtmpException("No address for ${target.host}")
        connected.soTimeout = timeoutMs.toInt()
        val active =
            if (target.secure) {
                val factory = sslSocketFactory ?: SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls = factory.createSocket(connected, target.host, target.port, true) as SSLSocket
                tlsSocket = tls
                tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                tls.startHandshake()
                tls
            } else {
                connected
            }
        input = BufferedInputStream(active.getInputStream(), 8192)
        output = active.getOutputStream()
    }

    private fun handshake() {
        val first = ByteArray(1 + HANDSHAKE_SIZE)
        first[0] = 3
        val random = ByteArray(HANDSHAKE_SIZE - 8)
        ThreadLocalRandom.current().nextBytes(random)
        System.arraycopy(random, 0, first, 9, random.size)
        write(first, 0, first.size)
        val server = readHandshake(1 + HANDSHAKE_SIZE)
        if (server[0].toInt() != 3) {
            throw RtmpException("Unsupported RTMP version ${server[0].toInt()}")
        }
        val second = ByteArray(HANDSHAKE_SIZE)
        System.arraycopy(server, 1, second, 0, HANDSHAKE_SIZE)
        write(second, 0, second.size)
        readHandshake(HANDSHAKE_SIZE)
        reader = RtmpChunkReader(input)
    }

    private fun readHandshake(count: Int): ByteArray {
        val data = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = input.read(data, done, count - done)
            if (n < 0) {
                throw EOFException("Connection closed by server")
            }
            done += n
        }
        return data
    }

    private fun publish() {
        val format = awaitVideoFormat()
        if (stopRequested) {
            throw RtmpException("Stopped")
        }
        val fourCc = format?.let { FlvVideoTags(it).fourCcName }
        val connect = LinkedHashMap<String, Any?>()
        connect["app"] = target.app
        connect["flashVer"] = FLASH_VERSION
        connect["tcUrl"] = target.tcUrl
        connect["fpad"] = false
        connect["capabilities"] = 239.0
        connect["audioCodecs"] = 1024.0
        connect["videoCodecs"] = 128.0
        connect["videoFunction"] = 1.0
        connect["objectEncoding"] = 0.0
        fourCc?.let { connect["fourCcList"] = listOf(it) }
        val connectTransaction = nextTransaction++
        sendCommand(0, "connect", connectTransaction, connect)
        val result = awaitResult(connectTransaction)
        if (result.code != "NetConnection.Connect.Success") {
            throw RtmpException(statusReason(result))
        }
        sendControl(MSG_SET_CHUNK_SIZE, uint32(OUTGOING_CHUNK_SIZE.toLong()))
        chunkWriter.chunkSize = OUTGOING_CHUNK_SIZE
        val key = target.streamKey
        sendCommand(0, "releaseStream", nextTransaction++, null, key)
        sendCommand(0, "FCPublish", nextTransaction++, null, key)
        val createTransaction = nextTransaction++
        sendCommand(0, "createStream", createTransaction, null)
        val created = awaitResult(createTransaction)
        streamId = (created.arguments.getOrNull(1) as? Double)?.toInt() ?: throw RtmpException("createStream failed")
        sendCommand(streamId, "publish", 0, null, key, "live")
        while (true) {
            val command = nextCommand()
            if (command.name != "onStatus") {
                continue
            }
            throwIfFailure(command)
            if (command.code == "NetStream.Publish.Start") {
                break
            }
        }
        rawSocket?.soTimeout = 0
    }

    private fun awaitVideoFormat(): VideoFormat? {
        formatLock.withLock {
            var remaining = TimeUnit.MILLISECONDS.toNanos(VIDEO_FORMAT_WAIT_MS)
            while (videoFormat == null && !stopRequested && remaining > 0) {
                remaining = formatChanged.awaitNanos(remaining)
            }
            return videoFormat
        }
    }

    private fun awaitResult(transaction: Int): Command {
        while (true) {
            val command = nextCommand()
            if (command.name == "onStatus") {
                throwIfFailure(command)
            } else if ((command.name == "_result" || command.name == "_error") && command.transaction == transaction) {
                if (command.name == "_error") {
                    throw RtmpException(statusReason(command))
                }
                return command
            }
        }
    }

    private fun nextCommand(): Command {
        while (true) {
            val command = process(reader.read())
            if (command != null) {
                return command
            }
        }
    }

    private fun throwIfFailure(command: Command) {
        val code = command.code ?: return
        val level = command.info?.get("level") as? String
        if (level == "error" || failureSuffixes.any { code.endsWith(it) }) {
            throw RtmpException(statusReason(command))
        }
    }

    private fun statusReason(command: Command): String {
        val code = command.code ?: command.name
        val description = command.info?.get("description") as? String
        return if (description.isNullOrEmpty()) code else "$code: $description"
    }

    private fun process(message: RtmpMessage): Command? {
        val data = message.body
        var command: Command? = null
        when (message.type) {
            MSG_SET_CHUNK_SIZE -> if (data.size >= 4) reader.chunkSize = (readUint32(data, 0) and 0x7FFFFFFF).toInt().coerceAtLeast(1)
            MSG_ABORT -> if (data.size >= 4) reader.discard(readUint32(data, 0).toInt())
            MSG_ACK -> if (data.size >= 4) acks.onAck(readUint32(data, 0))
            MSG_WINDOW_ACK_SIZE -> {
                if (data.size >= 4) {
                    serverWindow = readUint32(data, 0)
                    reply(MSG_WINDOW_ACK_SIZE, uint32(OUR_WINDOW_ACK_SIZE))
                }
            }
            MSG_USER_CONTROL -> {
                if (data.size >= 6 && data[1].toInt() == 6) {
                    reply(MSG_USER_CONTROL, byteArrayOf(0, 7, data[2], data[3], data[4], data[5]))
                }
            }
            MSG_COMMAND_AMF0, MSG_COMMAND_AMF3 -> command = parseCommand(data, if (message.type == MSG_COMMAND_AMF3) 1 else 0)
        }
        if (serverWindow > 0 && reader.bytesRead - lastAckSent >= serverWindow) {
            lastAckSent = reader.bytesRead
            reply(MSG_ACK, uint32(reader.bytesRead and 0xFFFFFFFFL))
        }
        return command
    }

    private fun parseCommand(data: ByteArray, offset: Int): Command? =
        try {
            val amf = Amf0.Reader(data, offset)
            val name = amf.read() as String
            val transaction = (amf.read() as Double).toInt()
            val arguments = ArrayList<Any?>()
            while (amf.hasMore) {
                arguments.add(amf.read())
            }
            Command(name, transaction, arguments)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: ClassCastException) {
            null
        }

    private fun reply(type: Int, payload: ByteArray) {
        if (streaming) {
            queue.postControl(type, payload)
        } else {
            sendControl(type, payload)
        }
    }

    private fun sendControl(type: Int, payload: ByteArray) {
        out.clear()
        chunkWriter.write(out, CSID_CONTROL, type, 0, 0, payload, 0, payload.size, false)
        flush()
    }

    private fun sendCommand(stream: Int, name: String, transaction: Int, vararg arguments: Any?) {
        body.clear()
        Amf0.writeCommand(body, name, transaction, *arguments)
        out.clear()
        chunkWriter.write(out, CSID_COMMAND, MSG_COMMAND_AMF0, stream, 0, body.array, 0, body.size, false)
        flush()
    }

    private fun write(data: ByteArray, offset: Int, length: Int) {
        writeStartedAt = System.nanoTime()
        output.write(data, offset, length)
        writeStartedAt = 0
        bytesSent.addAndGet(length.toLong())
        totalWritten += length
        acks.onWritten(totalWritten)
        rate.add(length.toLong())
    }

    private fun flush() {
        if (out.size > 0) {
            write(out.array, 0, out.size)
        }
    }

    private fun pump() {
        val taken = Taken()
        beginStream()
        while (queue.take(taken)) {
            out.clear()
            val frame = taken.frame
            if (frame == null) {
                val payload = taken.controlBody!!
                chunkWriter.write(out, CSID_CONTROL, taken.controlType, 0, 0, payload, 0, payload.size, false)
            } else if (taken.isVideo) {
                appendVideo(frame)
            } else {
                appendAudio(frame)
            }
            flush()
        }
    }

    private fun beginStream() {
        out.clear()
        body.clear()
        val video = videoFormat
        val audio = audioFormat
        val metadata = LinkedHashMap<String, Any?>()
        if (video != null) {
            metadata["width"] = video.width.toDouble()
            metadata["height"] = video.height.toDouble()
            metadata["framerate"] = video.fps.toDouble()
            metadata["videocodecid"] = videoCodecId(video)
        }
        if (audio != null) {
            metadata["audiocodecid"] = 10.0
            metadata["audiosamplerate"] = audio.sampleRate.toDouble()
            metadata["audiosamplesize"] = 16.0
            metadata["stereo"] = audio.channels > 1
        }
        Amf0.write(body, "@setDataFrame")
        Amf0.write(body, "onMetaData")
        Amf0.write(body, metadata)
        writeBody(CSID_DATA, MSG_DATA_AMF0, 0)
        if (video != null) {
            writeVideoSequenceHeader(video, 0)
        }
        if (audio != null) {
            writeAudioSequenceHeader(audio, 0)
        }
        flush()
    }

    private fun videoCodecId(format: VideoFormat): Double =
        when (format.codec) {
            VideoCodec.H264 -> 7.0
            VideoCodec.HEVC -> FOURCC_HEVC.toDouble()
            VideoCodec.AV1 -> FOURCC_AV1.toDouble()
        }

    private fun writeVideoSequenceHeader(format: VideoFormat, timestamp: Long) {
        val tags = FlvVideoTags(format)
        videoTags = tags
        sentVideoFormat = format
        body.clear()
        tags.writeSequenceHeader(body)
        writeBody(CSID_VIDEO, MSG_VIDEO, timestamp)
    }

    private fun writeAudioSequenceHeader(format: AudioFormat, timestamp: Long) {
        val tags = FlvAudioTags(format)
        audioTags = tags
        sentAudioFormat = format
        body.clear()
        tags.writeSequenceHeader(body)
        writeBody(CSID_AUDIO, MSG_AUDIO, timestamp)
    }

    private fun writeBody(csid: Int, type: Int, timestamp: Long) {
        chunkWriter.write(out, csid, type, streamId, timestamp, body.array, 0, body.size, csid != CSID_DATA)
    }

    private fun appendVideo(frame: EncodedFrame) {
        val format = videoFormat ?: return
        if (!videoStarted) {
            if (!frame.isKeyFrame) {
                return
            }
            videoStarted = true
        }
        if (baseUs == Long.MIN_VALUE) {
            baseUs = frame.ptsUs
        }
        val timestamp = maxOf((frame.ptsUs - baseUs) / 1000, lastVideoMs)
        lastVideoMs = timestamp
        if (sentVideoFormat !== format) {
            writeVideoSequenceHeader(format, timestamp)
        }
        body.clear()
        videoTags!!.writeFrame(body, frame)
        writeBody(CSID_VIDEO, MSG_VIDEO, timestamp)
    }

    private fun appendAudio(frame: EncodedFrame) {
        val format = audioFormat ?: return
        if (videoFormat != null && !videoStarted) {
            return
        }
        if (baseUs == Long.MIN_VALUE) {
            baseUs = frame.ptsUs
        }
        if (frame.ptsUs < baseUs) {
            return
        }
        val timestamp = maxOf((frame.ptsUs - baseUs) / 1000, lastAudioMs)
        lastAudioMs = timestamp
        if (sentAudioFormat !== format) {
            writeAudioSequenceHeader(format, timestamp)
        }
        body.clear()
        audioTags!!.writeFrame(body, frame)
        writeBody(CSID_AUDIO, MSG_AUDIO, timestamp)
    }

    private fun goodbye() {
        try {
            out.clear()
            body.clear()
            Amf0.writeCommand(body, "FCUnpublish", nextTransaction++, null, target.streamKey)
            chunkWriter.write(out, CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0, body.array, 0, body.size, false)
            body.clear()
            Amf0.writeCommand(body, "deleteStream", nextTransaction++, null, streamId.toDouble())
            chunkWriter.write(out, CSID_COMMAND, MSG_COMMAND_AMF0, 0, 0, body.array, 0, body.size, false)
            flush()
        } catch (_: IOException) {
        }
    }

    private fun uint32(value: Long): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun readUint32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)
}
