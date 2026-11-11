package app.mobdroid.settings

import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.net.LinkType
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
enum class MicSource(val label: String) { AUTO("Auto"), BUILT_IN("Phone"), USB("USB"), WIRED("Headset") }

@Serializable
enum class ChatTextSize(val label: String, val sp: Int) { SMALL("Small", 12), MEDIUM("Medium", 14), LARGE("Large", 17) }

@Serializable
enum class StatusItem(val label: String) {
    TEMPERATURE("Temperature"),
    UPTIME("Uptime"),
    BITRATE("Bitrate"),
    BONDING("Bonding"),
    AUDIO_LEVEL("Audio level"),
    RECORDING("Recording"),
    OBS("OBS"),
}

@Serializable
enum class QuickButtonId(val label: String) {
    CHAT("Chat"),
    RECORD("Record"),
    SNAPSHOT("Snapshot"),
    WIDGETS("Widgets"),
    OBS("OBS"),
    BROWSER("Browser"),
    BITRATE("Bitrate"),
    MIC("Mic"),
    STEALTH("Stealth"),
    TORCH("Torch"),
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
enum class WidgetType(val label: String) { TEXT("Text"), IMAGE("Image"), CHAT("Chat"), BROWSER("Browser"), MUSIC("Music") }

@Serializable
enum class Corner(val label: String, val left: Boolean, val top: Boolean) {
    TOP_LEFT("Top left", true, true),
    TOP_RIGHT("Top right", false, true),
    BOTTOM_LEFT("Bottom left", true, false),
    BOTTOM_RIGHT("Bottom right", false, false),
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
