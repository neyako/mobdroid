package app.mobdroid.engine.obs

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

private const val EVENT_SUBSCRIPTIONS = 2 or 4 or 8 or 64 or 128
private const val AUTHENTICATION_FAILED = 4009
private val SPECIAL_INPUTS = listOf("desktop1", "desktop2", "mic1", "mic2", "mic3", "mic4")

enum class ObsOutput { STOPPED, STARTING, STARTED, STOPPING }

data class ObsInput(val name: String, val muted: Boolean)

data class ObsState(
    val connected: Boolean = false,
    val error: String? = null,
    val scenes: List<String> = emptyList(),
    val scene: String? = null,
    val stream: ObsOutput = ObsOutput.STOPPED,
    val streamStartedAtMs: Long = 0,
    val record: ObsOutput = ObsOutput.STOPPED,
    val recordStartedAtMs: Long = 0,
    val inputs: List<ObsInput> = emptyList(),
)

fun obsUrl(text: String): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val url = if ("://" in trimmed) trimmed else "ws://$trimmed"
    if (!url.startsWith("ws://") && !url.startsWith("wss://")) return null
    return url.takeIf { ("http" + it.removePrefix("ws")).toHttpUrlOrNull() != null }
}

fun obsAuthentication(password: String, salt: String, challenge: String): String {
    val hash = { text: String ->
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    }
    return hash(hash(password + salt) + challenge)
}

private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull

private fun JsonObject.obj(key: String) = get(key) as? JsonObject

private fun output(data: JsonObject): ObsOutput = when (data.string("outputState")) {
    "OBS_WEBSOCKET_OUTPUT_STARTING" -> ObsOutput.STARTING
    "OBS_WEBSOCKET_OUTPUT_STOPPING" -> ObsOutput.STOPPING
    "OBS_WEBSOCKET_OUTPUT_STOPPED" -> ObsOutput.STOPPED
    "OBS_WEBSOCKET_OUTPUT_STARTED" -> ObsOutput.STARTED
    else -> if (data.boolean("outputActive") == true) ObsOutput.STARTED else ObsOutput.STOPPED
}

class ObsClient(
    http: OkHttpClient,
    private val onError: (String) -> Unit,
    private val retryMs: Long = 5000,
    private val screenshotMs: Long = 2000,
    private val requestTimeoutMs: Long = 10_000,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val sockets = http.newBuilder().pingInterval(10, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow(ObsState())
    val state: StateFlow<ObsState> = _state.asStateFlow()
    private val _screenshot = MutableStateFlow<ByteArray?>(null)
    val screenshot: StateFlow<ByteArray?> = _screenshot.asStateFlow()
    private val lock = Any()
    private var run: Run? = null
    private var screenshots = false

    fun start(url: String, password: String) {
        synchronized(lock) {
            if (run?.url == url && run?.password == password) return
            stop()
            run = Run(url, password).also { it.post(it::connect) }
        }
    }

    fun stop() = synchronized(lock) {
        run?.close()
        run = null
        _state.value = ObsState()
        _screenshot.value = null
    }

    fun setScreenshots(enabled: Boolean) = synchronized(lock) {
        screenshots = enabled
        if (!enabled) _screenshot.value = null
        run?.let { it.post(if (enabled) it::shoot else it::stopShooting) }
    }

    fun elapsedMs(startedAtMs: Long): Long = clock() - startedAtMs

    fun isFor(url: String?, password: String): Boolean = synchronized(lock) { run?.url == url && run?.password == password }

    fun setScene(name: String) = command("SetCurrentProgramScene") { put("sceneName", name) }

    fun startStream() = command("StartStream")

    fun stopStream() = command("StopStream")

    fun startRecord() = command("StartRecord")

    fun stopRecord() = command("StopRecord")

    fun setMuted(input: String, muted: Boolean) = command("SetInputMute") {
        put("inputName", input)
        put("inputMuted", muted)
    }

    private fun command(type: String, data: (JsonObjectBuilder.() -> Unit)? = null) {
        val current = synchronized(lock) { run }
        if (current == null) {
            onError("OBS is not connected")
        } else {
            current.request(type, data?.let { buildJsonObject(it) }) { _, error -> if (error != null) onError(error) }
        }
    }

    private inner class Run(val url: String, val password: String) : WebSocketListener() {
        private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "obs").apply { isDaemon = true } }
        private val pending = ConcurrentHashMap<String, (JsonObject?, String?) -> Unit>()
        private val nextId = AtomicInteger()

        @Volatile private var closed = false

        @Volatile private var socket: WebSocket? = null

        @Volatile private var identified = false

        @Volatile private var changing = false
        private var inputsRefresh = 0
        private var refreshing = false
        private var shot = 0
        private var shotTimer: ScheduledFuture<*>? = null

        fun post(task: () -> Unit) {
            runCatching { executor.execute { if (!closed) task() } }
        }

        fun close() {
            closed = true
            executor.shutdownNow()
            socket?.close(1000, null)
        }

        fun connect() {
            val created = sockets.newWebSocket(Request.Builder().url(url).build(), this)
            socket = created
            if (closed) created.cancel()
        }

        override fun onMessage(webSocket: WebSocket, text: String) = post {
            if (webSocket === socket) runCatching { handle(text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            post { lost(webSocket, if (code == AUTHENTICATION_FAILED) "Wrong password" else reason.ifEmpty { "Connection closed" }) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = post {
            lost(webSocket, "Cannot reach OBS")
        }

        private fun lost(webSocket: WebSocket, reason: String) {
            if (webSocket !== socket) return
            socket = null
            identified = false
            changing = false
            pending.clear()
            refreshing = false
            stopShooting()
            update { ObsState(error = reason) }
            publish(null)
            executor.schedule({ post(::connect) }, retryMs, TimeUnit.MILLISECONDS)
        }

        private fun update(change: (ObsState) -> ObsState) = synchronized(lock) {
            if (run === this) _state.value = change(_state.value)
        }

        private fun publish(image: ByteArray?) = synchronized(lock) {
            if (run === this && (screenshots || image == null)) _screenshot.value = image
        }

        private fun handle(text: String) {
            val message = Json.parseToJsonElement(text).jsonObject
            val data = message.obj("d") ?: return
            when ((message["op"] as? JsonPrimitive)?.intOrNull) {
                0 -> socket?.send(
                    buildJsonObject {
                        put("op", 1)
                        putJsonObject("d") {
                            put("rpcVersion", 1)
                            put("eventSubscriptions", EVENT_SUBSCRIPTIONS)
                            data.obj("authentication")?.let {
                                put(
                                    "authentication",
                                    obsAuthentication(password, it.string("salt").orEmpty(), it.string("challenge").orEmpty()),
                                )
                            }
                        }
                    }.toString(),
                )
                2 -> {
                    identified = true
                    update { it.copy(connected = true, error = null) }
                    requestScenes()
                    requestOutput("GetStreamStatus") { state, output, startedAt ->
                        state.copy(stream = output, streamStartedAtMs = startedAt)
                    }
                    requestOutput("GetRecordStatus") { state, output, startedAt ->
                        state.copy(record = output, recordStartedAtMs = startedAt)
                    }
                }
                5 -> event(data.string("eventType"), data.obj("eventData") ?: JsonObject(emptyMap()))
                7 -> {
                    val callback = pending.remove(data.string("requestId")) ?: return
                    val status = data.obj("requestStatus")
                    if (status?.boolean("result") == true) {
                        callback(data.obj("responseData") ?: JsonObject(emptyMap()), null)
                    } else {
                        callback(
                            null,
                            status?.string("comment") ?: when (val code = (status?.get("code") as? JsonPrimitive)?.intOrNull) {
                                500 -> "Already running"
                                501 -> "Not running"
                                else -> "OBS error $code"
                            },
                        )
                    }
                }
            }
        }

        fun request(type: String, data: JsonObject? = null, onResult: (JsonObject?, String?) -> Unit) {
            val current = socket
            if (current == null || !identified || changing) {
                onResult(null, if (changing) "OBS is changing its scene collection" else "OBS is not connected")
                return
            }
            val id = nextId.incrementAndGet().toString()
            pending[id] = onResult
            val sent = current.send(
                buildJsonObject {
                    put("op", 6)
                    putJsonObject("d") {
                        put("requestType", type)
                        put("requestId", id)
                        if (data != null) put("requestData", data)
                    }
                }.toString(),
            )
            if (!sent) {
                pending.remove(id)?.invoke(null, "OBS is not connected")
                return
            }
            runCatching {
                executor.schedule(
                    { if (!closed) pending.remove(id)?.invoke(null, "OBS did not answer") },
                    requestTimeoutMs,
                    TimeUnit.MILLISECONDS,
                )
            }
        }

        private fun requestOutput(type: String, apply: (ObsState, ObsOutput, Long) -> ObsState) = request(type) { data, _ ->
            if (data != null) {
                val duration = (data["outputDuration"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: 0
                update { apply(it, output(data), clock() - duration) }
            }
        }

        private fun requestScenes() = request("GetSceneList") { data, _ ->
            if (data != null) {
                val scenes = (data["scenes"] as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("sceneName") }
                    .reversed()
                val scene = data.string("currentProgramSceneName")
                val changed = scene != _state.value.scene
                update { it.copy(scenes = scenes, scene = scene) }
                if (changed) sceneChanged()
            }
        }

        private fun sceneChanged() {
            refreshInputs()
            shoot()
        }

        private fun refreshInputs() {
            val refresh = ++inputsRefresh
            val scene = _state.value.scene
            refreshing = scene != null && !changing
            if (!refreshing) return
            request("GetSpecialInputs") { special, _ ->
                request("GetSceneItemList", buildJsonObject { put("sceneName", scene) }) { items, _ ->
                    val names = (
                        SPECIAL_INPUTS.mapNotNull { special?.string(it) } +
                            (items?.get("sceneItems") as? JsonArray).orEmpty()
                                .mapNotNull { it as? JsonObject }
                                .filter { it.boolean("sceneItemEnabled") == true }
                                .mapNotNull { it.string("sourceName") }
                        ).distinct()
                    val muted = arrayOfNulls<Boolean>(names.size)
                    var left = names.size
                    val done = {
                        if (refresh == inputsRefresh) {
                            refreshing = false
                            update { state ->
                                state.copy(inputs = names.indices.mapNotNull { i -> muted[i]?.let { ObsInput(names[i], it) } })
                            }
                        }
                    }
                    if (names.isEmpty()) done()
                    names.forEachIndexed { index, name ->
                        request("GetInputMute", buildJsonObject { put("inputName", name) }) { data, _ ->
                            muted[index] = data?.boolean("inputMuted")
                            if (--left == 0) done()
                        }
                    }
                }
            }
        }

        private fun event(type: String?, data: JsonObject) {
            when (type) {
                "CurrentProgramSceneChanged" -> {
                    val scene = data.string("sceneName") ?: return
                    if (scene != _state.value.scene) {
                        update { it.copy(scene = scene) }
                        sceneChanged()
                    }
                }
                "SceneListChanged", "SceneCreated", "SceneRemoved", "SceneNameChanged" -> requestScenes()
                "CurrentSceneCollectionChanging" -> {
                    changing = true
                    inputsRefresh++
                    refreshing = false
                    stopShooting()
                }
                "CurrentSceneCollectionChanged" -> {
                    changing = false
                    requestScenes()
                    refreshInputs()
                    shoot()
                }
                "StreamStateChanged" -> {
                    val next = output(data)
                    update {
                        it.copy(
                            stream = next,
                            streamStartedAtMs = if (next == ObsOutput.STARTED && it.stream != next) clock() else it.streamStartedAtMs,
                        )
                    }
                }
                "RecordStateChanged" -> {
                    val next = output(data)
                    update {
                        it.copy(
                            record = next,
                            recordStartedAtMs = if (next == ObsOutput.STARTED && it.record != next) clock() else it.recordStartedAtMs,
                        )
                    }
                }
                "InputMuteStateChanged" -> {
                    val name = data.string("inputName")
                    val muted = data.boolean("inputMuted") ?: return
                    update { state ->
                        state.copy(inputs = state.inputs.map { if (it.name == name) it.copy(muted = muted) else it })
                    }
                    if (refreshing) refreshInputs()
                }
                "InputCreated", "InputRemoved", "InputNameChanged", "SceneItemCreated", "SceneItemRemoved",
                "SceneItemEnableStateChanged",
                -> refreshInputs()
            }
        }

        fun stopShooting() {
            shot++
            shotTimer?.cancel(false)
        }

        fun shoot() {
            stopShooting()
            val current = shot
            val scene = _state.value.scene
            val wanted = synchronized(lock) { screenshots }
            if (!wanted || !identified || changing || scene == null) return
            val data = buildJsonObject {
                put("sourceName", scene)
                put("imageFormat", "jpg")
                put("imageWidth", 480)
                put("imageCompressionQuality", 60)
            }
            request("GetSourceScreenshot", data) { response, _ ->
                if (current != shot) return@request
                publish(
                    response?.string("imageData")?.substringAfter("base64,", "")?.takeIf { it.isNotEmpty() }?.let {
                        runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                    },
                )
                shotTimer = executor.schedule({ post(::shoot) }, screenshotMs, TimeUnit.MILLISECONDS)
            }
        }
    }
}
