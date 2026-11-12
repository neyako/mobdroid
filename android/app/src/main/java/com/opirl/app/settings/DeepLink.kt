package com.opirl.app.settings

import com.opirl.app.engine.media.VideoCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val DEEP_LINK_SCHEME = "moblin"

class DeepLinkException(message: String) : Exception(message)

data class DeepLinkStream(
    val name: String,
    val url: String,
    val selected: Boolean = false,
    val resolution: Resolution? = null,
    val fps: Int? = null,
    val bitrateKbps: Int? = null,
    val codec: VideoCodec? = null,
    val keyFrameIntervalSeconds: Int? = null,
    val audioBitrateKbps: Int? = null,
    val srtLatencyMs: Int? = null,
    val adaptiveBitrate: Boolean? = null,
    val obsUrl: String? = null,
    val obsPassword: String? = null,
    val twitchChannel: String? = null,
    val kickChannel: String? = null,
) {
    fun applyTo(profile: StreamProfile): StreamProfile {
        val platform = platformOf(url)
        return profile.copy(
            platform = platform,
            url = url,
            streamKey = "",
            resolution = resolution ?: profile.resolution,
            fps = fps ?: profile.fps,
            bitrateKbps = bitrateKbps ?: profile.bitrateKbps,
            codec = (codec ?: profile.codec)?.takeIf { it in platform.codecs },
            keyFrameIntervalSeconds = keyFrameIntervalSeconds ?: profile.keyFrameIntervalSeconds,
            audioBitrateKbps = audioBitrateKbps ?: profile.audioBitrateKbps,
            srtLatencyMs = srtLatencyMs ?: profile.srtLatencyMs,
            adaptiveBitrate = adaptiveBitrate ?: profile.adaptiveBitrate,
            obs = if (obsUrl != null) profile.obs.copy(enabled = true, url = obsUrl, password = obsPassword.orEmpty()) else profile.obs,
            twitchChannel = twitchChannel ?: profile.twitchChannel,
            kickChannel = kickChannel ?: profile.kickChannel,
        )
    }
}

data class DeepLinkQuickButtons(val disableAll: Boolean, val buttons: Map<QuickButtonId, Boolean>)

data class DeepLinkRemoteControl(
    val password: String,
    val assistant: Boolean? = null,
    val assistantPort: Int? = null,
    val relay: Boolean? = null,
    val relayUrl: String? = null,
    val bridgeId: String? = null,
    val streamer: Boolean? = null,
    val streamerUrl: String? = null,
)

data class DeepLink(
    val streams: List<DeepLinkStream> = emptyList(),
    val quickButtons: DeepLinkQuickButtons? = null,
    val remoteControl: DeepLinkRemoteControl? = null,
) {
    fun collisions(settings: AppSettings): List<String> =
        streams.map { it.name }.filter { name -> settings.profiles.any { it.name == name } }.distinct()
}

private val QUICK_BUTTONS = mapOf(
    "Torch" to QuickButtonId.TORCH,
    "Mute" to QuickButtonId.MIC,
    "Bitrate" to QuickButtonId.BITRATE,
    "Chat" to QuickButtonId.CHAT,
    "Black screen" to QuickButtonId.STEALTH,
    "Record" to QuickButtonId.RECORD,
    "OBS" to QuickButtonId.OBS,
    "Browser" to QuickButtonId.BROWSER,
    "Remote" to QuickButtonId.REMOTE,
    "Snapshot" to QuickButtonId.SNAPSHOT,
    "Widgets" to QuickButtonId.WIDGETS,
)

private val CODECS = mapOf("H.265/HEVC" to VideoCodec.HEVC, "H.264/AVC" to VideoCodec.H264, "AV1" to VideoCodec.AV1)

private val STREAM_SCHEMES = listOf("rtmp://", "rtmps://", "srt://", "srtla://")

fun platformOf(url: String): Platform {
    val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
    return when {
        host.endsWith("global-contribute.live-video.net") -> Platform.KICK
        host.endsWith("twitch.tv") || host.endsWith("contribute.live-video.net") -> Platform.TWITCH
        host.endsWith("youtube.com") -> Platform.YOUTUBE
        host.endsWith("facebook.com") -> Platform.FACEBOOK
        else -> Platform.CUSTOM
    }
}

private fun percentDecode(text: String): String {
    val bytes = java.io.ByteArrayOutputStream()
    val literal = StringBuilder()
    var index = 0
    while (index < text.length) {
        val value = if (text[index] == '%' && index + 2 < text.length) text.substring(index + 1, index + 3).toIntOrNull(16) else null
        if (value == null) {
            literal.append(text[index++])
        } else {
            bytes.write(literal.toString().toByteArray())
            literal.setLength(0)
            bytes.write(value)
            index += 3
        }
    }
    bytes.write(literal.toString().toByteArray())
    return String(bytes.toByteArray(), Charsets.UTF_8)
}

private const val QUERY_ALLOWED = "!$&'()*+,;=:@/?-._~{}[]\""

private fun percentEncode(text: String): String = buildString {
    for (byte in text.toByteArray()) {
        val char = byte.toInt().toChar()
        if (byte >= 0 && (char.isLetterOrDigit() || char in QUERY_ALLOWED)) append(char) else append("%%%02X".format(byte.toInt() and 0xFF))
    }
}

private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull

private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.child(key: String): JsonObject? = get(key) as? JsonObject

private fun fail(message: String): Nothing = throw DeepLinkException(message)

private fun webSocketUrl(url: String): String {
    if (!url.startsWith("ws://") && !url.startsWith("wss://") || url.substringAfter("://").isEmpty()) fail("Malformed WebSocket URL")
    return url
}

private fun stream(fields: JsonObject): DeepLinkStream {
    val name = fields.text("name") ?: fail("The link cannot be read")
    val url = fields.text("url")?.trim() ?: fail("The link cannot be read")
    if (STREAM_SCHEMES.none { url.startsWith(it) } || url.substringAfter("://").isEmpty()) fail("Unsupported stream URL")
    val video = fields.child("video")
    val resolution = video?.text("resolution")?.let { raw ->
        Resolution.entries.firstOrNull { "${it.width}x${it.height}" == raw } ?: fail("Unsupported video resolution")
    }
    val fps = video?.number("fps")?.toInt()?.also { if (it !in fpsChoices) fail("Unsupported video FPS") }
    val bitrate = video?.number("bitrate")?.also { if (it !in 50_000..50_000_000) fail("Video bitrate out of range") }
    val codec = video?.text("codec")?.let { CODECS[it] ?: fail("Unsupported video codec") }
    val interval = video?.number("maxKeyFrameInterval")?.also { if (it !in 0..10) fail("Key frame interval out of range") }
    val audio = fields.child("audio")?.number("bitrate")?.also {
        if (it !in 32_000..320_000 || it % 32_000 != 0L) fail("Unsupported audio bitrate")
    }
    val srt = fields.child("srt")
    val latency = srt?.number("latency")?.also { if (it !in 0..65535) fail("SRT latency out of range") }
    val obs = fields.child("obs")
    return DeepLinkStream(
        name = name,
        url = url,
        selected = fields.flag("selected") == true,
        resolution = resolution,
        fps = fps,
        bitrateKbps = bitrate?.let { (it / 1000).toInt() },
        codec = codec,
        keyFrameIntervalSeconds = interval?.toInt()?.takeIf { it > 0 }?.coerceAtMost(5),
        audioBitrateKbps = audio?.let { (it / 1000).toInt() },
        srtLatencyMs = latency?.toInt(),
        adaptiveBitrate = srt?.flag("adaptiveBitrateEnabled"),
        obsUrl = obs?.let { webSocketUrl(it.text("webSocketUrl")?.trim() ?: fail("Malformed WebSocket URL")) },
        obsPassword = obs?.let { it.text("webSocketPassword")?.trim() ?: fail("The link cannot be read") },
        twitchChannel = fields.child("twitch")?.text("channelName")?.trim(),
        kickChannel = fields.child("kick")?.text("channelName")?.trim(),
    )
}

private fun remoteControl(fields: JsonObject): DeepLinkRemoteControl {
    val assistant = fields.child("assistant")
    val relay = assistant?.child("relay")
    val streamer = fields.child("streamer")
    return DeepLinkRemoteControl(
        password = fields.text("password")?.trim() ?: fail("Invalid remote control settings"),
        assistant = assistant?.let { it.flag("enabled") ?: fail("Invalid remote control settings") },
        assistantPort = assistant?.let { it.number("port")?.toInt()?.takeIf { port -> port in 1..65535 } ?: fail("Invalid remote control settings") },
        relay = relay?.let { it.flag("enabled") ?: fail("Invalid remote control settings") },
        relayUrl = relay?.let { webSocketUrl(it.text("baseUrl")?.trim().orEmpty()) },
        bridgeId = relay?.let { it.text("bridgeId")?.trim()?.takeIf(String::isNotEmpty) ?: fail("Invalid remote control settings") },
        streamer = streamer?.let { it.flag("enabled") ?: fail("Invalid remote control settings") },
        streamerUrl = streamer?.let { webSocketUrl(it.text("url")?.trim().orEmpty()) },
    )
}

fun parseDeepLink(link: String): DeepLink {
    val trimmed = link.trim()
    val prefix = "$DEEP_LINK_SCHEME://"
    if (!trimmed.startsWith(prefix, ignoreCase = true)) fail("Not a moblin:// link")
    val rest = trimmed.substring(prefix.length)
    if (!rest.startsWith("?")) fail("The link cannot be read")
    val root = runCatching { Json.parseToJsonElement(percentDecode(rest.substring(1))).jsonObject }.getOrNull()
        ?: fail("The link cannot be read")
    val quickButtons = root.child("quickButtons")?.let { fields ->
        DeepLinkQuickButtons(
            fields.flag("disableAllButtons") == true,
            (fields["buttons"] as? JsonArray).orEmpty().mapNotNull { entry ->
                val button = entry as? JsonObject ?: return@mapNotNull null
                val id = QUICK_BUTTONS[button.text("type")] ?: return@mapNotNull null
                id to (button.flag("enabled") ?: return@mapNotNull null)
            }.toMap(),
        )
    }
    return DeepLink(
        streams = (root["streams"] as? JsonArray).orEmpty().map { stream(it as? JsonObject ?: fail("The link cannot be read")) },
        quickButtons = quickButtons,
        remoteControl = root.child("remoteControl")?.let(::remoteControl),
    )
}

private fun uniqueName(name: String, taken: Collection<String>): String {
    if (name !in taken) return name
    var number = 2
    while ("$name $number" in taken) number++
    return "$name $number"
}

fun AppSettings.withDeepLink(link: DeepLink, replace: Boolean): AppSettings {
    var profiles = profiles
    var selected = selectedProfileId
    for (stream in link.streams) {
        val existing = profiles.firstOrNull { it.name == stream.name }.takeIf { replace }
        val profile = stream.applyTo(existing ?: StreamProfile(name = uniqueName(stream.name, profiles.map { it.name })))
        profiles = if (existing != null) profiles.map { if (it.id == existing.id) profile else it } else profiles + profile
        if (stream.selected) selected = profile.id
    }
    val hidden = link.quickButtons?.let { change ->
        val base = if (change.disableAll) QuickButtonId.entries.toSet() else hiddenQuickButtons
        base - change.buttons.filterValues { it }.keys + change.buttons.filterValues { !it }.keys
    } ?: hiddenQuickButtons
    val remote = link.remoteControl?.let { change ->
        remoteControl.copy(
            password = change.password,
            assistant = change.assistant ?: remoteControl.assistant,
            assistantPort = change.assistantPort ?: remoteControl.assistantPort,
            relay = change.relay ?: remoteControl.relay,
            relayUrl = change.relayUrl ?: remoteControl.relayUrl,
            bridgeId = change.bridgeId ?: remoteControl.bridgeId,
            streamer = change.streamer ?: remoteControl.streamer,
            streamerUrl = change.streamerUrl ?: remoteControl.streamerUrl,
        )
    } ?: remoteControl
    return copy(profiles = profiles, selectedProfileId = selected, hiddenQuickButtons = hidden, remoteControl = remote)
}

fun createDeepLink(profiles: List<StreamProfile>, selectedId: String? = null): String {
    val root = buildJsonObject {
        put(
            "streams",
            buildJsonArray {
                for (profile in profiles) {
                    val url = profile.url.trim()
                    val key = profile.streamKey.trim()
                    add(
                        buildJsonObject {
                            put("name", profile.name)
                            put("url", if (url.startsWith("rtmp") && key.isNotEmpty()) "${url.trimEnd('/')}/$key" else url)
                            if (profile.id == selectedId) put("selected", true)
                            put(
                                "video",
                                buildJsonObject {
                                    put("resolution", "${profile.resolution.width}x${profile.resolution.height}")
                                    put("fps", profile.fps)
                                    put("bitrate", profile.bitrateKbps * 1000)
                                    CODECS.entries.firstOrNull { it.value == profile.codec && it.value != VideoCodec.AV1 }?.let {
                                        put("codec", it.key)
                                    }
                                    put("maxKeyFrameInterval", profile.keyFrameIntervalSeconds)
                                },
                            )
                            put("audio", buildJsonObject { put("bitrate", profile.audioBitrateKbps * 1000) })
                            if (url.startsWith("srt")) {
                                put(
                                    "srt",
                                    buildJsonObject {
                                        put("latency", profile.srtLatencyMs)
                                        put("adaptiveBitrateEnabled", profile.adaptiveBitrate)
                                    },
                                )
                            }
                            if (profile.obs.enabled && profile.obs.url.isNotBlank()) {
                                put(
                                    "obs",
                                    buildJsonObject {
                                        put("webSocketUrl", profile.obs.url.trim().let { if ("://" in it) it else "ws://$it" })
                                        put("webSocketPassword", profile.obs.password)
                                    },
                                )
                            }
                            if (profile.twitchChannel.isNotBlank()) {
                                put(
                                    "twitch",
                                    buildJsonObject {
                                        put("channelName", profile.twitchChannel)
                                        put("channelId", "")
                                    },
                                )
                            }
                            if (profile.kickChannel.isNotBlank()) put("kick", buildJsonObject { put("channelName", profile.kickChannel) })
                        },
                    )
                }
            },
        )
    }
    return "$DEEP_LINK_SCHEME://?${percentEncode(root.toString())}"
}
