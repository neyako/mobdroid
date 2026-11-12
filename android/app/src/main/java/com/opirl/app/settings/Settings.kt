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
    REPLAY(R.string.replay),
    INSTANT_REPLAY(R.string.quick_instant_replay),
    WIDGETS(R.string.quick_widgets),
    OBS(R.string.quick_obs),
    BROWSER(R.string.quick_browser),
    BITRATE(R.string.quick_bitrate),
    MIC(R.string.quick_mic),
    STEALTH(R.string.quick_stealth),
    TORCH(R.string.quick_torch),
    PREVIEW_STREAM(R.string.quick_preview_stream),
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

@Serializable
data class Destination(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Destination",
    val url: String = "rtmp://",
    val streamKey: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class ReplaySettings(
    val enabled: Boolean = false,
    val seconds: Int = 20,
    val delaySeconds: Int = 3,
)

@Serializable
data class PreviewStreamSettings(
    val url: String = "",
    val token: String = "",
    val resolution: Resolution = Resolution.R480,
    val bitrateKbps: Int = 500,
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
    val ristBonding: Boolean = false,
    val recording: RecordingSettings = RecordingSettings(),
    val obs: ObsSettings = ObsSettings(),
    val destinations: List<Destination> = emptyList(),
    val replay: ReplaySettings = ReplaySettings(),
    val previewStream: PreviewStreamSettings = PreviewStreamSettings(),
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
    val micKey: String? = null,
    val replaySpeedPercent: Int = 100,
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
) {
    val selectedProfile: StreamProfile
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.first()

    val selectedScene: Scene?
        get() = scenes.firstOrNull { it.id == selectedSceneId } ?: scenes.firstOrNull()
}

fun isValidBitrateKbps(kbps: Int) = kbps in 50..50_000

val fpsChoices = listOf(25, 30, 50, 60)

val BONDING_NETWORKS = setOf(LinkType.WIFI, LinkType.CELLULAR, LinkType.ETHERNET)

enum class Transport(val label: String, val bonded: Boolean = false) {
    RTMP("RTMP"),
    SRT("SRT"),
    SRTLA("SRTLA", true),
    RIST("RIST"),
    WHIP("WHIP"),
}

fun transportOf(url: String): Transport? {
    val scheme = url.trim().substringBefore("://", "").lowercase()
    return when (scheme) {
        "rtmp", "rtmps" -> Transport.RTMP
        "srt" -> Transport.SRT
        "srtla" -> Transport.SRTLA
        "rist" -> Transport.RIST
        "whip", "whips" -> Transport.WHIP
        else -> null
    }
}

val StreamProfile.transport: Transport? get() = transportOf(url)

val StreamProfile.bonded: Boolean get() = transport == Transport.SRTLA || transport == Transport.RIST && ristBonding

fun streamUrl(profile: StreamProfile): String {
    val url = profile.url.trim()
    val transport = transportOf(url)
    if (transport != Transport.SRT && transport != Transport.SRTLA || Regex("[?&]latency=").containsMatchIn(url)) return url
    return url + (if ('?' in url) "&" else "?") + "latency=${profile.srtLatencyMs}"
}

val StreamProfile.allowedCodecs: List<VideoCodec>
    get() = if (transport == null || transport == Transport.RTMP) platform.codecs else platform.codecs - VideoCodec.AV1

val REPLAY_SECONDS = listOf(10, 20, 30, 60)

val REPLAY_SPEEDS = listOf(100, 50)

val PREVIEW_RESOLUTIONS = listOf(Resolution.R720, Resolution.R540, Resolution.R480)
