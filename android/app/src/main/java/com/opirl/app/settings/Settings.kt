package com.opirl.app.settings

import com.opirl.app.R
import androidx.annotation.StringRes
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.net.LinkType
import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class Platform(val label: String, val codecs: List<VideoCodec>, val defaultUrl: String) {
    TWITCH("Twitch", listOf(VideoCodec.H264), "rtmp://live.twitch.tv/app"),
    YOUTUBE(
        "YouTube",
        listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.H264),
        "rtmps://a.rtmps.youtube.com/live2",
    ),
    KICK("Kick", listOf(VideoCodec.H264), "rtmps://fa723fc1b171.global-contribute.live-video.net/app"),
    FACEBOOK("Facebook", listOf(VideoCodec.H264), "rtmps://live-api-s.facebook.com:443/rtmp"),
    CUSTOM("Custom", listOf(VideoCodec.AV1, VideoCodec.HEVC, VideoCodec.H264), "srtla://"),
}

@Serializable
enum class Resolution(val label: String, val width: Int, val height: Int) {
    R2160("4K", 3840, 2160),
    R1440("1440p", 2560, 1440),
    R1080("1080p", 1920, 1080),
    R720("720p", 1280, 720),
    R540("540p", 960, 540),
    R480("480p", 854, 480),
}

@Serializable
enum class StabilizationSetting { OFF, STANDARD, PREVIEW }

@Serializable
enum class MicSource(@StringRes val label: Int) {
    AUTO(R.string.mic_auto),
    BUILT_IN(R.string.mic_phone),
    USB(R.string.mic_usb),
    WIRED(R.string.mic_headset),
}

@Serializable
enum class ChatTextSize(@StringRes val label: Int, val sp: Int) {
    SMALL(R.string.size_small, 12),
    MEDIUM(R.string.size_medium, 14),
    LARGE(R.string.size_large, 17),
}

@Serializable
enum class StatusItem(@StringRes val label: Int) {
    TEMPERATURE(R.string.temperature),
    UPTIME(R.string.status_uptime),
    BITRATE(R.string.bitrate),
    BONDING(R.string.status_bonding),
    AUDIO_LEVEL(R.string.status_audio_level),
    RECORDING(R.string.recording),
    OBS(R.string.quick_obs),
    MOBLINK(R.string.moblink),
    REMOTE_CONTROL(R.string.remote_control),
}

@Serializable
enum class QuickButtonId(@StringRes val label: Int) {
    CHAT(R.string.quick_chat),
    RECORD(R.string.quick_record),
    SNAPSHOT(R.string.quick_snapshot),
    WIDGETS(R.string.quick_widgets),
    OBS(R.string.quick_obs),
    BROWSER(R.string.quick_browser),
    REMOTE(R.string.quick_remote),
    BITRATE(R.string.quick_bitrate),
    MIC(R.string.quick_mic),
    STEALTH(R.string.quick_stealth),
    TORCH(R.string.quick_torch),
}

@Serializable
data class RecordingSettings(
    val separate: Boolean = false,
    val codec: VideoCodec? = null,
    val bitrateKbps: Int = 20000,
    val startWithStream: Boolean = false,
    val stopWithStream: Boolean = false,
)

@Serializable
data class ObsSettings(
    val enabled: Boolean = false,
    val url: String = "",
    val password: String = "",
    val startStream: Boolean = false,
    val stopStream: Boolean = false,
    val startRecording: Boolean = false,
    val stopRecording: Boolean = false,
)

val RECORDING_CODECS = listOf(VideoCodec.HEVC, VideoCodec.H264, VideoCodec.AV1)

@Serializable
enum class WidgetType(@StringRes val label: Int) {
    TEXT(R.string.widget_text),
    IMAGE(R.string.widget_image),
    CHAT(R.string.chat),
    BROWSER(R.string.widget_browser),
    MUSIC(R.string.widget_music),
}

@Serializable
enum class Corner(@StringRes val label: Int, val left: Boolean, val top: Boolean) {
    TOP_LEFT(R.string.corner_top_left, true, true),
    TOP_RIGHT(R.string.corner_top_right, false, true),
    BOTTOM_LEFT(R.string.corner_bottom_left, true, false),
    BOTTOM_RIGHT(R.string.corner_bottom_right, false, false),
}

@Serializable
data class Widget(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Widget",
    val type: WidgetType = WidgetType.TEXT,
    val text: String = "",
    val textSize: Int = 15,
    val image: String? = null,
    val url: String = "",
    val browserWidth: Int = 1280,
    val browserHeight: Int = 720,
    val previewOnly: Boolean = false,
    val cover: Boolean = true,
    val hideWhenPaused: Boolean = true,
)

@Serializable
data class SceneWidget(
    val widgetId: String,
    val enabled: Boolean = true,
    val corner: Corner = Corner.TOP_LEFT,
    val x: Int = 2,
    val y: Int = 3,
    val size: Int = 30,
)

@Serializable
data class SceneVideo(val uri: String, val name: String)

@Serializable
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Scene",
    val cameraKey: String? = null,
    val widgets: List<SceneWidget> = emptyList(),
    val video: Boolean = false,
    val videos: List<SceneVideo> = emptyList(),
    val videoVolume: Int = 100,
    val micWithVideo: Boolean = true,
) {
    val playsVideo: Boolean get() = video && videos.isNotEmpty()
}

@Serializable
enum class ControllerFunction(@StringRes val label: Int) {
    UNUSED(R.string.function_unused),
    RECORD(R.string.quick_record),
    STREAM(R.string.function_stream),
    ZOOM_IN(R.string.function_zoom_in),
    ZOOM_OUT(R.string.function_zoom_out),
    MUTE(R.string.function_mute),
    TORCH(R.string.quick_torch),
    STEALTH(R.string.quick_stealth),
    SCENE(R.string.function_scene),
    SWITCH_SCENE(R.string.function_switch_scene),
    WIDGET(R.string.function_widget),
    SNAPSHOT(R.string.quick_snapshot),
    STREAM_DECK_LAYOUT(R.string.function_stream_deck_layout),
}

@Serializable
data class ControllerAction(
    val function: ControllerFunction = ControllerFunction.UNUSED,
    val sceneId: String? = null,
    val widgetId: String? = null,
    val layoutId: String? = null,
)

@Serializable
data class KeyboardKey(
    val id: String = UUID.randomUUID().toString(),
    val key: String = "",
    val action: ControllerAction = ControllerAction(),
)

@Serializable
enum class GamepadButton(val label: String) {
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),
    L1("L1"),
    R1("R1"),
    L2("L2"),
    R2("R2"),
    DPAD_UP("D-pad ↑"),
    DPAD_DOWN("D-pad ↓"),
    DPAD_LEFT("D-pad ←"),
    DPAD_RIGHT("D-pad →"),
    MENU("Menu"),
}

@Serializable
enum class StreamDeckModel(val label: String, val keys: Int, val columns: Int) {
    MINI("Mini", 6, 3),
    CLASSIC("Classic", 15, 5),
    XL("XL", 32, 8),
}

const val STREAM_DECK_KEYS = 32

@Serializable
data class StreamDeckKey(val text: String = "", val color: Int = 0, val action: ControllerAction = ControllerAction())

@Serializable
data class StreamDeckLayout(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Layout",
    val model: StreamDeckModel = StreamDeckModel.CLASSIC,
    val keys: List<StreamDeckKey> = List(STREAM_DECK_KEYS) { StreamDeckKey() },
) {
    fun key(index: Int): StreamDeckKey = keys.getOrNull(index) ?: StreamDeckKey()

    fun withKey(index: Int, key: StreamDeckKey): StreamDeckLayout =
        copy(keys = List(STREAM_DECK_KEYS) { if (it == index) key else key(it) })
}

@Serializable
data class MoblinkSettings(
    val password: String = "1234",
    val streamer: Boolean = false,
    val streamerPort: Int = 7777,
    val relay: Boolean = false,
    val relayName: String = "",
    val relayId: String = "",
    val relayManual: Boolean = false,
    val relayUrl: String = "",
)

@Serializable
data class RemoteControlSettings(
    val password: String = "",
    val streamerId: String = "",
    val streamer: Boolean = false,
    val streamerUrl: String = "",
    val assistant: Boolean = false,
    val assistantPort: Int = 2345,
    val relay: Boolean = false,
    val relayUrl: String = "wss://moblin.mys-lang.org/moblin-remote-control-relay",
    val bridgeId: String = "",
    val web: Boolean = false,
    val webPort: Int = 8080,
)

val RELAY_NAMES = listOf("Black", "Red", "Green", "Yellow", "Blue", "Purple", "Cyan", "White")

fun randomPassword(): String {
    val bytes = ByteArray(15).also { java.security.SecureRandom().nextBytes(it) }
    return java.util.Base64.getEncoder().encodeToString(bytes).filter { it.isLetterOrDigit() }
}

fun AppSettings.withIdentity(): AppSettings {
    if (moblink.relayId.isNotEmpty() && moblink.relayName.isNotEmpty() && remoteControl.password.isNotEmpty() &&
        remoteControl.streamerId.isNotEmpty() && remoteControl.bridgeId.isNotEmpty()
    ) {
        return this
    }
    return copy(
        moblink = moblink.copy(
            relayId = moblink.relayId.ifEmpty { UUID.randomUUID().toString() },
            relayName = moblink.relayName.ifEmpty { RELAY_NAMES.random() },
        ),
        remoteControl = remoteControl.copy(
            password = remoteControl.password.ifEmpty { randomPassword() },
            streamerId = remoteControl.streamerId.ifEmpty { UUID.randomUUID().toString() },
            bridgeId = remoteControl.bridgeId.ifEmpty { UUID.randomUUID().toString() },
        ),
    )
}

@Serializable
data class Bookmark(val id: String = UUID.randomUUID().toString(), val name: String, val url: String)

@Serializable
data class StreamProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Stream",
    val platform: Platform = Platform.CUSTOM,
    val url: String = Platform.CUSTOM.defaultUrl,
    val streamKey: String = "",
    val codec: VideoCodec? = null,
    val resolution: Resolution = Resolution.R1080,
    val portrait: Boolean = false,
    val fps: Int = 30,
    val bitrateKbps: Int = 6000,
    val adaptiveBitrate: Boolean = true,
    val minBitrateKbps: Int = 500,
    val audioBitrateKbps: Int = 128,
    val keyFrameIntervalSeconds: Int = 2,
    val twitchChannel: String = "",
    val kickChannel: String = "",
    val youtubeChannel: String = "",
    val srtLatencyMs: Int = 3000,
    val bondingNetworks: Set<LinkType> = BONDING_NETWORKS,
    val recording: RecordingSettings = RecordingSettings(),
    val obs: ObsSettings = ObsSettings(),
)

@Serializable
data class AppSettings(
    val profiles: List<StreamProfile> = listOf(StreamProfile(name = "My stream")),
    val selectedProfileId: String? = null,
    val cameraKey: String? = null,
    val stabilization: StabilizationSetting = StabilizationSetting.STANDARD,
    val keepScreenOn: Boolean = true,
    val alertHaptics: Boolean = true,
    val chatOnPreview: Boolean = true,
    val chatTextSize: ChatTextSize = ChatTextSize.MEDIUM,
    val chatSpeech: Boolean = false,
    val chatSpeechNames: Boolean = true,
    val mirrorFrontCameraOnStream: Boolean = false,
    val mic: MicSource = MicSource.AUTO,
    val hiddenStatusItems: Set<StatusItem> = emptySet(),
    val hiddenQuickButtons: Set<QuickButtonId> = emptySet(),
    val fixedLensCameras: Set<String> = emptySet(),
    val bitratePresetsKbps: List<Int> = listOf(1000, 2500, 4000, 6000, 8000, 10000, 15000, 20000),
    val scenes: List<Scene> = emptyList(),
    val widgets: List<Widget> = emptyList(),
    val selectedSceneId: String? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val usbCameraRotation: Int = 0,
    val setupDone: Boolean = false,
    val moblink: MoblinkSettings = MoblinkSettings(),
    val remoteControl: RemoteControlSettings = RemoteControlSettings(),
    val keyboard: List<KeyboardKey> = emptyList(),
    val gamepad: Map<GamepadButton, ControllerAction> = emptyMap(),
    val streamDecks: List<StreamDeckLayout> = emptyList(),
    val selectedStreamDeckId: String? = null,
) {
    val selectedProfile: StreamProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()

    val selectedScene: Scene?
        get() = scenes.firstOrNull { it.id == selectedSceneId } ?: scenes.firstOrNull()

    val selectedStreamDeck: StreamDeckLayout?
        get() = streamDecks.firstOrNull { it.id == selectedStreamDeckId }
}

fun isValidBitrateKbps(kbps: Int) = kbps in 50..50_000

val fpsChoices = listOf(25, 30, 50, 60)

val BONDING_NETWORKS = setOf(LinkType.WIFI, LinkType.CELLULAR, LinkType.ETHERNET)

fun streamUrl(profile: StreamProfile): String {
    val url = profile.url.trim()
    if (!url.startsWith("srt") || Regex("[?&]latency=").containsMatchIn(url)) return url
    return url + (if ('?' in url) "&" else "?") + "latency=${profile.srtLatencyMs}"
}

val StreamProfile.allowedCodecs: List<VideoCodec>
    get() = if (url.startsWith("srt")) platform.codecs - VideoCodec.AV1 else platform.codecs
