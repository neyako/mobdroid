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
}

@Serializable
enum class QuickButtonId(@StringRes val label: Int) {
    CHAT(R.string.quick_chat),
    RECORD(R.string.quick_record),
    SNAPSHOT(R.string.quick_snapshot),
    WIDGETS(R.string.quick_widgets),
    OBS(R.string.quick_obs),
    BROWSER(R.string.quick_browser),
    BITRATE(R.string.quick_bitrate),
    MIC(R.string.quick_mic),
    STEALTH(R.string.quick_stealth),
    TORCH(R.string.quick_torch),
    FILTERS(R.string.filters),
    DRAW(R.string.quick_draw),
    OVERLAYS(R.string.quick_overlays),
    AUTO_SWITCHER(R.string.quick_auto_switcher),
    MACROS(R.string.macros),
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
    val removeBackground: Boolean = false,
    val keyFrom: Int = 90,
    val keyTo: Int = 150,
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
enum class Filter(@StringRes val label: Int) {
    FOUR_THREE(R.string.filter_four_three),
    CRT(R.string.filter_crt),
    GRAYSCALE(R.string.filter_grayscale),
    SEPIA(R.string.filter_sepia),
    TRIPLE(R.string.filter_triple),
    TWIN(R.string.filter_twin),
    PIXELLATE(R.string.filter_pixellate),
    WHIRLPOOL(R.string.filter_whirlpool),
    PINCH(R.string.filter_pinch),
    BLUR_FACES(R.string.filter_blur_faces),
    BLUR_TEXT(R.string.filter_blur_text),
    PRIVACY(R.string.filter_privacy),
    BEAUTY(R.string.filter_beauty),
    MOUTH(R.string.filter_mouth),
}

@Serializable
enum class PrivacyMode(@StringRes val label: Int) {
    BLUR(R.string.privacy_blur),
    PIXELLATE(R.string.filter_pixellate),
    IMAGE(R.string.widget_image),
    ICON(R.string.privacy_icon),
}

@Serializable
data class Lut(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val file: String? = null,
    val enabled: Boolean = false,
)

val BUNDLED_LUTS = listOf(Lut(id = "bundled-meme", name = "Moblin Meme"))

@Serializable
data class Effects(
    val filters: Set<Filter> = emptySet(),
    val pixellate: Int = 30,
    val whirlpool: Int = 90,
    val pinch: Int = 50,
    val privacyMode: PrivacyMode = PrivacyMode.BLUR,
    val blurStrength: Int = 80,
    val facePixellate: Int = 30,
    val faceImage: String? = null,
    val smoothRadius: Int = 10,
    val smoothStrength: Int = 65,
    val shapePosition: Int = 50,
    val shapeRadius: Int = 50,
    val shapeStrength: Int = 50,
    val luts: List<Lut> = BUNDLED_LUTS,
)

@Serializable
data class SwitcherScene(val id: String = UUID.randomUUID().toString(), val sceneId: String? = null, val seconds: Int = 15)

@Serializable
data class AutoSwitcher(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Switcher",
    val shuffle: Boolean = false,
    val scenes: List<SwitcherScene> = emptyList(),
)

@Serializable
enum class MacroFunction(@StringRes val label: Int) {
    SCENE(R.string.scene),
    ZOOM(R.string.zoom),
    FILTERS(R.string.filters),
    REACTION(R.string.macro_reaction),
    RECORD(R.string.quick_record),
    SNAPSHOT(R.string.quick_snapshot),
    MUTE(R.string.macro_mute),
    TORCH(R.string.quick_torch),
    AUTO_SWITCHER(R.string.auto_switcher),
    CHAT_MESSAGE(R.string.macro_chat_message),
    NOTIFICATION(R.string.macro_notification),
    DELAY(R.string.macro_delay),
    WAIT_FOR_EVENT(R.string.macro_wait),
    IF(R.string.macro_if),
    MACRO(R.string.macro_run_macro),
}

@Serializable
enum class MacroEventType(@StringRes val label: Int) {
    GO_LIVE(R.string.go_live),
    END(R.string.end),
    START_RECORDING(R.string.event_start_recording),
    STOP_RECORDING(R.string.event_stop_recording),
    SWITCH_SCENE(R.string.event_switch_scene),
}

@Serializable
enum class Comparison(val label: String) {
    EQUAL("="),
    NOT_EQUAL("!="),
    LESS("<"),
    LESS_EQUAL("<="),
    GREATER(">"),
    GREATER_EQUAL(">="),
    CONTAINS("contains"),
}

@Serializable
enum class Reaction(@StringRes val label: Int) {
    GLASSES(R.string.prop_glasses),
    SPARKLE(R.string.prop_sparkle),
}

@Serializable
enum class RepeatMode(@StringRes val label: Int) {
    OFF(R.string.off),
    COUNT(R.string.repeat_count),
    FOREVER(R.string.repeat_forever),
}

@Serializable
data class MacroAction(
    val id: String = UUID.randomUUID().toString(),
    val function: MacroFunction = MacroFunction.SCENE,
    val sceneId: String? = null,
    val zoom: Float = 1f,
    val filters: Set<Filter> = emptySet(),
    val reaction: Reaction = Reaction.GLASSES,
    val on: Boolean = true,
    val switcherId: String? = null,
    val text: String = "",
    val delaySeconds: Float = 3f,
    val event: MacroEventType = MacroEventType.GO_LIVE,
    val value: String = "",
    val comparison: Comparison = Comparison.EQUAL,
    val otherValue: String = "",
    val runCount: Int = 1,
    val macroId: String? = null,
)

@Serializable
data class Macro(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Macro",
    val actions: List<MacroAction> = emptyList(),
    val repeat: RepeatMode = RepeatMode.OFF,
    val repeatCount: Int = 5,
    val closePanel: Boolean = false,
    val runAtStart: Boolean = false,
)

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
    val effects: Effects = Effects(),
    val fixedHorizon: Boolean = false,
    val autoSwitchers: List<AutoSwitcher> = emptyList(),
    val autoSwitcherId: String? = null,
    val macros: List<Macro> = emptyList(),
) {
    val selectedProfile: StreamProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()

    val selectedScene: Scene?
        get() = scenes.firstOrNull { it.id == selectedSceneId } ?: scenes.firstOrNull()
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
