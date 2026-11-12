package com.opirl.app.settings

import com.opirl.app.R
import androidx.annotation.StringRes
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.net.LinkType
import com.opirl.app.widgets.alerts.AlertsSettings
import com.opirl.app.widgets.bingo.BingoSettings
import com.opirl.app.widgets.crop.CropSettings
import com.opirl.app.widgets.map.MapSettings
import com.opirl.app.widgets.pngtuber.PngTuberSettings
import com.opirl.app.widgets.pomodoro.PomodoroSettings
import com.opirl.app.widgets.qrcode.QrCodeSettings
import com.opirl.app.widgets.scoreboard.ScoreboardSettings
import com.opirl.app.widgets.slideshow.SlideshowSettings
import com.opirl.app.widgets.snapshot.SnapshotSettings
import com.opirl.app.widgets.text.TextSettings
import com.opirl.app.widgets.videosource.VideoSourceSettings
import com.opirl.app.widgets.vtuber.VTuberSettings
import com.opirl.app.widgets.wheel.WheelSettings
import java.util.UUID
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
enum class WidgetType(@StringRes val label: Int, @StringRes val description: Int) {
    TEXT(R.string.widget_text, R.string.widget_text_description),
    BROWSER(R.string.widget_browser, R.string.widget_browser_description),
    VIDEO_SOURCE(R.string.widget_video_source, R.string.widget_video_source_description),
    IMAGE(R.string.widget_image, R.string.widget_image_description),
    ALERTS(R.string.widget_alerts, R.string.widget_alerts_description),
    MAP(R.string.widget_map, R.string.widget_map_description),
    SNAPSHOT(R.string.widget_snapshot, R.string.widget_snapshot_description),
    CHAT(R.string.chat, R.string.widget_chat_description),
    MUSIC(R.string.widget_music, R.string.widget_music_description),
    SCENE(R.string.widget_scene, R.string.widget_scene_description),
    SLIDESHOW(R.string.widget_slideshow, R.string.widget_slideshow_description),
    VTUBER(R.string.widget_vtuber, R.string.widget_vtuber_description),
    PNGTUBER(R.string.widget_pngtuber, R.string.widget_pngtuber_description),
    QR_CODE(R.string.widget_qr_code, R.string.widget_qr_code_description),
    SCOREBOARD(R.string.widget_scoreboard, R.string.widget_scoreboard_description),
    WHEEL_OF_LUCK(R.string.widget_wheel_of_luck, R.string.widget_wheel_of_luck_description),
    BINGO_CARD(R.string.widget_bingo_card, R.string.widget_bingo_card_description),
    CROP(R.string.widget_crop, R.string.widget_crop_description),
    POMODORO(R.string.widget_pomodoro, R.string.widget_pomodoro_description),
}

@Serializable
enum class Corner(@StringRes val label: Int, val left: Boolean, val top: Boolean) {
    TOP_LEFT(R.string.corner_top_left, true, true),
    TOP_RIGHT(R.string.corner_top_right, false, true),
    BOTTOM_LEFT(R.string.corner_bottom_left, true, false),
    BOTTOM_RIGHT(R.string.corner_bottom_right, false, false),
}

@OptIn(ExperimentalSerializationApi::class)
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
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sceneId: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val textOptions: TextSettings = TextSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val videoSource: VideoSourceSettings = VideoSourceSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val alerts: AlertsSettings = AlertsSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val map: MapSettings = MapSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val snapshot: SnapshotSettings = SnapshotSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val slideshow: SlideshowSettings = SlideshowSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val vTuber: VTuberSettings = VTuberSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pngTuber: PngTuberSettings = PngTuberSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val qrCode: QrCodeSettings = QrCodeSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val scoreboard: ScoreboardSettings = ScoreboardSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val wheel: WheelSettings = WheelSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val bingo: BingoSettings = BingoSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val crop: CropSettings = CropSettings(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val pomodoro: PomodoroSettings = PomodoroSettings(),
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
