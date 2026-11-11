package app.mobdroid.engine.rtmp

import app.mobdroid.engine.flv.ByteWriter
import java.io.BufferedInputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import javax.net.ssl.SSLContext

internal class ReceivedCommand(val name: String, val transaction: Int, val streamId: Int, val arguments: List<Any?>)

internal class ReceivedMedia(val type: Int, val streamId: Int, val timestamp: Long, val body: ByteArray)

internal class Behavior(
    val connectCode: String = "NetConnection.Connect.Success",
    val connectAsError: Boolean = false,
    val publishCode: String = "NetStream.Publish.Start",
    val publishLevel: String = "status",
    val serverWindowAckSize: Long = 2_500_000,
    val ackEveryBytes: Long = 0,
    val pingAfterPublish: Boolean = false,
    val answerConnect: Boolean = true,
    val closeAfterPublishStart: Boolean = false,
    val readGate: CountDownLatch? = null,
    val recordMedia: Boolean = true,
)

internal class FakeRtmpServer(sslContext: SSLContext? = null, private val behavior: Behavior = Behavior()) : AutoCloseable {
    private val serverSocket: ServerSocket =
        sslContext?.serverSocketFactory?.createServerSocket(0) ?: ServerSocket(0)
    private val writeLock = Any()
    private val chunkWriter = RtmpChunkWriter()
    private val out = ByteWriter()

    @Volatile private var socket: Socket? = null

    private val thread = Thread(::serve, "fake-rtmp-server").apply { isDaemon = true }

    val port: Int get() = serverSocket.localPort
    val commands = CopyOnWriteArrayList<ReceivedCommand>()
    val media = CopyOnWriteArrayList<ReceivedMedia>()
    val controls = CopyOnWriteArrayList<RtmpMessage>()
    val mediaCount = java.util.concurrent.atomic.AtomicLong()
    val timestampRegressions = java.util.concurrent.atomic.AtomicLong()
    val lastTimestamps = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    val published = CountDownLatch(1)
    val accepted = CountDownLatch(1)

    fun start(): FakeRtmpServer {
        thread.start()
        return this
    }

    fun commandNames(): List<String> = commands.map { it.name }

    fun mediaOfType(type: Int): List<ReceivedMedia> = media.filter { it.type == type }

    fun sendStatus(code: String, level: String = "error") {
        send(MSG_COMMAND_AMF0, 1) { Amf0.writeCommand(it, "onStatus", 0, null, linkedMapOf("level" to level, "code" to code, "description" to "test")) }
    }

    fun dropConnection() {
        runCatching { socket?.close() }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { serverSocket.close() }
        thread.join(2000)
    }

    private fun send(type: Int, streamId: Int, build: (ByteWriter) -> Unit) {
        val body = ByteWriter()
        build(body)
        synchronized(writeLock) {
            out.clear()
            chunkWriter.write(out, if (type == MSG_COMMAND_AMF0) CSID_COMMAND else CSID_CONTROL, type, streamId, 0, body.array, 0, body.size, false)
            socket?.getOutputStream()?.write(out.array, 0, out.size)
        }
    }

    private fun sendRaw(type: Int, payload: ByteArray) = send(type, 0) { it.put(payload) }

    private fun u32(value: Long) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun serve() {
        try {
            val connection = serverSocket.accept()
            socket = connection
            accepted.countDown()
            val input = BufferedInputStream(connection.getInputStream())
            val first = ByteArray(1 + 1536)
            readFully(input, first)
            val reply = ByteArray(1 + 1536 + 1536)
            reply[0] = 3
            System.arraycopy(first, 1, reply, 1 + 1536, 1536)
            connection.getOutputStream().write(reply)
            readFully(input, ByteArray(1536))
            val reader = RtmpChunkReader(input)
            var lastAck = 0L
            while (true) {
                val message = reader.read()
                handle(message, reader)
                if (behavior.ackEveryBytes > 0 && reader.bytesRead - lastAck >= behavior.ackEveryBytes) {
                    lastAck = reader.bytesRead
                    sendRaw(MSG_ACK, u32(reader.bytesRead + 3073))
                }
            }
        } catch (_: IOException) {
        }
    }

    private fun readFully(input: java.io.InputStream, target: ByteArray) {
        var done = 0
        while (done < target.size) {
            val n = input.read(target, done, target.size - done)
            if (n < 0) throw IOException("eof")
            done += n
        }
    }

    private fun handle(message: RtmpMessage, reader: RtmpChunkReader) {
        when (message.type) {
            MSG_COMMAND_AMF0 -> handleCommand(message)
            MSG_SET_CHUNK_SIZE -> {
                controls.add(message)
                reader.chunkSize = ((message.body[0].toInt() and 0xFF) shl 24) or ((message.body[1].toInt() and 0xFF) shl 16) or
                    ((message.body[2].toInt() and 0xFF) shl 8) or (message.body[3].toInt() and 0xFF)
            }
            MSG_AUDIO, MSG_VIDEO, MSG_DATA_AMF0 -> {
                mediaCount.incrementAndGet()
                val previous = lastTimestamps.put(message.type, message.timestamp)
                if (previous != null && message.timestamp < previous) {
                    timestampRegressions.incrementAndGet()
                }
                if (behavior.recordMedia) {
                    media.add(ReceivedMedia(message.type, message.streamId, message.timestamp, message.body))
                }
            }
            else -> controls.add(message)
        }
    }

    private fun handleCommand(message: RtmpMessage) {
        val amf = Amf0.Reader(message.body)
        val name = amf.read() as String
        val transaction = (amf.read() as Double).toInt()
        val arguments = ArrayList<Any?>()
        while (amf.hasMore) arguments.add(amf.read())
        commands.add(ReceivedCommand(name, transaction, message.streamId, arguments))
        when (name) {
            "connect" -> {
                if (!behavior.answerConnect) return
                sendRaw(MSG_WINDOW_ACK_SIZE, u32(behavior.serverWindowAckSize))
                sendRaw(MSG_SET_PEER_BANDWIDTH, u32(behavior.serverWindowAckSize) + byteArrayOf(2))
                if (behavior.connectAsError) {
                    send(MSG_COMMAND_AMF0, 0) {
                        Amf0.writeCommand(it, "_error", transaction, null, linkedMapOf("level" to "error", "code" to behavior.connectCode, "description" to "rejected"))
                    }
                } else {
                    send(MSG_COMMAND_AMF0, 0) {
                        Amf0.writeCommand(
                            it, "_result", transaction, linkedMapOf("fmsVer" to "FMS/3,0,1,123", "capabilities" to 31.0),
                            linkedMapOf("level" to "status", "code" to behavior.connectCode, "description" to "Connection succeeded.", "objectEncoding" to 0.0),
                        )
                    }
                }
            }
            "releaseStream", "FCPublish" -> send(MSG_COMMAND_AMF0, 0) { Amf0.writeCommand(it, "_result", transaction, null) }
            "createStream" -> send(MSG_COMMAND_AMF0, 0) { Amf0.writeCommand(it, "_result", transaction, null, 1.0) }
            "publish" -> {
                send(MSG_COMMAND_AMF0, 1) {
                    Amf0.writeCommand(
                        it, "onStatus", 0, null,
                        linkedMapOf("level" to behavior.publishLevel, "code" to behavior.publishCode, "description" to "Started publishing"),
                    )
                }
                if (behavior.pingAfterPublish) {
                    sendRaw(MSG_USER_CONTROL, byteArrayOf(0, 6, 1, 2, 3, 4))
                }
                published.countDown()
                if (behavior.closeAfterPublishStart) {
                    dropConnection()
                }
                behavior.readGate?.await()
            }
        }
    }
}
