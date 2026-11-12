package com.opirl.app.engine.remote

import com.opirl.app.engine.srt.await
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val SCENE = "5B2C1F0E-9D3A-4C7B-8E1F-2A3B4C5D6E7F"

private class FakeTarget : RemoteControlTarget {
    val requests = CopyOnWriteArrayList<RemoteRequest>()

    @Volatile var live = false

    override fun status() = RemoteStatus(
        RemoteStatusGeneral(batteryLevel = 71, isLive = live),
        mapOf("stream" to RemoteStatusItem("My stream")),
        RemoteStatusTopRight(mapOf("bitrate" to RemoteStatusItem("5.0 Mbps")), audioLevel = -12f, bitsPerSecond = 5_000_000, totalBytes = 1000),
    )

    override fun settings() = RemoteSettings(
        scenes = listOf(RemoteNamed(SCENE, "Back")),
        bitratePresets = listOf(RemoteBitratePreset("b", 5_000_000)),
        zoomBack = listOf(RemoteZoomPreset("z", "1x", 1f)),
    )

    override fun state() = RemoteState(scene = SCENE, streaming = live, zoom = 1f)

    override fun handle(request: RemoteRequest): Boolean {
        requests += request
        if (request is RemoteRequest.SetLive) live = request.on
        return request !is RemoteRequest.SetMic
    }
}

private class Fixture(password: String = "secret") : AutoCloseable {
    val target = FakeTarget()
    val errors = CopyOnWriteArrayList<String>()
    val previewRequests = LinkedBlockingQueue<Boolean>()
    val http = OkHttpClient()
    val assistant = RemoteControlAssistant(0, "secret", { errors += it }, pollMs = 50).also {
        it.start()
        await(what = "the assistant listens") { it.state.value.listening }
    }
    val streamer = RemoteControlStreamer(
        http,
        "ws://127.0.0.1:${assistant.localPort}",
        password,
        "streamer",
        target,
        { previewRequests += it },
        retryMs = 100,
    ).also { it.start() }

    override fun close() {
        streamer.stop()
        assistant.stop()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

private class WebClient(http: OkHttpClient, port: Int) : WebSocketListener() {
    val messages = LinkedBlockingQueue<String>()
    val socket = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:$port").build(), this)

    override fun onMessage(webSocket: WebSocket, text: String) {
        messages += text
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        messages += "failure: $t"
    }

    fun next(): String = assertNotNull(messages.poll(5, TimeUnit.SECONDS))
}

class RemoteControlTest {
    @Test
    fun theAssistantSeesTheStreamerAndControlsIt() {
        Fixture().use { fixture ->
            await(what = "the streamer's state") { fixture.assistant.state.value.streamer.scene == SCENE }
            await(what = "the streamer's settings") { fixture.assistant.state.value.settings == fixture.target.settings() }
            assertTrue(fixture.assistant.state.value.connected)
            assertEquals(RemoteStreamerState.CONNECTED, fixture.streamer.state.value)

            fixture.assistant.setActive(true)
            assertEquals(true, fixture.previewRequests.poll(5, TimeUnit.SECONDS))
            await(what = "the whole status") { fixture.assistant.state.value.status == fixture.target.status() }
            fixture.streamer.sendPreview(byteArrayOf(1, 2, 3))
            await(what = "the preview") { fixture.assistant.preview.value != null }
            assertContentEquals(byteArrayOf(1, 2, 3), fixture.assistant.preview.value)

            fixture.assistant.request(RemoteRequest.SetLive(true))
            await(what = "the request arrives") { RemoteRequest.SetLive(true) in fixture.target.requests }
            fixture.streamer.stateChanged(RemoteState(streaming = true))
            await(what = "the state change") { fixture.assistant.state.value.streamer.streaming == true }
            assertEquals(SCENE, fixture.assistant.state.value.streamer.scene)

            fixture.assistant.request(RemoteRequest.SetMic("USB"))
            await(what = "the refusal") { fixture.errors.isNotEmpty() }

            fixture.assistant.setActive(false)
            assertEquals(false, fixture.previewRequests.poll(5, TimeUnit.SECONDS))
            fixture.streamer.stop()
            await(what = "the streamer leaves") { !fixture.assistant.state.value.connected }
        }
    }

    @Test
    fun aWrongPasswordIsRejected() {
        Fixture(password = "wrong").use { fixture ->
            await(what = "the streamer knows") { fixture.streamer.state.value == RemoteStreamerState.WRONG_PASSWORD }
            assertTrue(fixture.assistant.state.value.wrongPassword)
            assertTrue(!fixture.assistant.state.value.connected)
        }
    }

    @Test
    fun theWebRemoteServesItsPagesAndAnswersRequests() {
        val target = FakeTarget()
        val previews = LinkedBlockingQueue<Boolean>()
        val files = mapOf("index.html" to "<html>", "js/index.mjs" to "export {}")
        val web = RemoteControlWeb(0, target, { files[it]?.toByteArray() }, { previews += it })
        val http = OkHttpClient()
        try {
            web.start()
            await(what = "the web remote listens") { web.listening.value }
            val get = { path: String -> http.newCall(Request.Builder().url("http://127.0.0.1:${web.localPort}$path").build()).execute() }
            get("/").use { response ->
                assertEquals("text/html; charset=utf-8", response.header("Content-Type"))
                assertEquals("<html>", response.body.string())
            }
            get("/js/index.mjs").use { assertEquals("export {}", it.body.string()) }
            get("/../secret").use { assertEquals(404, it.code) }
            val port = get("/js/config.mjs").use { it.body.string() }.removePrefix("export const websocketPort = ").removeSuffix(";").toInt()

            val client = WebClient(http, port)
            assertIs<RemoteToAssistant.StateEvent>(decodeToAssistant(client.next()))
            client.socket.send("""{"request":{"id":2,"data":{"setLive":{"on":true}}}}""")
            assertEquals("""{"response":{"id":2,"result":{"ok":{}}}}""", client.next())
            assertTrue(target.live)
            client.socket.send("""{"request":{"id":3,"data":{"getStatus":{}}}}""")
            val status = assertIs<RemoteToAssistant.Response>(decodeToAssistant(client.next()))
            assertEquals(target.status(), status.status)
            client.socket.send("""{"request":{"id":4,"data":{"setFilter":{"filter":{"movie":{}},"on":true}}}}""")
            assertEquals("""{"response":{"id":4,"result":{"unknownRequest":{}}}}""", client.next())
            client.socket.send("""{"request":{"id":5,"data":{"startPreview":{}}}}""")
            assertEquals(true, previews.poll(5, TimeUnit.SECONDS))
            web.sendPreview(byteArrayOf(1, 2, 3))
            await(what = "the preview") { client.messages.any { it == """{"preview":{"preview":"AQID"}}""" } }
            client.socket.cancel()
            assertEquals(false, previews.poll(5, TimeUnit.SECONDS))
        } finally {
            web.stop()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun readsMoblinsJson() {
        val state = assertIs<RemoteToAssistant.StateEvent>(
            decodeToAssistant(
                """{"event":{"data":{"state":{"data":{"scene":"$SCENE","zoom":1.5,"streaming":true,""" +
                    """"zoomCamera":{"position":"back","minX":0.5,"maxX":15},"autoSceneSwitcher":{},"filters":[{"movie":{}},false]}}}}}""",
            ),
        ).state
        assertEquals(RemoteState(scene = SCENE, zoom = 1.5f, streaming = true, zoomCamera = RemoteZoomCamera("back", 0.5f, 15f)), state)
        val status = assertIs<RemoteToAssistant.StatusEvent>(
            decodeToAssistant(
                """{"event":{"data":{"status":{"topRight":{"audioLevel":{"message":"-12 dB","ok":true},""" +
                    """"audioInfo":{"audioLevel":{"value":{"_0":-12.5}},"numberOfAudioChannels":2},""" +
                    """"bondingInfo":[{"name":"Cellular","bytesPerSecond":1000,"rtt":40}]}}}}}""",
            ),
        ).status
        assertEquals(
            RemoteStatusTopRight(
                mapOf("audioLevel" to RemoteStatusItem("-12 dB")),
                audioLevel = -12.5f,
                bonding = listOf(RemoteBondingConnection("Cellular", 1000, 40)),
            ),
            status.topRight,
        )
        assertEquals(
            RemoteToStreamer.Request(7, RemoteRequest.SetZoomPreset(SCENE)),
            decodeToStreamer("""{"request":{"id":7,"data":{"setZoomPreset":{"id":"$SCENE"}}}}"""),
        )
        assertEquals(
            RemoteToStreamer.Request(8, RemoteRequest.Unknown("setGimbalMovement")),
            decodeToStreamer("""{"request":{"id":8,"data":{"setGimbalMovement":{"x":1,"y":0}}}}"""),
        )
        assertEquals(
            """{"identify":{"streamerId":"me","authentication":"hash"}}""",
            encode(RemoteToAssistant.Identify("me", "hash")),
        )
    }

    @Test
    fun aStateChangeCarriesOnlyWhatChanged() {
        val sent = RemoteState(scene = SCENE, streaming = false, zoom = 1f)
        assertEquals(RemoteState(streaming = true), sent.copy(streaming = true).changesFrom(sent))
        assertEquals(null, sent.changesFrom(sent))
        assertEquals(sent.copy(streaming = true), sent.merged(RemoteState(streaming = true)))
    }
}
