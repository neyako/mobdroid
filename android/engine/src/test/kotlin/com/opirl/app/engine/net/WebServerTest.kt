package com.opirl.app.engine.net

import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

class WebServerTest {
    @Test
    fun aFrameOverTheMessageLimitEndsTheConnection() {
        val failures = CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> failures += error }
        val server = WebServer(0, object : WebServerHandler {}, "limit")
        server.start()
        try {
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 5000
                val output = socket.getOutputStream()
                val input = socket.getInputStream()
                output.write("GET / HTTP/1.1\r\nUpgrade: websocket\r\nSec-WebSocket-Key: a2V5\r\n\r\n".toByteArray())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                val reader = Thread.getAllStackTraces().keys.first { it.name == "limit-read" }
                output.write(byteArrayOf(0x01, 0x81.toByte(), 0, 0, 0, 0, 'a'.code.toByte()))
                output.write(byteArrayOf(0x00, 0xFF.toByte(), 0x7F, -1, -1, -1, -1, -1, -1, -1, 0, 0, 0, 0))
                output.flush()
                while (input.read() >= 0) Unit
                reader.join(5000)
            }
            assertEquals(emptyList(), failures.toList())
        } finally {
            server.stop()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun aStoppedServerStaysStopped() {
        val server = WebServer(0, object : WebServerHandler {})
        server.start()
        server.stop()
        server.start()
        assertEquals(0, server.localPort)
    }
}
