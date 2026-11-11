package app.mobdroid.engine.obs

import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val SALT = "lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI="
private const val CHALLENGE = "+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="
private const val PASSWORD = "supersecretpassword"
private const val AUTHENTICATION = "1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4="
private val IMAGE = byteArrayOf(1, 2, 3, 4)

private class Sent(val type: String, val data: JsonObject?)

private class FakeObs : WebSocketListener() {
    val requests = CopyOnWriteArrayList<Sent>()
    val connections = AtomicInteger()
    val failing = CopyOnWriteArrayList<String>()
    val muted = ConcurrentHashMap(mapOf("Desktop Audio" to true))

    @Volatile var beforeResponse: (Sent) -> Unit = {}

    @Volatile var socket: WebSocket? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket = webSocket
        connections.incrementAndGet()
        webSocket.send(
            """{"op":0,"d":{"obsWebSocketVersion":"5.5.2","rpcVersion":1,"authentication":{"challenge":"$CHALLENGE","salt":"$SALT"}}}""",
        )
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val message = Json.parseToJsonElement(text).jsonObject
        val data = message.getValue("d").jsonObject
        when (message.getValue("op").jsonPrimitive.content) {
            "1" -> if (data["authentication"]?.jsonPrimitive?.content == AUTHENTICATION) {
                webSocket.send("""{"op":2,"d":{"negotiatedRpcVersion":1}}""")
            } else {
                webSocket.close(4009, "Authentication failed.")
            }
            "6" -> {
                val type = data.getValue("requestType").jsonPrimitive.content
                val id = data.getValue("requestId").jsonPrimitive.content
                val requestData = data["requestData"]?.jsonObject
                val sent = Sent(type, requestData)
                requests += sent
                beforeResponse(sent)
                val response = respond(type, requestData)
                val status = if (response == null) {
                    """{"result":false,"code":604,"comment":"Not possible"}"""
                } else {
                    """{"result":true,"code":100}"""
                }
                webSocket.send(
                    """{"op":7,"d":{"requestType":"$type","requestId":"$id","requestStatus":$status""" +
                        (if (response.isNullOrEmpty()) "" else ""","responseData":$response""") + "}}",
                )
            }
        }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    fun event(type: String, data: String) {
        socket!!.send("""{"op":5,"d":{"eventType":"$type","eventIntent":1,"eventData":$data}}""")
    }

    fun sent(type: String) = requests.filter { it.type == type }

    private fun respond(type: String, data: JsonObject?): String? = when {
        type in failing -> null
        type == "GetSceneList" ->
            """{"currentProgramSceneName":"Main","scenes":[{"sceneIndex":0,"sceneName":"BRB"},{"sceneIndex":1,"sceneName":"Main"}]}"""
        type == "GetStreamStatus" -> """{"outputActive":true,"outputReconnecting":false,"outputDuration":42000}"""
        type == "GetRecordStatus" -> """{"outputActive":false,"outputPaused":false,"outputDuration":0}"""
        type == "GetSpecialInputs" ->
            """{"desktop1":"Desktop Audio","desktop2":null,"mic1":"Mic/Aux","mic2":null,"mic3":null,"mic4":null}"""
        type == "GetSceneItemList" ->
            """{"sceneItems":[{"sourceName":"Camera","sceneItemEnabled":true},{"sourceName":"Logo","sceneItemEnabled":true},""" +
                """{"sourceName":"Music","sceneItemEnabled":false}]}"""
        type == "GetInputMute" -> data!!.getValue("inputName").jsonPrimitive.content.let { name ->
            if (name == "Logo") null else """{"inputMuted":${muted[name] == true}}"""
        }
        type == "GetSourceScreenshot" -> """{"imageData":"data:image/jpg;base64,${Base64.getEncoder().encodeToString(IMAGE)}"}"""
        else -> ""
    }
}

private class Harness(password: String = PASSWORD, screenshotMs: Long = 20) : AutoCloseable {
    private val server = MockWebServer()
    private val http = OkHttpClient()
    val obs = FakeObs()
    val errors = LinkedBlockingQueue<String>()
    val client = ObsClient(http, { errors += it }, retryMs = 50, screenshotMs = screenshotMs, clock = { 100_000 })

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().webSocketUpgrade(obs).build()
        }
        server.start()
        client.start("ws://${server.hostName}:${server.port}", password)
    }

    fun await(condition: (ObsState) -> Boolean): ObsState = runBlocking { withTimeout(10_000) { client.state.first(condition) } }

    fun awaitRequest(type: String, count: Int = 1): List<Sent> {
        val deadline = System.nanoTime() + 10_000_000_000
        while (obs.sent(type).size < count) {
            check(System.nanoTime() < deadline) { "no $type request" }
            Thread.sleep(5)
        }
        return obs.sent(type)
    }

    override fun close() {
        client.stop()
        server.close()
        http.dispatcher.executorService.shutdown()
    }
}

class ObsClientTest {
    @Test
    fun authenticationIsTheObsWebSocketHash() {
        assertEquals(AUTHENTICATION, obsAuthentication(PASSWORD, SALT, CHALLENGE))
    }

    @Test
    fun urlsAreNormalized() {
        assertEquals("ws://192.168.1.20:4455", obsUrl(" 192.168.1.20:4455 "))
        assertEquals("wss://obs.example.com/control", obsUrl("wss://obs.example.com/control"))
        assertNull(obsUrl("http://192.168.1.20:4455"))
        assertNull(obsUrl("ws://"))
        assertNull(obsUrl(""))
    }

    @Test
    fun readsScenesOutputsAndAudioInputsAfterIdentifying() = Harness().use { harness ->
        val state = harness.await { it.connected && it.inputs.isNotEmpty() && it.stream == ObsOutput.STARTED }
        assertEquals(listOf("Main", "BRB"), state.scenes)
        assertEquals("Main", state.scene)
        assertEquals(58_000, state.streamStartedAtMs)
        assertEquals(ObsOutput.STOPPED, state.record)
        assertEquals(
            listOf(ObsInput("Desktop Audio", true), ObsInput("Mic/Aux", false), ObsInput("Camera", false)),
            state.inputs,
        )
    }

    @Test
    fun wrongPasswordIsReportedAndRetried() = Harness("wrong").use { harness ->
        val state = harness.await { it.error != null }
        assertEquals("Wrong password", state.error)
        assertFalse(state.connected)
        val deadline = System.nanoTime() + 10_000_000_000
        while (harness.obs.connections.get() < 2) {
            check(System.nanoTime() < deadline) { "no retry" }
            Thread.sleep(5)
        }
    }

    @Test
    fun eventsUpdateTheState() = Harness().use { harness ->
        harness.await { it.connected && it.inputs.size == 3 }
        harness.obs.event("CurrentProgramSceneChanged", """{"sceneName":"BRB"}""")
        harness.obs.event("StreamStateChanged", """{"outputActive":false,"outputState":"OBS_WEBSOCKET_OUTPUT_STOPPING"}""")
        harness.obs.event("RecordStateChanged", """{"outputActive":true,"outputState":"OBS_WEBSOCKET_OUTPUT_STARTED"}""")
        harness.obs.event("InputMuteStateChanged", """{"inputName":"Mic/Aux","inputMuted":true}""")
        val state = harness.await { it.inputs.firstOrNull { input -> input.name == "Mic/Aux" }?.muted == true }
        assertEquals("BRB", state.scene)
        assertEquals(ObsOutput.STOPPING, state.stream)
        assertEquals(ObsOutput.STARTED, state.record)
        assertEquals(100_000, state.recordStartedAtMs)
        val lists = harness.awaitRequest("GetSceneItemList", 2)
        assertEquals("BRB", lists.last().data!!.getValue("sceneName").jsonPrimitive.content)
    }

    @Test
    fun commandsAreSentAndFailuresReported() = Harness().use { harness ->
        harness.await { it.connected }
        harness.obs.failing += "StartStream"
        harness.client.setScene("BRB")
        harness.client.setMuted("Mic/Aux", true)
        harness.client.startStream()
        assertEquals("Not possible", harness.errors.poll(10, TimeUnit.SECONDS))
        assertEquals("BRB", harness.obs.sent("SetCurrentProgramScene").single().data!!.getValue("sceneName").jsonPrimitive.content)
        val mute = harness.obs.sent("SetInputMute").single().data!!
        assertEquals("Mic/Aux", mute.getValue("inputName").jsonPrimitive.content)
        assertEquals("true", mute.getValue("inputMuted").jsonPrimitive.content)
    }

    @Test
    fun aCommandSentJustBeforeStopStillReachesObs() = Harness().use { harness ->
        harness.await { it.connected }
        harness.client.stopStream()
        harness.client.stop()
        assertEquals(1, harness.awaitRequest("StopStream").size)
    }

    @Test
    fun screenshotsAreFetchedOnlyWhileWanted() = Harness().use { harness ->
        harness.await { it.connected && it.inputs.size == 3 }
        assertTrue(harness.obs.sent("GetSourceScreenshot").isEmpty())
        harness.client.setScreenshots(true)
        val image = runBlocking { withTimeout(10_000) { harness.client.screenshot.first { it != null } } }
        assertContentEquals(IMAGE, image)
        assertEquals("Main", harness.obs.sent("GetSourceScreenshot").first().data!!.getValue("sourceName").jsonPrimitive.content)
        harness.awaitRequest("GetSourceScreenshot", 3)
        harness.client.setScreenshots(false)
        assertNull(harness.client.screenshot.value)
        Thread.sleep(100)
        val count = harness.obs.sent("GetSourceScreenshot").size
        Thread.sleep(150)
        assertEquals(count, harness.obs.sent("GetSourceScreenshot").size)
        assertNull(harness.client.screenshot.value)
    }

    @Test
    fun showingThePanelAgainKeepsOneScreenshotTimer() = Harness(screenshotMs = 200).use { harness ->
        harness.await { it.connected && it.inputs.size == 3 }
        repeat(5) {
            harness.client.setScreenshots(true)
            harness.awaitRequest("GetSourceScreenshot", it + 1)
            harness.client.setScreenshots(false)
        }
        harness.client.setScreenshots(true)
        Thread.sleep(1100)
        val count = harness.obs.sent("GetSourceScreenshot").size
        assertTrue(count in 8..13, "$count screenshot requests")
    }

    @Test
    fun aMuteEventDuringAnInputRefreshIsKept() = Harness().use { harness ->
        harness.await { it.connected && it.inputs.size == 3 }
        harness.obs.beforeResponse = { sent ->
            if (sent.type == "GetInputMute" && sent.data!!.getValue("inputName").jsonPrimitive.content == "Camera") {
                harness.obs.beforeResponse = {}
                harness.obs.muted["Mic/Aux"] = true
                harness.obs.event("InputMuteStateChanged", """{"inputName":"Mic/Aux","inputMuted":true}""")
            }
        }
        harness.obs.event("InputCreated", """{"inputName":"New"}""")
        harness.awaitRequest("GetSpecialInputs", 3)
        harness.awaitRequest("GetInputMute", 12)
        Thread.sleep(100)
        assertEquals(ObsInput("Mic/Aux", true), harness.client.state.value.inputs[1])
    }

    @Test
    fun nothingIsRequestedWhileTheSceneCollectionChanges() = Harness().use { harness ->
        harness.await { it.connected && it.inputs.size == 3 }
        harness.client.setScreenshots(true)
        harness.awaitRequest("GetSourceScreenshot")
        harness.obs.event("CurrentSceneCollectionChanging", """{"sceneCollectionName":"Other"}""")
        Thread.sleep(100)
        val before = harness.obs.requests.size
        harness.obs.event("InputRemoved", """{"inputName":"Camera"}""")
        harness.client.setScene("BRB")
        assertEquals("OBS is changing its scene collection", harness.errors.poll(10, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertEquals(before, harness.obs.requests.size)
        harness.obs.event("CurrentSceneCollectionChanged", """{"sceneCollectionName":"Other"}""")
        harness.awaitRequest("GetSceneList", 2)
        val shots = harness.obs.sent("GetSourceScreenshot").size
        assertTrue(harness.awaitRequest("GetSourceScreenshot", shots + 1).size > shots)
    }

    @Test
    fun reconnectsAfterTheConnectionDropsAndStopsOnStop() = Harness().use { harness ->
        harness.await { it.connected }
        harness.obs.socket!!.close(1001, "Server stopping")
        val lost = harness.await { !it.connected }
        assertEquals("Server stopping", lost.error)
        harness.await { it.connected }
        assertEquals(2, harness.obs.connections.get())
        harness.client.stop()
        assertEquals(ObsState(), harness.client.state.value)
        Thread.sleep(200)
        assertEquals(2, harness.obs.connections.get())
    }
}
