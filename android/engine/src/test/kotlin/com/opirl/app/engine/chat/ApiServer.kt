package com.opirl.app.engine.chat

import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient

internal class ApiServer : AutoCloseable {
    private val server = MockWebServer().apply { start() }
    val http = OkHttpClient()

    fun url(path: String = ""): String = server.url("/").toString().removeSuffix("/") + path

    fun reply(body: String = "{}", code: Int = 200) {
        server.enqueue(MockResponse.Builder().code(code).body(body).build())
    }

    fun take(): RecordedRequest = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))

    fun request(): String = take().let { request ->
        listOfNotNull("${request.method} ${request.target}", request.body?.utf8()?.takeIf { it.isNotEmpty() }).joinToString(" ")
    }

    override fun close() {
        server.close()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
