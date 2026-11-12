package com.opirl.app.engine.remote

import com.opirl.app.engine.json.EMPTY_OBJECT
import com.opirl.app.engine.json.array
import com.opirl.app.engine.json.boolean
import com.opirl.app.engine.json.case
import com.opirl.app.engine.json.double
import com.opirl.app.engine.json.int
import com.opirl.app.engine.json.long
import com.opirl.app.engine.json.obj
import com.opirl.app.engine.json.single
import com.opirl.app.engine.json.string
import com.opirl.app.engine.moblink.REMOTE_API_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

const val REMOTE_ASSISTANT_DEFAULT_PORT = 2345
const val REMOTE_WEB_DEFAULT_PORT = 8080
const val REMOTE_RELAY_DEFAULT_URL = "wss://moblin.mys-lang.org/moblin-remote-control-relay"

data class RemoteStatusItem(val message: String, val ok: Boolean = true)

data class RemoteStatusGeneral(
    val batteryCharging: Boolean? = null,
    val batteryLevel: Int? = null,
    val flame: String? = null,
    val wiFiSsid: String? = null,
    val isLive: Boolean? = null,
    val isRecording: Boolean? = null,
    val isMuted: Boolean? = null,
)

data class RemoteBondingConnection(val name: String, val bytesPerSecond: Long, val rtt: Int? = null)

data class RemoteStatusTopRight(
    val items: Map<String, RemoteStatusItem> = emptyMap(),
    val audioLevel: Float? = null,
    val muted: Boolean = false,
    val bitsPerSecond: Long? = null,
    val totalBytes: Long? = null,
    val bonding: List<RemoteBondingConnection>? = null,
)

data class RemoteStatus(
    val general: RemoteStatusGeneral? = null,
    val topLeft: Map<String, RemoteStatusItem>? = null,
    val topRight: RemoteStatusTopRight? = null,
) {
    fun merged(update: RemoteStatus) = RemoteStatus(update.general ?: general, update.topLeft ?: topLeft, update.topRight ?: topRight)
}

data class RemoteNamed(val id: String, val name: String)

data class RemoteBitratePreset(val id: String, val bitrate: Long)

data class RemoteZoomPreset(val id: String, val name: String, val x: Float)

data class RemoteSettings(
    val streams: List<RemoteNamed> = emptyList(),
    val scenes: List<RemoteNamed> = emptyList(),
    val bitratePresets: List<RemoteBitratePreset> = emptyList(),
    val mics: List<RemoteNamed> = emptyList(),
    val zoomFront: List<RemoteZoomPreset> = emptyList(),
    val zoomBack: List<RemoteZoomPreset> = emptyList(),
)

data class RemoteZoomCamera(val position: String, val minX: Float, val maxX: Float)

data class RemoteState(
    val scene: String? = null,
    val mic: String? = null,
    val bitrate: String? = null,
    val zoom: Float? = null,
    val zoomPreset: String? = null,
    val zoomCamera: RemoteZoomCamera? = null,
    val debugLogging: Boolean? = null,
    val streaming: Boolean? = null,
    val recording: Boolean? = null,
    val muted: Boolean? = null,
    val stealthMode: Boolean? = null,
    val torchOn: Boolean? = null,
    val batteryCharging: Boolean? = null,
) {
    fun changesFrom(sent: RemoteState): RemoteState? = RemoteState(
        scene.takeIf { it != sent.scene },
        mic.takeIf { it != sent.mic },
        bitrate.takeIf { it != sent.bitrate },
        zoom.takeIf { it != sent.zoom },
        zoomPreset.takeIf { it != sent.zoomPreset },
        zoomCamera.takeIf { it != sent.zoomCamera },
        debugLogging.takeIf { it != sent.debugLogging },
        streaming.takeIf { it != sent.streaming },
        recording.takeIf { it != sent.recording },
        muted.takeIf { it != sent.muted },
        stealthMode.takeIf { it != sent.stealthMode },
        torchOn.takeIf { it != sent.torchOn },
        batteryCharging.takeIf { it != sent.batteryCharging },
    ).takeIf { it != RemoteState() }

    fun merged(changes: RemoteState) = RemoteState(
        changes.scene ?: scene,
        changes.mic ?: mic,
        changes.bitrate ?: bitrate,
        changes.zoom ?: zoom,
        changes.zoomPreset ?: zoomPreset,
        changes.zoomCamera ?: zoomCamera,
        changes.debugLogging ?: debugLogging,
        changes.streaming ?: streaming,
        changes.recording ?: recording,
        changes.muted ?: muted,
        changes.stealthMode ?: stealthMode,
        changes.torchOn ?: torchOn,
        changes.batteryCharging ?: batteryCharging,
    )
}

sealed interface RemoteRequest {
    data object GetStatus : RemoteRequest

    data object GetSettings : RemoteRequest

    data object ReloadBrowserWidgets : RemoteRequest

    data object StartPreview : RemoteRequest

    data object StopPreview : RemoteRequest

    data object StopStatus : RemoteRequest

    data class StartStatus(val interval: Int, val topRight: Boolean) : RemoteRequest

    data class SetRecord(val on: Boolean) : RemoteRequest

    data class SetLive(val on: Boolean) : RemoteRequest

    data class SetMute(val on: Boolean) : RemoteRequest

    data class SetStealthMode(val on: Boolean) : RemoteRequest

    data class SetTorch(val on: Boolean) : RemoteRequest

    data class SetDebugLogging(val on: Boolean) : RemoteRequest

    data class SetZoom(val x: Float) : RemoteRequest

    data class SetZoomPreset(val id: String) : RemoteRequest

    data class SetStream(val id: String) : RemoteRequest

    data class SetScene(val id: String) : RemoteRequest

    data class SetBitratePreset(val id: String) : RemoteRequest

    data class SetMic(val id: String) : RemoteRequest

    data class Unknown(val name: String) : RemoteRequest
}

sealed interface RemoteToStreamer {
    data class Hello(val challenge: String, val salt: String) : RemoteToStreamer

    data class Identified(val result: String) : RemoteToStreamer

    data class Request(val id: Int, val data: RemoteRequest) : RemoteToStreamer

    data object Pong : RemoteToStreamer
}

sealed interface RemoteToAssistant {
    data class Identify(val streamerId: String?, val authentication: String) : RemoteToAssistant

    data class Response(
        val id: Int,
        val result: String,
        val status: RemoteStatus? = null,
        val settings: RemoteSettings? = null,
    ) : RemoteToAssistant

    data class StateEvent(val state: RemoteState) : RemoteToAssistant

    data class StatusEvent(val status: RemoteStatus) : RemoteToAssistant

    data class LogEvent(val entry: String) : RemoteToAssistant

    data class Preview(val preview: String) : RemoteToAssistant

    data object Ping : RemoteToAssistant

    data object Other : RemoteToAssistant
}

private fun on(value: Boolean) = buildJsonObject { put("on", value) }

private fun id(value: String) = buildJsonObject { put("id", value) }

private fun encode(request: RemoteRequest): JsonObject = when (request) {
    RemoteRequest.GetStatus -> case("getStatus")
    RemoteRequest.GetSettings -> case("getSettings")
    RemoteRequest.ReloadBrowserWidgets -> case("reloadBrowserWidgets")
    RemoteRequest.StartPreview -> case("startPreview")
    RemoteRequest.StopPreview -> case("stopPreview")
    RemoteRequest.StopStatus -> case("stopStatus")
    is RemoteRequest.StartStatus -> case(
        "startStatus",
        buildJsonObject {
            put("interval", request.interval)
            put("filter", buildJsonObject { put("topRight", request.topRight) })
        },
    )
    is RemoteRequest.SetRecord -> case("setRecord", on(request.on))
    is RemoteRequest.SetLive -> case("setLive", on(request.on))
    is RemoteRequest.SetMute -> case("setMute", on(request.on))
    is RemoteRequest.SetStealthMode -> case("setStealthMode", on(request.on))
    is RemoteRequest.SetTorch -> case("setTorch", on(request.on))
    is RemoteRequest.SetDebugLogging -> case("setDebugLogging", on(request.on))
    is RemoteRequest.SetZoom -> case("setZoom", buildJsonObject { put("x", request.x) })
    is RemoteRequest.SetZoomPreset -> case("setZoomPreset", id(request.id))
    is RemoteRequest.SetStream -> case("setStream", id(request.id))
    is RemoteRequest.SetScene -> case("setScene", id(request.id))
    is RemoteRequest.SetBitratePreset -> case("setBitratePreset", id(request.id))
    is RemoteRequest.SetMic -> case("setMic", id(request.id))
    is RemoteRequest.Unknown -> case(request.name)
}

private fun decodeRequest(data: JsonObject?): RemoteRequest {
    val (name, fields) = data?.single() ?: return RemoteRequest.Unknown("")
    val on = fields.boolean("on")
    val id = fields.string("id")
    return when (name) {
        "getStatus" -> RemoteRequest.GetStatus
        "getSettings" -> RemoteRequest.GetSettings
        "reloadBrowserWidgets" -> RemoteRequest.ReloadBrowserWidgets
        "startPreview" -> RemoteRequest.StartPreview
        "stopPreview" -> RemoteRequest.StopPreview
        "stopStatus" -> RemoteRequest.StopStatus
        "startStatus" -> fields.int("interval")?.let {
            RemoteRequest.StartStatus(it, fields.obj("filter")?.boolean("topRight") ?: true)
        }
        "setRecord" -> on?.let(RemoteRequest::SetRecord)
        "setLive" -> on?.let(RemoteRequest::SetLive)
        "setMute" -> on?.let(RemoteRequest::SetMute)
        "setStealthMode" -> on?.let(RemoteRequest::SetStealthMode)
        "setTorch" -> on?.let(RemoteRequest::SetTorch)
        "setDebugLogging" -> on?.let(RemoteRequest::SetDebugLogging)
        "setZoom" -> fields.double("x")?.let { RemoteRequest.SetZoom(it.toFloat()) }
        "setZoomPreset" -> id?.let(RemoteRequest::SetZoomPreset)
        "setStream" -> id?.let(RemoteRequest::SetStream)
        "setScene" -> id?.let(RemoteRequest::SetScene)
        "setBitratePreset" -> id?.let(RemoteRequest::SetBitratePreset)
        "setMic" -> id?.let(RemoteRequest::SetMic)
        else -> null
    } ?: RemoteRequest.Unknown(name)
}

private fun item(item: RemoteStatusItem) = buildJsonObject {
    put("message", item.message)
    put("ok", item.ok)
}

private fun items(items: Map<String, RemoteStatusItem>, more: JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
    items.forEach { (key, value) -> put(key, item(value)) }
    more()
}

private fun JsonObjectBuilder.putStatus(status: RemoteStatus) {
    status.general?.let { general ->
        put(
            "general",
            buildJsonObject {
                general.batteryCharging?.let { put("batteryCharging", it) }
                general.batteryLevel?.let { put("batteryLevel", it) }
                general.flame?.let { put("flame", it) }
                general.wiFiSsid?.let { put("wiFiSsid", it) }
                general.isLive?.let { put("isLive", it) }
                general.isRecording?.let { put("isRecording", it) }
                general.isMuted?.let { put("isMuted", it) }
            },
        )
    }
    status.topLeft?.let { put("topLeft", items(it)) }
    status.topRight?.let { topRight ->
        put(
            "topRight",
            items(topRight.items) {
                if (topRight.muted || topRight.audioLevel != null) {
                    put(
                        "audioInfo",
                        buildJsonObject {
                            put(
                                "audioLevel",
                                when {
                                    topRight.muted -> case("muted")
                                    else -> case("value", buildJsonObject { put("_0", topRight.audioLevel) })
                                },
                            )
                            put("numberOfAudioChannels", 1)
                        },
                    )
                }
                if (topRight.bitsPerSecond != null) {
                    put(
                        "bitrateInfo",
                        buildJsonObject {
                            put("bitsPerSecond", topRight.bitsPerSecond)
                            put("totalBytes", topRight.totalBytes ?: 0)
                        },
                    )
                }
                topRight.bonding?.let { connections ->
                    put(
                        "bondingInfo",
                        buildJsonArray {
                            connections.forEach { connection ->
                                add(
                                    buildJsonObject {
                                        put("name", connection.name)
                                        put("bytesPerSecond", connection.bytesPerSecond)
                                        connection.rtt?.let { put("rtt", it) }
                                    },
                                )
                            }
                        },
                    )
                }
            },
        )
    }
}

private fun decodeItems(fields: JsonObject): Map<String, RemoteStatusItem> = fields.mapNotNull { (key, value) ->
    val item = value as? JsonObject ?: return@mapNotNull null
    key to RemoteStatusItem(item.string("message") ?: return@mapNotNull null, item.boolean("ok") ?: true)
}.toMap()

private fun decodeStatus(fields: JsonObject): RemoteStatus = RemoteStatus(
    general = fields.obj("general")?.let {
        RemoteStatusGeneral(
            it.boolean("batteryCharging"),
            it.int("batteryLevel"),
            it.string("flame"),
            it.string("wiFiSsid"),
            it.boolean("isLive"),
            it.boolean("isRecording"),
            it.boolean("isMuted"),
        )
    },
    topLeft = fields.obj("topLeft")?.let(::decodeItems),
    topRight = fields.obj("topRight")?.let { topRight ->
        val level = topRight.obj("audioInfo")?.obj("audioLevel")?.single()
        RemoteStatusTopRight(
            items = decodeItems(topRight),
            audioLevel = level?.takeIf { it.first == "value" }?.second?.double("_0")?.toFloat(),
            muted = level?.first == "muted",
            bitsPerSecond = topRight.obj("bitrateInfo")?.long("bitsPerSecond"),
            totalBytes = topRight.obj("bitrateInfo")?.long("totalBytes"),
            bonding = (topRight["bondingInfo"] as? JsonArray)?.mapNotNull {
                val connection = it as? JsonObject ?: return@mapNotNull null
                RemoteBondingConnection(
                    connection.string("name") ?: return@mapNotNull null,
                    connection.long("bytesPerSecond") ?: 0,
                    connection.int("rtt"),
                )
            },
        )
    },
)

private fun named(list: List<RemoteNamed>) = buildJsonArray {
    list.forEach { entry ->
        add(
            buildJsonObject {
                put("id", entry.id)
                put("name", entry.name)
            },
        )
    }
}

private fun zoomPresets(list: List<RemoteZoomPreset>) = buildJsonArray {
    list.forEach { preset ->
        add(
            buildJsonObject {
                put("id", preset.id)
                put("name", preset.name)
                put("x", preset.x)
            },
        )
    }
}

private fun encode(settings: RemoteSettings) = buildJsonObject {
    put("streams", named(settings.streams))
    put("scenes", named(settings.scenes))
    put("autoSceneSwitchers", JsonArray(emptyList()))
    put(
        "bitratePresets",
        buildJsonArray {
            settings.bitratePresets.forEach { preset ->
                add(
                    buildJsonObject {
                        put("id", preset.id)
                        put("bitrate", preset.bitrate)
                    },
                )
            }
        },
    )
    put("mics", named(settings.mics))
    put(
        "srt",
        buildJsonObject {
            put("connectionPrioritiesEnabled", false)
            put("connectionPriorities", JsonArray(emptyList()))
        },
    )
    put(
        "zoomPresets",
        buildJsonObject {
            put("front", zoomPresets(settings.zoomFront))
            put("back", zoomPresets(settings.zoomBack))
        },
    )
}

private fun decodeNamed(list: List<kotlinx.serialization.json.JsonElement>) = list.mapNotNull {
    val entry = it as? JsonObject ?: return@mapNotNull null
    RemoteNamed(entry.string("id") ?: return@mapNotNull null, entry.string("name").orEmpty())
}

private fun decodeZoomPresets(list: List<kotlinx.serialization.json.JsonElement>) = list.mapNotNull {
    val entry = it as? JsonObject ?: return@mapNotNull null
    RemoteZoomPreset(
        entry.string("id") ?: return@mapNotNull null,
        entry.string("name").orEmpty(),
        entry.double("x")?.toFloat() ?: return@mapNotNull null,
    )
}

private fun decodeSettings(fields: JsonObject) = RemoteSettings(
    streams = decodeNamed(fields.array("streams")),
    scenes = decodeNamed(fields.array("scenes")),
    bitratePresets = fields.array("bitratePresets").mapNotNull {
        val entry = it as? JsonObject ?: return@mapNotNull null
        RemoteBitratePreset(entry.string("id") ?: return@mapNotNull null, entry.long("bitrate") ?: return@mapNotNull null)
    },
    mics = decodeNamed(fields.array("mics")),
    zoomFront = decodeZoomPresets(fields.obj("zoomPresets")?.array("front").orEmpty()),
    zoomBack = decodeZoomPresets(fields.obj("zoomPresets")?.array("back").orEmpty()),
)

private fun encode(state: RemoteState) = buildJsonObject {
    state.scene?.let { put("scene", it) }
    state.mic?.let { put("mic", it) }
    state.bitrate?.let { put("bitrate", it) }
    state.zoom?.let { put("zoom", it) }
    state.zoomPreset?.let { put("zoomPreset", it) }
    state.zoomCamera?.let { camera ->
        put(
            "zoomCamera",
            buildJsonObject {
                put("position", camera.position)
                put("minX", camera.minX)
                put("maxX", camera.maxX)
            },
        )
    }
    state.debugLogging?.let { put("debugLogging", it) }
    state.streaming?.let { put("streaming", it) }
    state.recording?.let { put("recording", it) }
    state.muted?.let { put("muted", it) }
    state.stealthMode?.let { put("stealthMode", it) }
    state.torchOn?.let { put("torchOn", it) }
    state.batteryCharging?.let { put("batteryCharging", it) }
}

private fun decodeState(fields: JsonObject) = RemoteState(
    scene = fields.string("scene"),
    mic = fields.string("mic"),
    bitrate = fields.string("bitrate"),
    zoom = fields.double("zoom")?.toFloat(),
    zoomPreset = fields.string("zoomPreset"),
    zoomCamera = fields.obj("zoomCamera")?.let {
        RemoteZoomCamera(it.string("position") ?: "other", it.double("minX")?.toFloat() ?: 1f, it.double("maxX")?.toFloat() ?: 1f)
    },
    debugLogging = fields.boolean("debugLogging"),
    streaming = fields.boolean("streaming"),
    recording = fields.boolean("recording"),
    muted = fields.boolean("muted"),
    stealthMode = fields.boolean("stealthMode"),
    torchOn = fields.boolean("torchOn"),
    batteryCharging = fields.boolean("batteryCharging"),
)

fun encode(message: RemoteToStreamer): String = when (message) {
    is RemoteToStreamer.Hello -> case(
        "hello",
        buildJsonObject {
            put("apiVersion", REMOTE_API_VERSION)
            put(
                "authentication",
                buildJsonObject {
                    put("challenge", message.challenge)
                    put("salt", message.salt)
                },
            )
        },
    )
    is RemoteToStreamer.Identified -> case("identified", buildJsonObject { put("result", case(message.result)) })
    is RemoteToStreamer.Request -> case(
        "request",
        buildJsonObject {
            put("id", message.id)
            put("data", encode(message.data))
        },
    )
    RemoteToStreamer.Pong -> case("pong")
}.toString()

fun decodeToStreamer(text: String): RemoteToStreamer? {
    val (name, fields) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.single() ?: return null
    return when (name) {
        "hello" -> {
            val authentication = fields.obj("authentication") ?: return null
            RemoteToStreamer.Hello(authentication.string("challenge") ?: return null, authentication.string("salt") ?: return null)
        }
        "identified" -> RemoteToStreamer.Identified(fields.obj("result")?.single()?.first ?: return null)
        "request" -> RemoteToStreamer.Request(fields.int("id") ?: return null, decodeRequest(fields.obj("data")))
        "pong" -> RemoteToStreamer.Pong
        else -> null
    }
}

private fun event(data: JsonObject) = case("event", buildJsonObject { put("data", data) })

fun encode(message: RemoteToAssistant): String = when (message) {
    is RemoteToAssistant.Identify -> case(
        "identify",
        buildJsonObject {
            message.streamerId?.let { put("streamerId", it) }
            put("authentication", message.authentication)
        },
    )
    is RemoteToAssistant.Response -> case(
        "response",
        buildJsonObject {
            put("id", message.id)
            put("result", case(message.result))
            when {
                message.status != null -> put("data", case("getStatus", buildJsonObject { putStatus(message.status) }))
                message.settings != null -> put(
                    "data",
                    case("getSettings", buildJsonObject { put("data", encode(message.settings)) }),
                )
            }
        },
    )
    is RemoteToAssistant.StateEvent -> event(case("state", buildJsonObject { put("data", encode(message.state)) }))
    is RemoteToAssistant.StatusEvent -> event(case("status", buildJsonObject { putStatus(message.status) }))
    is RemoteToAssistant.LogEvent -> event(case("log", buildJsonObject { put("entry", message.entry) }))
    is RemoteToAssistant.Preview -> case("preview", buildJsonObject { put("preview", message.preview) })
    RemoteToAssistant.Ping -> case("ping")
    RemoteToAssistant.Other -> EMPTY_OBJECT
}.toString()

fun decodeToAssistant(text: String): RemoteToAssistant? {
    val (name, fields) = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()?.single() ?: return null
    return when (name) {
        "identify" -> RemoteToAssistant.Identify(fields.string("streamerId"), fields.string("authentication") ?: return null)
        "response" -> {
            val data = fields.obj("data")?.single()
            RemoteToAssistant.Response(
                id = fields.int("id") ?: return null,
                result = fields.obj("result")?.single()?.first ?: return null,
                status = data?.takeIf { it.first == "getStatus" }?.let { decodeStatus(it.second) },
                settings = data?.takeIf { it.first == "getSettings" }?.second?.obj("data")?.let(::decodeSettings),
            )
        }
        "event" -> {
            val (type, data) = fields.obj("data")?.single() ?: return null
            when (type) {
                "state" -> RemoteToAssistant.StateEvent(decodeState(data.obj("data") ?: return null))
                "status" -> RemoteToAssistant.StatusEvent(decodeStatus(data))
                "log" -> RemoteToAssistant.LogEvent(data.string("entry") ?: return null)
                else -> RemoteToAssistant.Other
            }
        }
        "preview" -> RemoteToAssistant.Preview(fields.string("preview") ?: return null)
        "ping" -> RemoteToAssistant.Ping
        else -> RemoteToAssistant.Other
    }
}
