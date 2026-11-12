package com.opirl.app.engine.net

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

private const val MAXIMUM_HEADER_BYTES = 16 * 1024
private const val MAXIMUM_MESSAGE_BYTES = 8 * 1024 * 1024
private const val MAXIMUM_CONNECTIONS = 32
private const val MAXIMUM_QUEUED_FRAMES = 128
private const val HEADER_TIMEOUT_MS = 10_000
private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private const val OPCODE_CONTINUATION = 0
private const val OPCODE_TEXT = 1
private const val OPCODE_CLOSE = 8
private const val OPCODE_PING = 9
private const val OPCODE_PONG = 10
private val POISON = ByteArray(0)

class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

class HttpResponse(
    val status: Int,
    val contentType: String = "text/plain",
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
)

interface WebSocketConnection {
    val remoteAddress: InetAddress
    val localAddress: InetAddress

    fun send(text: String): Boolean

    fun ping()

    fun close()
}

interface WebServerHandler {
    fun onHttp(request: HttpRequest): HttpResponse? = null

    fun onOpen(connection: WebSocketConnection) {}

    fun onText(connection: WebSocketConnection, text: String) {}

    fun onPong(connection: WebSocketConnection) {}

    fun onClosed(connection: WebSocketConnection) {}
}

fun webSocketAccept(key: String): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + WEBSOCKET_GUID).toByteArray()))

fun webSocketFrame(opcode: Int, payload: ByteArray): ByteArray {
    val header = when {
        payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
        payload.size < 65536 -> byteArrayOf((0x80 or opcode).toByte(), 126, (payload.size shr 8).toByte(), payload.size.toByte())
        else -> ByteArray(10).also {
            it[0] = (0x80 or opcode).toByte()
            it[1] = 127
            for (index in 0 until 8) it[2 + index] = (payload.size.toLong() shr (56 - 8 * index)).toByte()
        }
    }
    return header + payload
}

class WebServer(private val port: Int, private val handler: WebServerHandler, private val name: String = "web") {
    private var server: ServerSocket? = null
    private var stopped = false
    private val connections = ConcurrentHashMap.newKeySet<Connection>()

    val localPort: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (stopped || server != null) return
        val created = ServerSocket()
        try {
            created.reuseAddress = true
            created.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            created.close()
            throw e
        }
        server = created
        thread("$name-accept") { accept(created) }
    }

    @Synchronized
    fun stop() {
        stopped = true
        val current = server ?: return
        server = null
        runCatching { current.close() }
        connections.forEach { it.terminate() }
    }

    private fun thread(name: String, block: () -> Unit) = Thread(block, name).apply {
        isDaemon = true
        start()
    }

    private fun accept(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                if (server.isClosed) return
                continue
            }
            if (connections.size >= MAXIMUM_CONNECTIONS) {
                runCatching { socket.close() }
                continue
            }
            val connection = Connection(socket)
            connections += connection
            if (server.isClosed) connection.terminate()
            thread("$name-read") { connection.run() }
        }
    }

    private inner class Connection(private val socket: Socket) : WebSocketConnection {
        private val queue = LinkedBlockingQueue<ByteArray>(MAXIMUM_QUEUED_FRAMES)

        @Volatile private var open = false

        @Volatile private var closing = false

        @Volatile private var closed = false

        override val remoteAddress: InetAddress get() = socket.inetAddress
        override val localAddress: InetAddress get() = socket.localAddress

        override fun send(text: String): Boolean = enqueue(webSocketFrame(OPCODE_TEXT, text.toByteArray()))

        override fun ping() {
            enqueue(webSocketFrame(OPCODE_PING, ByteArray(0)))
        }

        override fun close() {
            if (closed) return
            if (closing || !open || !queue.offer(POISON)) terminate() else closing = true
        }

        fun terminate() {
            if (closed) return
            closed = true
            queue.clear()
            queue.offer(POISON)
            runCatching { socket.close() }
        }

        private fun enqueue(frame: ByteArray): Boolean {
            if (closed || closing || !open) return false
            if (queue.offer(frame)) return true
            terminate()
            return false
        }

        fun run() {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = HEADER_TIMEOUT_MS
                val input = socket.getInputStream().buffered()
                val request = readRequest(input) ?: return
                val key = request.headers["sec-websocket-key"]
                if (key != null && request.headers["upgrade"].equals("websocket", ignoreCase = true)) {
                    socket.soTimeout = 0
                    socket.getOutputStream().write(
                        (
                            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                                "Sec-WebSocket-Accept: ${webSocketAccept(key)}\r\n\r\n"
                            ).toByteArray(),
                    )
                    open = true
                    thread("$name-write") { write() }
                    handler.onOpen(this)
                    readFrames(DataInputStream(input))
                } else {
                    respond(request)
                }
            } catch (_: IOException) {
            } catch (_: IllegalArgumentException) {
            } finally {
                val wasOpen = open
                terminate()
                connections -= this
                if (wasOpen) handler.onClosed(this)
            }
        }

        private fun respond(request: HttpRequest) {
            val response = if (request.method == "GET" || request.method == "DELETE") {
                handler.onHttp(request) ?: HttpResponse(404, body = "Not found".toByteArray())
            } else {
                HttpResponse(405)
            }
            val reason = when (response.status) {
                200 -> "OK"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                else -> "Error"
            }
            val head = StringBuilder("HTTP/1.1 ${response.status} $reason\r\n")
                .append("Content-Type: ${response.contentType}\r\n")
                .append("Content-Length: ${response.body.size}\r\n")
                .append("Connection: close\r\n")
            response.headers.forEach { (name, value) -> head.append("$name: $value\r\n") }
            head.append("\r\n")
            val output = socket.getOutputStream()
            output.write(head.toString().toByteArray())
            output.write(response.body)
            output.flush()
        }

        private fun write() {
            try {
                val output = socket.getOutputStream()
                while (true) {
                    val frame = queue.take()
                    if (frame === POISON) break
                    output.write(frame)
                    output.flush()
                }
            } catch (_: IOException) {
            } catch (_: InterruptedException) {
            }
            terminate()
        }

        private fun readFrames(input: DataInputStream) {
            val message = ByteArrayOutputStream()
            var text = false
            while (true) {
                val first = input.readUnsignedByte()
                val second = input.readUnsignedByte()
                val opcode = first and 0x0F
                var length = (second and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
                if (second and 0x80 == 0 || length < 0 || length > MAXIMUM_MESSAGE_BYTES - message.size()) return
                val mask = ByteArray(4).also(input::readFully)
                val payload = ByteArray(length.toInt()).also(input::readFully)
                for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
                when (opcode) {
                    OPCODE_CLOSE -> return
                    OPCODE_PING -> enqueue(webSocketFrame(OPCODE_PONG, payload))
                    OPCODE_PONG -> handler.onPong(this)
                    else -> {
                        if (opcode != OPCODE_CONTINUATION) {
                            message.reset()
                            text = opcode == OPCODE_TEXT
                        }
                        message.write(payload)
                        if (first and 0x80 != 0) {
                            if (text) handler.onText(this, String(message.toByteArray(), Charsets.UTF_8))
                            message.reset()
                        }
                    }
                }
            }
        }
    }
}

private fun readRequest(input: InputStream): HttpRequest? {
    val head = ByteArrayOutputStream()
    var matched = 0
    while (matched < 4) {
        val byte = input.read()
        if (byte < 0) throw EOFException()
        if (head.size() >= MAXIMUM_HEADER_BYTES) return null
        head.write(byte)
        matched = if (byte == (if (matched % 2 == 0) '\r' else '\n').code) matched + 1 else if (byte == '\r'.code) 1 else 0
    }
    val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
    val parts = lines.first().split(' ')
    if (parts.size < 2) return null
    val target = parts[1]
    val path = URLDecoder.decode(target.substringBefore('?'), "UTF-8")
    val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }
    val headers = lines.drop(1).filter { ':' in it }.associate {
        it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
    }
    return HttpRequest(parts[0], path, query, headers)
}
