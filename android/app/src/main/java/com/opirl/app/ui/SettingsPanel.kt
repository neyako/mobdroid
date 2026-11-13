package com.opirl.app.ui

import com.opirl.app.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Videocam
import androidx.activity.result.PickVisualMediaRequest
import com.opirl.app.camera.Facing
import com.opirl.app.scenes.placement
import com.opirl.app.scenes.withPlacement
import com.opirl.app.scenes.withoutWidget
import com.opirl.app.settings.Corner
import com.opirl.app.settings.Scene
import com.opirl.app.settings.SceneVideo
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import java.io.File
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.opirl.app.recording.Recording
import com.opirl.app.settings.RECORDING_CODECS
import com.opirl.app.settings.ObsSettings
import com.opirl.app.settings.RecordingSettings
import com.opirl.app.stream.formatBytes
import com.opirl.app.stream.key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Share
import com.opirl.app.settings.createDeepLink
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.ImportExport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.BuildConfig
import com.opirl.app.engine.chat.bot.BotCommand
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.net.LinkType
import com.opirl.app.media.CodecCatalog
import com.opirl.app.media.label
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.BONDING_NETWORKS
import com.opirl.app.settings.ChatTextSize
import com.opirl.app.media.Mic
import com.opirl.app.media.MicKind
import com.opirl.app.media.findMic
import com.opirl.app.media.micLabel
import com.opirl.app.settings.Destination
import com.opirl.app.settings.PREVIEW_RESOLUTIONS
import com.opirl.app.settings.REPLAY_SECONDS
import com.opirl.app.settings.Transport
import com.opirl.app.settings.bonded
import com.opirl.app.settings.transport
import com.opirl.app.settings.transportOf
import com.opirl.app.stream.LiveState
import com.opirl.app.settings.Platform
import com.opirl.app.settings.QuickButtonId
import com.opirl.app.settings.QuickButtonPage
import com.opirl.app.settings.Resolution
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.StabilizationSetting
import com.opirl.app.settings.StatusItem
import com.opirl.app.settings.StreamProfile
import com.opirl.app.settings.allowedCodecs
import com.opirl.app.settings.fpsChoices
import com.opirl.app.stream.StreamEngine
import com.opirl.app.ui.widgets.WidgetScope
import com.opirl.app.ui.widgets.WidgetSettings
import com.opirl.app.ui.widgets.icon
import com.opirl.app.ui.widgets.widgetFiles
import com.opirl.app.widgets.drawer
import com.opirl.app.stream.outputSize
import java.util.UUID

class SettingsPages {
    internal val stack = mutableStateListOf<Route>(Route.Home)

    fun reset() {
        if (stack.size > 1) stack.removeRange(1, stack.size)
    }

    internal fun open(vararg routes: Route) {
        reset()
        stack += routes
    }
}

internal sealed interface Route {
    data object Home : Route

    data object Streams : Route

    data class Stream(val id: String) : Route

    data class StreamUrl(val id: String) : Route

    data class StreamVideo(val id: String) : Route

    data class StreamAudio(val id: String) : Route

    data class StreamTransport(val id: String) : Route

    data class StreamChat(val id: String) : Route

    data class StreamRecording(val id: String) : Route

    data class StreamObs(val id: String) : Route

    data class StreamDestinations(val id: String) : Route

    data class StreamDestination(val id: String, val destinationId: String) : Route

    data class StreamReplay(val id: String) : Route

    data class StreamPreview(val id: String) : Route

    data object Recordings : Route

    data object Scenes : Route

    data class ScenePage(val id: String) : Route

    data class SceneVideos(val id: String) : Route

    data class Placement(val sceneId: String, val widgetId: String) : Route

    data class WidgetPage(val id: String, val page: String? = null, val title: String? = null) : Route

    data object NewWidget : Route

    data class SwitcherPage(val id: String) : Route

    data class SwitcherScenePage(val switcherId: String, val id: String) : Route

    data class MacroPage(val id: String) : Route

    data class MacroActionPage(val macroId: String, val id: String) : Route

    data class MacroFunctionPage(val macroId: String, val id: String) : Route

    data object Chat : Route

    data object TwitchAccount : Route

    data object KickAccount : Route

    data object YouTubeAccount : Route

    data object Emotes : Route

    data object Speech : Route

    data object SpeechVoices : Route

    data object PredefinedMessages : Route

    data object ChatBot : Route

    data class BotCommandPage(val command: BotCommand) : Route

    data object Display : Route

    data object StatusItems : Route

    data object QuickButtons : Route

    data object Camera : Route

    data object Audio : Route

    data object BitratePresets : Route

    data object About : Route

    data object Licenses : Route

    data class License(val name: String) : Route

    data object ImportExport : Route

    data object ShareStreams : Route

    data object RemoteControl : Route

    data object RemoteStreamer : Route

    data object RemoteAssistant : Route

    data object RemoteWeb : Route

    data object Moblink : Route

    data object MoblinkStreamer : Route

    data object MoblinkRelay : Route

    data object Gamepad : Route

    data object Keyboard : Route

    data class KeyboardKeyPage(val id: String) : Route

    data object StreamDecks : Route

    data class StreamDeckPage(val id: String) : Route

    data class StreamDeckKeyPage(val layoutId: String, val index: Int) : Route

    data class ActionPage(val target: ActionTarget) : Route
}

private fun AppSettings.profile(id: String) = profiles.firstOrNull { it.id == id }

private fun transportName(profile: StreamProfile) = (profile.transport ?: Transport.RTMP).label

@Composable
private fun micLabel(mic: Mic): String = micLabel(mic, stringResource(mic.kind.label))

@Composable
private fun micLabel(mics: List<Mic>, key: String?): String = when {
    key == null -> stringResource(R.string.mic_auto)
    else -> findMic(mics, key)?.let { micLabel(it) }
        ?: MicKind.entries.firstOrNull { key.startsWith(it.name) }?.let { stringResource(it.label) }.orEmpty()
}

@Composable
private fun title(route: Route, settings: AppSettings) = when (route) {
    Route.Home -> stringResource(R.string.settings)
    Route.Streams -> stringResource(R.string.streams)
    is Route.Stream -> settings.profile(route.id)?.name.orEmpty()
    is Route.StreamUrl -> "URL"
    is Route.StreamVideo -> stringResource(R.string.video)
    is Route.StreamAudio -> stringResource(R.string.audio)
    is Route.StreamTransport -> settings.profile(route.id)?.let(::transportName).orEmpty()
    is Route.StreamChat -> stringResource(R.string.chat)
    is Route.StreamRecording -> stringResource(R.string.recording)
    is Route.StreamObs -> stringResource(R.string.obs_remote)
    is Route.StreamDestinations -> stringResource(R.string.destinations)
    is Route.StreamDestination ->
        settings.profile(route.id)?.destinations?.firstOrNull { it.id == route.destinationId }?.name.orEmpty()
    is Route.StreamReplay -> stringResource(R.string.replay)
    is Route.StreamPreview -> stringResource(R.string.preview_stream)
    Route.Recordings -> stringResource(R.string.recordings)
    Route.Scenes -> stringResource(R.string.scenes)
    is Route.ScenePage -> settings.scenes.firstOrNull { it.id == route.id }?.name.orEmpty()
    is Route.SceneVideos -> stringResource(R.string.videos)
    is Route.Placement -> stringResource(
        R.string.placement_title,
        settings.widgets.firstOrNull { it.id == route.widgetId }?.name.orEmpty(),
        settings.scenes.firstOrNull { it.id == route.sceneId }?.name.orEmpty(),
    )
    is Route.WidgetPage -> route.title ?: settings.widgets.firstOrNull { it.id == route.id }?.name.orEmpty()
    Route.NewWidget -> stringResource(R.string.create_widget)
    is Route.SwitcherPage -> settings.autoSwitchers.firstOrNull { it.id == route.id }?.name.orEmpty()
    is Route.SwitcherScenePage -> stringResource(R.string.scene)
    is Route.MacroPage -> settings.macros.firstOrNull { it.id == route.id }?.name.orEmpty()
    is Route.MacroActionPage -> settings.macros.firstOrNull { it.id == route.macroId }?.actions?.firstOrNull { it.id == route.id }
        ?.let { stringResource(it.function.label) }.orEmpty()
    is Route.MacroFunctionPage -> stringResource(R.string.macro_function)
    Route.Chat -> stringResource(R.string.chat)
    Route.TwitchAccount -> stringResource(R.string.twitch_account)
    Route.KickAccount -> stringResource(R.string.kick_account)
    Route.YouTubeAccount -> stringResource(R.string.youtube_account)
    Route.Emotes -> stringResource(R.string.emotes)
    Route.Speech -> stringResource(R.string.chat_speech)
    Route.SpeechVoices -> stringResource(R.string.voice)
    Route.PredefinedMessages -> stringResource(R.string.predefined_messages)
    Route.ChatBot -> stringResource(R.string.chat_bot)
    is Route.BotCommandPage -> botCommandTitle(route.command)
    Route.Display -> stringResource(R.string.display)
    Route.StatusItems -> stringResource(R.string.status)
    Route.QuickButtons -> stringResource(R.string.quick_buttons)
    Route.Camera -> stringResource(R.string.camera)
    Route.Audio -> stringResource(R.string.audio)
    Route.BitratePresets -> stringResource(R.string.bitrate_presets)
    Route.About -> stringResource(R.string.about)
    Route.Licenses -> stringResource(R.string.licenses)
    is Route.License -> route.name
    Route.ImportExport -> stringResource(R.string.import_export)
    Route.ShareStreams -> stringResource(R.string.share_streams)
    Route.RemoteControl -> stringResource(R.string.remote_control)
    Route.RemoteStreamer -> stringResource(R.string.remote_streamer)
    Route.RemoteAssistant -> stringResource(R.string.remote_assistant)
    Route.RemoteWeb -> stringResource(R.string.remote_web)
    Route.Moblink -> stringResource(R.string.moblink)
    Route.MoblinkStreamer -> stringResource(R.string.remote_streamer)
    Route.MoblinkRelay -> stringResource(R.string.moblink_relay)
    Route.Gamepad -> stringResource(R.string.game_controller)
    Route.Keyboard -> stringResource(R.string.keyboard)
    is Route.KeyboardKeyPage -> stringResource(R.string.key)
    Route.StreamDecks -> "Stream Deck"
    is Route.StreamDeckPage -> settings.streamDecks.firstOrNull { it.id == route.id }?.name.orEmpty()
    is Route.StreamDeckKeyPage -> stringResource(
        R.string.key_position,
        route.index / (settings.streamDecks.firstOrNull { it.id == route.layoutId }?.model?.columns ?: 5) + 1,
        route.index % (settings.streamDecks.firstOrNull { it.id == route.layoutId }?.model?.columns ?: 5) + 1,
    )
    is Route.ActionPage -> stringResource(R.string.function)
}

private const val SOURCE_URL = "https://github.com/neyako/mobdroid"

@Composable
private fun platformLabel(platform: Platform): String =
    if (platform == Platform.CUSTOM) stringResource(R.string.platform_custom) else platform.label

private fun videoSummary(profile: StreamProfile): String {
    val (w, h) = outputSize(profile)
    val codec = CodecCatalog.choose(profile.codec, profile.allowedCodecs, w, h, profile.fps)
    return "${profile.resolution.label}${profile.fps} ${codec.label} ${formatMbps(profile.bitrateKbps)} Mbps"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsPanel(
    store: SettingsStore,
    engine: StreamEngine,
    live: Boolean,
    pages: SettingsPages,
    onHide: () -> Unit,
    onClose: () -> Unit,
) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val stack = pages.stack
    val push: (Route) -> Unit = { stack += it }
    val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    val back = backProgress(stack.size > 1) { pop() }
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<IntOffset>()
    val fastSpatial = motion.fastSpatialSpec<IntOffset>()
    val effects = motion.defaultEffectsSpec<Float>()
    val fastEffects = motion.fastEffectsSpec<Float>()
    PanelSurface {
        PanelHeader(title(stack.last(), settings), onHide, onClose, onBack = if (stack.size > 1) ({ pop() }) else null)
        AnimatedContent(
            targetState = stack.size to stack.last(),
            modifier = Modifier.backGesture(back),
            transitionSpec = {
                val forward = targetState.first > initialState.first
                (slideInHorizontally(spatial) { if (forward) it / 4 else -it / 4 } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(fastSpatial) { if (forward) -it / 4 else it / 4 } + fadeOut(fastEffects))
            },
            label = "settings",
        ) { (_, route) ->
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (route) {
                    Route.Home -> Home(settings, engine, push)
                    Route.Streams -> Streams(store, settings, live, push)
                    is Route.Stream -> settings.profile(route.id)?.let { StreamPage(store, settings, it, live, push, pop) }
                    is Route.StreamUrl -> settings.profile(route.id)?.let { StreamUrl(store, it, editable(settings, it, live)) }
                    is Route.StreamVideo -> settings.profile(route.id)?.let { StreamVideo(store, it, editable(settings, it, live)) }
                    is Route.StreamAudio -> settings.profile(route.id)?.let { StreamAudio(store, it, editable(settings, it, live)) }
                    is Route.StreamTransport -> settings.profile(route.id)?.let {
                        StreamTransport(store, it, editable(settings, it, live))
                    }
                    is Route.StreamChat -> settings.profile(route.id)?.let { profile ->
                        Group {
                            row { shapes ->
                                TextRow(shapes, stringResource(R.string.twitch_channel), profile.twitchChannel) {
                                    store.updateProfile(profile.copy(twitchChannel = it.trim()))
                                }
                            }
                            row { shapes ->
                                TextRow(shapes, stringResource(R.string.youtube_channel), profile.youtubeChannel) {
                                    store.updateProfile(profile.copy(youtubeChannel = it.trim()))
                                }
                            }
                            row { shapes ->
                                TextRow(shapes, stringResource(R.string.kick_channel), profile.kickChannel) {
                                    store.updateProfile(profile.copy(kickChannel = it.trim()))
                                }
                            }
                        }
                    }
                    is Route.StreamRecording -> settings.profile(route.id)?.let {
                        StreamRecording(store, it, editable(settings, it, live))
                    }
                    is Route.StreamObs -> settings.profile(route.id)?.let { StreamObs(store, engine, it) }
                    is Route.StreamDestinations -> settings.profile(route.id)?.let {
                        StreamDestinations(store, it, engine, editable(settings, it, live), push)
                    }
                    is Route.StreamDestination -> settings.profile(route.id)?.let { profile ->
                        profile.destinations.firstOrNull { it.id == route.destinationId }?.let {
                            StreamDestination(store, profile, it, editable(settings, profile, live), pop)
                        }
                    }
                    is Route.StreamReplay -> settings.profile(route.id)?.let { StreamReplay(store, it, editable(settings, it, live)) }
                    is Route.StreamPreview -> settings.profile(route.id)?.let { StreamPreview(store, it, editable(settings, it, live)) }
                    Route.Recordings -> RecordingsPage(engine)
                    Route.Scenes -> ScenesPage(store, settings, engine, push)
                    is Route.ScenePage -> settings.scenes.firstOrNull { it.id == route.id }?.let {
                        ScenePage(store, settings, it, engine, push, pop)
                    }
                    is Route.SceneVideos -> settings.scenes.firstOrNull { it.id == route.id }?.let { SceneVideosPage(store, it) }
                    is Route.Placement -> {
                        val scene = settings.scenes.firstOrNull { it.id == route.sceneId }
                        val widget = settings.widgets.firstOrNull { it.id == route.widgetId }
                        if (scene != null && widget != null) PlacementPage(store, scene, widget)
                    }
                    is Route.WidgetPage -> settings.widgets.firstOrNull { it.id == route.id }?.let {
                        WidgetPage(store, settings, it, route, engine, push, pop)
                    }
                    Route.NewWidget -> NewWidgetPage(store) { widget ->
                        pop()
                        push(Route.WidgetPage(widget.id))
                    }
                    is Route.SwitcherPage -> settings.autoSwitchers.firstOrNull { it.id == route.id }?.let {
                        SwitcherPage(store, settings, it, push, pop)
                    }
                    is Route.SwitcherScenePage -> settings.autoSwitchers.firstOrNull { it.id == route.switcherId }?.let { switcher ->
                        switcher.scenes.firstOrNull { it.id == route.id }?.let { SwitcherScenePage(store, settings, switcher, it) }
                    }
                    is Route.MacroPage -> settings.macros.firstOrNull { it.id == route.id }?.let {
                        MacroPage(store, settings, it, push, pop)
                    }
                    is Route.MacroActionPage -> settings.macros.firstOrNull { it.id == route.macroId }?.let { macro ->
                        macro.actions.firstOrNull { it.id == route.id }?.let { MacroActionPage(store, settings, macro, it, push) }
                    }
                    is Route.MacroFunctionPage -> settings.macros.firstOrNull { it.id == route.macroId }?.let { macro ->
                        macro.actions.firstOrNull { it.id == route.id }?.let { MacroFunctionPage(store, macro, it, pop) }
                    }
                    Route.TwitchAccount -> TwitchAccountPage(engine)
                    Route.KickAccount -> KickAccountPage(engine)
                    Route.YouTubeAccount -> YouTubeAccountPage(engine)
                    Route.Emotes -> EmotesPage(store, settings)
                    Route.Speech -> SpeechPage(store, settings, engine, push)
                    Route.SpeechVoices -> SpeechVoicesPage(store, settings, engine)
                    Route.PredefinedMessages -> PredefinedMessagesPage(store, settings)
                    Route.ChatBot -> ChatBotPage(store, settings, push)
                    is Route.BotCommandPage -> BotCommandPage(store, settings, route.command)
                    Route.Chat -> ChatPage(store, settings, engine, push)
                    Route.Display -> Display(store, settings, push)
                    Route.StatusItems -> Group {
                        StatusItem.entries.forEach { item ->
                            row { shapes ->
                                SwitchRow(shapes, stringResource(item.label), item !in settings.hiddenStatusItems) { shown ->
                                    store.update { it.copy(hiddenStatusItems = it.hiddenStatusItems.toggled(item, !shown)) }
                                }
                            }
                        }
                    }
                    Route.QuickButtons -> QuickButtonsPage(store, settings)
                    Route.Camera -> Camera(store, settings)
                    Route.Audio -> Microphones(store, settings, engine)
                    Route.BitratePresets -> BitratePresetsPage(store, settings)
                    Route.About -> Group {
                        row { ValueRow(it, stringResource(R.string.version), BuildConfig.VERSION_NAME) }
                        row { ValueRow(it, stringResource(R.string.based_on), "Moblin") }
                        row { shapes ->
                            val context = LocalContext.current
                            NavRow(shapes, stringResource(R.string.source_code), "GitHub") {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))) }
                            }
                        }
                        row { NavRow(it, stringResource(R.string.licenses)) { push(Route.Licenses) } }
                    }
                    Route.Licenses -> Group {
                        LICENSES.forEach { item -> row { NavRow(it, item.name, item.license) { push(Route.License(item.name)) } } }
                    }
                    is Route.License -> LICENSES.firstOrNull { it.name == route.name }?.let { LicenseText(it.file) }
                    Route.ImportExport -> ImportExport(store, engine, live, push)
                    Route.ShareStreams -> ShareStreamsPage(settings)
                    Route.RemoteControl -> RemoteControlPage(store, settings, engine, push)
                    Route.RemoteStreamer -> RemoteStreamerPage(store, settings.remoteControl)
                    Route.RemoteAssistant -> RemoteAssistantPage(store, settings.remoteControl)
                    Route.RemoteWeb -> RemoteWebPage(store, settings.remoteControl)
                    Route.Moblink -> MoblinkPage(store, settings, engine, push)
                    Route.MoblinkStreamer -> {
                        val relays by engine.moblink.relays.collectAsStateWithLifecycle()
                        MoblinkStreamerPage(store, settings.moblink, relays)
                    }
                    Route.MoblinkRelay -> MoblinkRelayPage(store, settings.moblink)
                    Route.Gamepad -> GamepadPage(settings, engine, push)
                    Route.Keyboard -> KeyboardPage(store, settings, push)
                    is Route.KeyboardKeyPage -> settings.keyboard.firstOrNull { it.id == route.id }?.let {
                        KeyboardKeyPage(store, settings, it, push, pop)
                    }
                    Route.StreamDecks -> StreamDecksPage(store, settings, engine, push)
                    is Route.StreamDeckPage -> settings.streamDecks.firstOrNull { it.id == route.id }?.let {
                        StreamDeckPage(store, settings, it, push, pop)
                    }
                    is Route.StreamDeckKeyPage -> settings.streamDecks.firstOrNull { it.id == route.layoutId }?.let {
                        StreamDeckKeyPage(store, settings, it, route.index, push)
                    }
                    is Route.ActionPage -> ActionPage(store, settings, route.target)
                }
            }
        }
    }
}

private fun <T> Set<T>.toggled(item: T, present: Boolean) = if (present) this + item else this - item

private fun editable(settings: AppSettings, profile: StreamProfile, live: Boolean) =
    !(live && profile.id == settings.selectedProfile.id)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Home(settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    val mics by engine.mics.collectAsStateWithLifecycle()
    Group {
        row {
            NavRow(
                it,
                stringResource(R.string.streams),
                settings.selectedProfile.name,
                Badge(Icons.Rounded.Sensors, Color(0xFFFF8A7A), MaterialShapes.Cookie9Sided),
            ) { push(Route.Streams) }
        }
        row {
            NavRow(
                it,
                stringResource(R.string.scenes),
                settings.selectedScene?.name,
                Badge(Icons.Rounded.Layers, Color(0xFFFFB86B), MaterialShapes.Slanted),
            ) { push(Route.Scenes) }
        }
        row {
            NavRow(it, stringResource(R.string.chat), badge = Badge(Icons.Rounded.ChatBubble, Color(0xFFFF9ECF), MaterialShapes.Pill)) {
                push(Route.Chat)
            }
        }
        row {
            NavRow(it, stringResource(R.string.display), badge = Badge(Icons.Rounded.PictureInPictureAlt, Color(0xFF8AB4FF), MaterialShapes.Square)) {
                push(Route.Display)
            }
        }
        row {
            NavRow(it, stringResource(R.string.camera), badge = Badge(Icons.Rounded.PhotoCamera, Color(0xFF7DDB8B), MaterialShapes.Clover4Leaf)) {
                push(Route.Camera)
            }
        }
        row {
            NavRow(
                it,
                stringResource(R.string.audio),
                micLabel(mics, settings.micKey),
                Badge(Icons.Rounded.GraphicEq, Color(0xFFFFC46B), MaterialShapes.Sunny),
            ) {
                push(Route.Audio)
            }
        }
    }
    Group {
        row {
            NavRow(it, stringResource(R.string.recordings), badge = Badge(Icons.Rounded.Videocam, Color(0xFF6FE3C8), MaterialShapes.Gem)) {
                push(Route.Recordings)
            }
        }
        row {
            NavRow(
                it,
                stringResource(R.string.bitrate_presets),
                badge = Badge(Icons.Rounded.Speed, Color(0xFFC3A6FF), MaterialShapes.Pentagon),
            ) {
                push(Route.BitratePresets)
            }
        }
    }
    Group {
        row {
            NavRow(
                it,
                stringResource(R.string.remote_control),
                badge = Badge(Icons.Rounded.SettingsRemote, Color(0xFF8FD3FF), MaterialShapes.Diamond),
            ) { push(Route.RemoteControl) }
        }
        row {
            NavRow(it, stringResource(R.string.moblink), badge = Badge(Icons.Rounded.Hub, Color(0xFFB5E48C), MaterialShapes.Flower)) {
                push(Route.Moblink)
            }
        }
        row {
            NavRow(
                it,
                stringResource(R.string.game_controller),
                badge = Badge(Icons.Rounded.SportsEsports, Color(0xFFFFA8A8), MaterialShapes.Ghostish),
            ) { push(Route.Gamepad) }
        }
        row {
            NavRow(it, stringResource(R.string.keyboard), badge = Badge(Icons.Rounded.Keyboard, Color(0xFFE0C3FC), MaterialShapes.Bun)) {
                push(Route.Keyboard)
            }
        }
        row {
            NavRow(
                it,
                "Stream Deck",
                settings.selectedStreamDeck?.name,
                Badge(Icons.Rounded.Apps, Color(0xFFFFD6A5), MaterialShapes.ClamShell),
            ) { push(Route.StreamDecks) }
        }
    }
    Group {
        row {
            NavRow(
                it,
                stringResource(R.string.about),
                BuildConfig.VERSION_NAME,
                Badge(Icons.Rounded.Info, Color(0xFF9FDCE8), MaterialShapes.Circle),
            ) {
                push(Route.About)
            }
        }
        row {
            NavRow(
                it,
                stringResource(R.string.import_export),
                badge = Badge(Icons.Rounded.ImportExport, Color(0xFFD6D6D6), MaterialShapes.Arch),
            ) { push(Route.ImportExport) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Streams(store: SettingsStore, settings: AppSettings, live: Boolean, push: (Route) -> Unit) {
    Group {
        settings.profiles.forEach { profile ->
            row { shapes ->
                NavRow(
                    shapes,
                    profile.name,
                    leading = {
                        RadioButton(
                            profile.id == settings.selectedProfile.id,
                            { store.update { it.copy(selectedProfileId = profile.id) } },
                            enabled = !live,
                        )
                    },
                    supporting = "${platformLabel(profile.platform)}  ${videoSummary(profile)}",
                ) { push(Route.Stream(profile.id)) }
            }
        }
    }
    Group {
        row { shapes ->
            val name = stringResource(R.string.stream_default_name, settings.profiles.size + 1)
            ActionRow(shapes, stringResource(R.string.create_stream), Icons.Rounded.Add) {
                val profile = StreamProfile(name = name)
                store.update { it.copy(profiles = it.profiles + profile) }
                push(Route.Stream(profile.id))
            }
        }
    }
}

@Composable
private fun StreamPage(
    store: SettingsStore,
    settings: AppSettings,
    profile: StreamProfile,
    live: Boolean,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    val editable = editable(settings, profile, live)
    Group {
        row { TextRow(it, stringResource(R.string.name), profile.name) { name -> store.updateProfile(profile.copy(name = name)) } }
    }
    Group {
        row { shapes ->
            val host = profile.url.substringAfter("://").substringBefore('/').substringBefore('?')
            NavRow(shapes, "URL", host.ifEmpty { stringResource(R.string.not_set) }) {
                push(Route.StreamUrl(profile.id))
            }
        }
        row { NavRow(it, stringResource(R.string.video), videoSummary(profile)) { push(Route.StreamVideo(profile.id)) } }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.audio), "${profile.audioBitrateKbps} kbps") { push(Route.StreamAudio(profile.id)) }
        }
        row { shapes ->
            NavRow(
                shapes,
                transportName(profile),
                stringResource(if (profile.adaptiveBitrate) R.string.adaptive_bitrate else R.string.fixed_bitrate),
            ) { push(Route.StreamTransport(profile.id)) }
        }
        row { shapes ->
            val channels = listOf(profile.twitchChannel, profile.youtubeChannel, profile.kickChannel).count { it.isNotBlank() }
            NavRow(
                shapes,
                stringResource(R.string.chat),
                if (channels == 0) stringResource(R.string.off) else stringResource(R.string.channels_on, channels),
            ) { push(Route.StreamChat(profile.id)) }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.recording), recordingSummary(profile)) { push(Route.StreamRecording(profile.id)) }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.obs_remote), stringResource(if (profile.obs.enabled) R.string.on else R.string.off)) {
                push(Route.StreamObs(profile.id))
            }
        }
    }
    Group {
        row { shapes ->
            val on = profile.destinations.count { it.enabled }
            NavRow(
                shapes,
                stringResource(R.string.destinations),
                if (on == 0) stringResource(R.string.off) else stringResource(R.string.channels_on, on),
            ) { push(Route.StreamDestinations(profile.id)) }
        }
        row { shapes ->
            NavRow(shapes, stringResource(R.string.replay), stringResource(if (profile.replay.enabled) R.string.on else R.string.off)) {
                push(Route.StreamReplay(profile.id))
            }
        }
        row { shapes ->
            val host = profile.previewStream.url.substringAfter("://", "").substringBefore('/')
            NavRow(shapes, stringResource(R.string.preview_stream), host.ifEmpty { stringResource(R.string.off) }) {
                push(Route.StreamPreview(profile.id))
            }
        }
    }
    Group {
        row { shapes ->
            val context = LocalContext.current
            ActionRow(shapes, stringResource(R.string.share_link), Icons.Rounded.Share) {
                runCatching {
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, createDeepLink(listOf(profile))),
                            null,
                        ),
                    )
                }
            }
        }
        row { shapes ->
            val name = stringResource(R.string.copy_name, profile.name)
            ActionRow(shapes, stringResource(R.string.duplicate), Icons.Rounded.ContentCopy) {
                val copy = profile.copy(id = UUID.randomUUID().toString(), name = name)
                store.update { it.copy(profiles = it.profiles + copy) }
                pop()
            }
        }
        if (settings.profiles.size > 1) {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error, editable) {
                    pop()
                    store.update { s -> s.copy(profiles = s.profiles.filterNot { it.id == profile.id }) }
                }
            }
        }
    }
}

@Composable
private fun StreamUrl(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val update: (StreamProfile) -> Unit = store::updateProfile
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.platform),
                Platform.entries,
                profile.platform,
                { platformLabel(it) },
                enabled = editable,
            ) { platform ->
                update(
                    profile.copy(
                        platform = platform,
                        url = if (profile.url.isBlank() || Platform.entries.any { it.defaultUrl == profile.url }) {
                            platform.defaultUrl
                        } else {
                            profile.url
                        },
                        codec = profile.codec?.takeIf { it in platform.codecs },
                    ),
                )
            }
        }
        row { shapes ->
            TextRow(shapes, "URL", profile.url, "rtmps://, srt://, srtla://, rist://, whip://", editable, KeyboardType.Uri) {
                update(profile.copy(url = it.trim()))
            }
        }
        keyLabel(profile.url)?.let { label ->
            row { shapes ->
                TextRow(shapes, stringResource(label), profile.streamKey, enabled = editable, keyboard = KeyboardType.Password) {
                    update(profile.copy(streamKey = it.trim()))
                }
            }
        }
    }
}

@Composable
private fun StreamVideo(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val update: (StreamProfile) -> Unit = store::updateProfile
    val (w, h) = outputSize(profile)
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.resolution),
                Resolution.entries.reversed(),
                profile.resolution,
                { it.label },
                enabled = editable,
            ) {
                update(profile.copy(resolution = it))
            }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.frame_rate), fpsChoices, profile.fps, { "$it" }, enabled = editable) {
                update(profile.copy(fps = it))
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.portrait), profile.portrait, editable) { on -> update(profile.copy(portrait = on)) }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                "Codec",
                listOf<VideoCodec?>(null) + profile.allowedCodecs.reversed(),
                profile.codec,
                {
                    it?.label
                        ?: stringResource(R.string.codec_auto, CodecCatalog.choose(null, profile.allowedCodecs, w, h, profile.fps).label)
                },
                { it == null || CodecCatalog.available(it, w, h, profile.fps) },
                editable,
            ) { update(profile.copy(codec = it)) }
        }
    }
    Group {
        row { shapes ->
            BitrateRow(shapes, "Bitrate", profile.bitrateKbps, 1000..20000, editable) { update(profile.copy(bitrateKbps = it)) }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.key_frame_interval),
                listOf(1, 2, 3, 4, 5),
                profile.keyFrameIntervalSeconds,
                { "$it s" },
                enabled = editable,
            ) {
                update(profile.copy(keyFrameIntervalSeconds = it))
            }
        }
    }
}

@Composable
private fun StreamAudio(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    Group {
        row { shapes ->
            ChoiceRow(shapes, "Bitrate", listOf(96, 128, 160, 192, 256), profile.audioBitrateKbps, { "$it kbps" }, enabled = editable) {
                store.updateProfile(profile.copy(audioBitrateKbps = it))
            }
        }
    }
}

@Composable
private fun cameraLabel(engine: StreamEngine, scene: Scene): String = if (scene.video) {
    stringResource(R.string.video)
} else {
    engine.ui.value.cameras.firstOrNull { it.key == scene.cameraKey }?.label ?: stringResource(R.string.source_default)
}

@Composable
private fun ScenesPage(store: SettingsStore, settings: AppSettings, engine: StreamEngine, push: (Route) -> Unit) {
    Group {
        settings.scenes.forEach { scene ->
            row { shapes ->
                val count = scene.widgets.count { it.enabled }
                NavRow(
                    shapes,
                    scene.name,
                    "${cameraLabel(engine, scene)}, ${pluralStringResource(R.plurals.widget_count, count, count)}",
                ) {
                    push(Route.ScenePage(scene.id))
                }
            }
        }
        row { shapes ->
            val name = stringResource(R.string.scene_default_name, settings.scenes.size + 1)
            ActionRow(shapes, stringResource(R.string.create_scene), Icons.Rounded.Add) {
                val scene = Scene(name = name, cameraKey = settings.selectedScene?.cameraKey)
                store.update { it.copy(scenes = it.scenes + scene) }
                push(Route.ScenePage(scene.id))
            }
        }
    }
    Group {
        settings.widgets.forEach { widget ->
            row { shapes -> NavRow(shapes, widget.name, stringResource(widget.type.label)) { push(Route.WidgetPage(widget.id)) } }
        }
        row { shapes -> ActionRow(shapes, stringResource(R.string.create_widget), Icons.Rounded.Add) { push(Route.NewWidget) } }
    }
    AutomationGroups(store, settings, push)
}

@Composable
private fun NewWidgetPage(store: SettingsStore, onCreated: (Widget) -> Unit) {
    Group {
        WidgetType.entries.forEach { type ->
            row { shapes ->
                val name = stringResource(type.label)
                NavRow(
                    shapes,
                    name,
                    leading = { Icon(type.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) },
                    supporting = stringResource(type.description),
                ) {
                    val widget = Widget(name = name, type = type)
                    store.update { it.copy(widgets = it.widgets + widget) }
                    onCreated(widget)
                }
            }
        }
    }
}

@Composable
private fun ScenePage(
    store: SettingsStore,
    settings: AppSettings,
    scene: Scene,
    engine: StreamEngine,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameras = ui.cameras.filter { it.facing != Facing.EXTERNAL }
    val update = { changed: Scene -> store.update { s -> s.copy(scenes = s.scenes.map { if (it.id == scene.id) changed else it }) } }
    Group {
        row { TextRow(it, stringResource(R.string.name), scene.name) { name -> update(scene.copy(name = name)) } }
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.source),
                cameras.map { it.key } + VIDEO_SOURCE,
                if (scene.video) VIDEO_SOURCE else scene.cameraKey,
                { key -> if (key == VIDEO_SOURCE) stringResource(R.string.video) else cameras.firstOrNull { it.key == key }?.label.orEmpty() },
            ) { update(if (it == VIDEO_SOURCE) scene.copy(video = true) else scene.copy(video = false, cameraKey = it)) }
        }
        if (scene.video) {
            row { shapes ->
                NavRow(
                    shapes,
                    stringResource(R.string.videos),
                    pluralStringResource(R.plurals.file_count, scene.videos.size, scene.videos.size),
                ) {
                    push(Route.SceneVideos(scene.id))
                }
            }
        }
    }
    if (settings.widgets.isNotEmpty()) {
        Group {
            settings.widgets.forEach { widget ->
                val placement = scene.placement(widget)
                row { shapes ->
                    SwitchNavRow(
                        shapes,
                        widget.name,
                        placement.enabled,
                        { on -> store.update { it.withPlacement(scene.id, placement.copy(enabled = on)) } },
                    ) {
                        store.update { it.withPlacement(scene.id, placement) }
                        push(Route.Placement(scene.id, widget.id))
                    }
                }
            }
        }
    }
    Group {
        row { shapes ->
            val name = stringResource(R.string.copy_name, scene.name)
            ActionRow(shapes, stringResource(R.string.duplicate), Icons.Rounded.ContentCopy) {
                val copy = scene.copy(id = UUID.randomUUID().toString(), name = name)
                store.update { it.copy(scenes = it.scenes + copy) }
                pop()
            }
        }
        if (settings.scenes.size > 1) {
            row { shapes ->
                ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                    pop()
                    store.update { s -> s.copy(scenes = s.scenes.filterNot { it.id == scene.id }) }
                    releaseUnusedVideos(context, store, scene.videos)
                }
            }
        }
    }
}

private const val VIDEO_SOURCE = "video"

private class LicenseItem(val name: String, val license: String, val file: String)

private val LICENSES = listOf(
    LicenseItem("Moblin", "MIT", "moblin.txt"),
    LicenseItem("SolidJS, Tailwind CSS, tailwind-merge", "MIT", "web-remote.txt"),
    LicenseItem("libusb", "LGPL 2.1", "libusb.txt"),
    LicenseItem("libuvc", "BSD", "libuvc.txt"),
    LicenseItem("libjpeg-turbo", "BSD, IJG, zlib", "libjpeg-turbo.txt"),
    LicenseItem("Bouncy Castle", "MIT", "bouncycastle.txt"),
    LicenseItem("OkHttp", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("Okio", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("Coil", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("Kotlin and JetBrains libraries", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("AndroidX and Compose", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("Accompanist", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("Guava", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("JSpecify", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("ZXing", "Apache 2.0", "apache-2.0.txt"),
    LicenseItem("ML Kit", "ML Kit terms", "mlkit.txt"),
    LicenseItem("Google Play services", "Android SDK License", "android-sdk.txt"),
    LicenseItem("OBS logo", "GPL 2.0", "gpl-2.0.txt"),
    LicenseItem("Nunito", "OFL 1.1", "ofl-nunito.txt"),
    LicenseItem("Filament", "Apache 2.0", "apache-2.0.txt"),
)

@Composable
private fun LicenseText(file: String) {
    val context = LocalContext.current
    val text by produceState("", file) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }.getOrDefault("")
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun releaseUnusedVideos(context: Context, store: SettingsStore, videos: List<SceneVideo>) {
    val used = store.settings.value.scenes.flatMap { it.videos }.map { it.uri }.toSet()
    videos.filter { it.uri !in used }.forEach { video ->
        runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(video.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

@Composable
private fun SceneVideosPage(store: SettingsStore, scene: Scene) {
    val context = LocalContext.current
    val update = { changed: Scene -> store.update { s -> s.copy(scenes = s.scenes.map { if (it.id == scene.id) changed else it }) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val added = uris.filter { uri -> scene.videos.none { it.uri == uri.toString() } }.map { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
            }.getOrNull()
            SceneVideo(uri.toString(), name ?: uri.lastPathSegment ?: "Video")
        }
        if (added.isNotEmpty()) store.update { s -> s.copy(scenes = s.scenes.map { if (it.id == scene.id) it.copy(videos = it.videos + added) else it }) }
    }
    Group {
        scene.videos.forEach { video ->
            row { shapes ->
                RemovableRow(shapes, video.name, "", null) {
                    store.update { s ->
                        s.copy(scenes = s.scenes.map { if (it.id == scene.id) it.copy(videos = it.videos - video) else it })
                    }
                    releaseUnusedVideos(context, store, listOf(video))
                }
            }
        }
        row { ActionRow(it, stringResource(R.string.add_video), Icons.Rounded.Add) { pick.launch(arrayOf("video/*")) } }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.video_volume), scene.videoVolume, 0..100, "${scene.videoVolume}%") {
                update(scene.copy(videoVolume = it))
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.mic_while_playing), scene.micWithVideo) { on -> update(scene.copy(micWithVideo = on)) }
        }
    }
}

@Composable
private fun PlacementPage(store: SettingsStore, scene: Scene, widget: Widget) {
    val placement = scene.placement(widget)
    val update = { changed: SceneWidget -> store.update { it.withPlacement(scene.id, changed) } }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.corner), Corner.entries, placement.corner, { stringResource(it.label) }) {
                update(placement.copy(corner = it))
            }
        }
        row { SliderRow(it, "X", placement.x, 0..100, "${placement.x}%") { x -> update(placement.copy(x = x)) } }
        row { SliderRow(it, "Y", placement.y, 0..100, "${placement.y}%") { y -> update(placement.copy(y = y)) } }
        if (widget.type.drawer.sized) {
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.size), placement.size, 5..100, "${placement.size}%") { size ->
                    update(placement.copy(size = size))
                }
            }
        }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.show_in_scene), placement.enabled) { on -> update(placement.copy(enabled = on)) }
        }
    }
}

@Composable
private fun WidgetPage(
    store: SettingsStore,
    settings: AppSettings,
    widget: Widget,
    route: Route.WidgetPage,
    engine: StreamEngine,
    push: (Route) -> Unit,
    pop: () -> Unit,
) {
    val scope = WidgetScope(store, engine, settings, widget, route.page, { page, title -> push(Route.WidgetPage(widget.id, page, title)) }, pop)
    if (route.page != null) {
        WidgetSettings(scope)
        return
    }
    Group {
        row { TextRow(it, stringResource(R.string.name), widget.name) { name -> scope.update { w -> w.copy(name = name) } } }
        row { ValueRow(it, stringResource(R.string.type), stringResource(widget.type.label)) }
    }
    WidgetSettings(scope)
    if (widget.type == WidgetType.IMAGE || widget.type == WidgetType.BROWSER) {
        Group {
            row { shapes ->
                SwitchRow(shapes, stringResource(R.string.remove_background), widget.removeBackground) { on ->
                    scope.update { it.copy(removeBackground = on) }
                }
            }
            if (widget.removeBackground) {
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.hue_from), widget.keyFrom, 0..359, "${widget.keyFrom}°") { hue ->
                        scope.update { it.copy(keyFrom = hue) }
                    }
                }
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.hue_to), widget.keyTo, 0..359, "${widget.keyTo}°") { hue ->
                        scope.update { it.copy(keyTo = hue) }
                    }
                }
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.show_on),
                listOf(false, true),
                widget.previewOnly,
                { stringResource(if (it) R.string.preview_only else R.string.stream_and_preview) },
            ) { previewOnly -> scope.update { it.copy(previewOnly = previewOnly) } }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error) {
                pop()
                store.update { it.withoutWidget(widget.id) }
                widgetFiles(engine.widgets, widget.id).forEach { it.delete() }
            }
        }
    }
}

private fun recordingCodec(profile: StreamProfile): VideoCodec {
    val (w, h) = outputSize(profile)
    return CodecCatalog.choose(profile.recording.codec, RECORDING_CODECS, w, h, profile.fps)
}

@Composable
private fun recordingSummary(profile: StreamProfile) = if (profile.recording.separate) {
    "${recordingCodec(profile).label} ${formatMbps(profile.recording.bitrateKbps)} Mbps"
} else {
    stringResource(R.string.same_as_stream)
}

@Composable
private fun StreamRecording(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val recording = profile.recording
    val update = { changed: RecordingSettings -> store.updateProfile(profile.copy(recording = changed)) }
    val (w, h) = outputSize(profile)
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.quality),
                listOf(false, true),
                recording.separate,
                { stringResource(if (it) R.string.separate else R.string.same_as_stream) },
                enabled = editable,
            ) { update(recording.copy(separate = it)) }
        }
        if (recording.separate) {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    "Codec",
                    listOf<VideoCodec?>(null) + RECORDING_CODECS.reversed(),
                    recording.codec,
                    {
                        it?.label
                            ?: stringResource(R.string.codec_auto, recordingCodec(profile.copy(recording = recording.copy(codec = null))).label)
                    },
                    { it == null || CodecCatalog.available(it, w, h, profile.fps) },
                    editable,
                ) { update(recording.copy(codec = it)) }
            }
            row { shapes ->
                BitrateRow(shapes, "Bitrate", recording.bitrateKbps, 2000..60000, editable) { update(recording.copy(bitrateKbps = it)) }
            }
        }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.start_when_live), recording.startWithStream, editable) {
                update(recording.copy(startWithStream = it))
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.stop_when_ends), recording.stopWithStream, editable) {
                update(recording.copy(stopWithStream = it))
            }
        }
    }
}

@Composable
private fun StreamObs(store: SettingsStore, engine: StreamEngine, profile: StreamProfile) {
    val obs = profile.obs
    val update = { changed: ObsSettings -> store.updateProfile(profile.copy(obs = changed)) }
    LocalNetworkAccess(engine)
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), obs.enabled) { on -> update(obs.copy(enabled = on)) } }
    }
    Group {
        row { shapes ->
            TextRow(shapes, "URL", obs.url, "ws://192.168.1.20:4455", keyboard = KeyboardType.Uri) { update(obs.copy(url = it.trim())) }
        }
        row { shapes ->
            TextRow(shapes, stringResource(R.string.password), obs.password, keyboard = KeyboardType.Password) { update(obs.copy(password = it)) }
        }
    }
    Group {
        row { SwitchRow(it, stringResource(R.string.obs_start_stream), obs.startStream) { on -> update(obs.copy(startStream = on)) } }
        row { SwitchRow(it, stringResource(R.string.obs_stop_stream), obs.stopStream) { on -> update(obs.copy(stopStream = on)) } }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.obs_start_recording), obs.startRecording) { on -> update(obs.copy(startRecording = on)) }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.obs_stop_recording), obs.stopRecording) { on -> update(obs.copy(stopRecording = on)) }
        }
    }
}

@Composable
private fun RecordingThumbnail(uri: Uri) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(192, 108), null).asImageBitmap() }.getOrNull()
        }
    }
    Box(Modifier.size(64.dp, 36.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecordingsPage(engine: StreamEngine) {
    val recordings by engine.recordings.recordings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Recording?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { engine.recordings.refresh() } }
    if (recordings.isEmpty()) {
        Text(stringResource(R.string.no_recordings), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
    } else {
        Group {
            recordings.forEach { recording ->
                row { shapes ->
                    SegmentedListItem(
                        contentPadding = RowPadding,
                        colors = rowColors(),
                        shapes = shapes,
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(recording.uri, "video/mp4")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            }
                        },
                        modifier = Modifier.heightIn(max = TwoLineRowHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        leadingContent = { RecordingThumbnail(recording.uri) },
                        supportingContent = {
                            Text("${formatUptime(recording.durationMs / 1000)}  ${formatBytes(recording.bytes)}", maxLines = 1)
                        },
                        trailingContent = {
                            IconButton({ deleting = recording }) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.delete_item, recording.name))
                            }
                        },
                    ) { Text(recording.name, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
    Group { row { ValueRow(it, stringResource(R.string.saved_to), "Movies/opIRL") } }
    deleting?.let { recording ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_recording_title)) },
            text = { Text(recording.name) },
            confirmButton = {
                TextButton({
                    deleting = null
                    scope.launch(Dispatchers.IO) { engine.recordings.delete(recording.uri) }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun StreamTransport(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val update: (StreamProfile) -> Unit = store::updateProfile
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.adaptive_bitrate), profile.adaptiveBitrate, editable) { on ->
                update(profile.copy(adaptiveBitrate = on))
            }
        }
        if (profile.adaptiveBitrate) {
            row { shapes ->
                BitrateRow(shapes, stringResource(R.string.minimum_bitrate), profile.minBitrateKbps, 250..profile.bitrateKbps, editable) {
                    update(profile.copy(minBitrateKbps = it))
                }
            }
        }
    }
    if (profile.transport == Transport.SRT || profile.transport == Transport.SRTLA) {
        Group {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.latency),
                    (listOf(1000, 2000, 3000, 4000, 6000, 8000) + profile.srtLatencyMs).distinct().sorted(),
                    profile.srtLatencyMs,
                    { "${formatMbps(it)} s" },
                    enabled = editable,
                ) { update(profile.copy(srtLatencyMs = it)) }
            }
        }
    }
    if (profile.transport == Transport.RIST) {
        Group {
            row { shapes ->
                SwitchRow(shapes, stringResource(R.string.status_bonding), profile.ristBonding, editable) { on ->
                    update(profile.copy(ristBonding = on))
                }
            }
        }
    }
    if (profile.bonded) {
        Group {
            val networks = listOf(
                LinkType.WIFI to R.string.network_wifi,
                LinkType.CELLULAR to R.string.network_cellular,
                LinkType.ETHERNET to R.string.network_ethernet,
            )
            for ((type, label) in networks) {
                row { shapes ->
                    SwitchRow(shapes, stringResource(R.string.bond_over, stringResource(label)), type in profile.bondingNetworks, editable) { on ->
                        val networks = profile.bondingNetworks.toggled(type, on)
                        update(profile.copy(bondingNetworks = networks.ifEmpty { BONDING_NETWORKS }))
                    }
                }
            }
        }
    }
}

@Composable
private fun Display(store: SettingsStore, settings: AppSettings, push: (Route) -> Unit) {
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.keep_screen_on), settings.keepScreenOn) { on ->
                store.update { s -> s.copy(keepScreenOn = on) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.vibrate_on_alerts), settings.alertHaptics) { on ->
                store.update { s -> s.copy(alertHaptics = on) }
            }
        }
    }
    Group {
        row { shapes ->
            NavRow(
                shapes,
                stringResource(R.string.status),
                stringResource(R.string.shown_count, StatusItem.entries.size - settings.hiddenStatusItems.size),
            ) {
                push(Route.StatusItems)
            }
        }
        row { shapes ->
            NavRow(
                shapes,
                stringResource(R.string.quick_buttons),
                stringResource(R.string.shown_count, QuickButtonId.entries.size - settings.hiddenQuickButtons.size),
            ) {
                push(Route.QuickButtons)
            }
        }
    }
}

@Composable
private fun Camera(store: SettingsStore, settings: AppSettings) {
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.stabilization),
                StabilizationSetting.entries,
                settings.stabilization,
                {
                    stringResource(
                        when (it) {
                            StabilizationSetting.OFF -> R.string.off
                            StabilizationSetting.STANDARD -> R.string.stabilization_standard
                            StabilizationSetting.PREVIEW -> R.string.stabilization_enhanced
                        },
                    )
                },
            ) { value -> store.update { it.copy(stabilization = value) } }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.mirror_front), settings.mirrorFrontCameraOnStream) { on ->
                store.update { it.copy(mirrorFrontCameraOnStream = on) }
            }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.usb_rotation), listOf(0, 90, 180, 270), settings.usbCameraRotation, { "$it°" }) { value ->
                store.update { it.copy(usbCameraRotation = value) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.fixed_horizon), settings.fixedHorizon) { on -> store.update { it.copy(fixedHorizon = on) } }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BitratePresetsPage(store: SettingsStore, settings: AppSettings) {
    var kbps by remember { mutableIntStateOf(5000) }
    Group {
        row { BitrateRow(it, stringResource(R.string.new_preset), kbps, 250..30000) { value -> kbps = value } }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_preset), Icons.Rounded.Add, enabled = kbps !in settings.bitratePresetsKbps) {
                store.update { it.copy(bitratePresetsKbps = (it.bitratePresetsKbps + kbps).sorted()) }
            }
        }
    }
    Group {
        settings.bitratePresetsKbps.forEach { preset ->
            row { shapes ->
                SegmentedListItem(
                    contentPadding = RowPadding,
                    colors = rowColors(),
                    shapes = shapes,
                    modifier = Modifier.heightIn(max = RowHeight),
                    trailingContent = {
                        IconButton(
                            { store.update { it.copy(bitratePresetsKbps = it.bitratePresetsKbps - preset) } },
                            enabled = settings.bitratePresetsKbps.size > 1,
                        ) { Icon(Icons.Rounded.Delete, stringResource(R.string.delete_item, "${formatMbps(preset)} Mbps")) }
                    },
                ) { Text("${formatMbps(preset)} Mbps", style = RowTitle) }
            }
        }
    }
}

@Composable
private fun ImportExport(store: SettingsStore, engine: StreamEngine, live: Boolean, push: (Route) -> Unit) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<Int?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(store.exportJson().toByteArray()) }
            }.fold({ R.string.settings_exported }, { R.string.export_failed })
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            message = if (text.isSuccess && store.importJson(text.getOrThrow())) {
                R.string.settings_imported
            } else {
                R.string.not_settings_file
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.export_settings), Icons.Rounded.FileUpload) { export.launch("opirl-settings.json") }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.import_settings), Icons.Rounded.FileDownload, enabled = !live) {
                import.launch(arrayOf("*/*"))
            }
        }
    }
    Group {
        row { NavRow(it, stringResource(R.string.share_streams)) { push(Route.ShareStreams) } }
        row { ImportLinkRow(it, engine, !live) }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.reset_settings), Icons.Rounded.RestartAlt, MaterialTheme.colorScheme.error, !live) {
                confirmReset = true
            }
        }
    }
    message?.let {
        Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp))
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_title)) },
            text = { Text(stringResource(R.string.reset_text)) },
            confirmButton = {
                TextButton({
                    confirmReset = false
                    store.update { AppSettings() }
                    message = R.string.settings_reset
                }) { Text(stringResource(R.string.reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun keyLabel(url: String): Int? = when (transportOf(url)) {
    Transport.RTMP -> R.string.stream_key
    Transport.WHIP -> R.string.token
    else -> null
}

@Composable
private fun Microphones(store: SettingsStore, settings: AppSettings, engine: StreamEngine) {
    val mics by engine.mics.collectAsStateWithLifecycle()
    val selected = findMic(mics, settings.micKey)
    Group {
        row { shapes ->
            NavRow(
                shapes,
                stringResource(R.string.mic_auto),
                leading = { RadioButton(settings.micKey == null, { store.update { it.copy(micKey = null) } }) },
            ) { store.update { it.copy(micKey = null) } }
        }
        mics.forEach { mic ->
            row { shapes ->
                NavRow(
                    shapes,
                    micLabel(mic),
                    leading = { RadioButton(mic == selected, { store.update { it.copy(micKey = mic.key) } }) },
                ) { store.update { it.copy(micKey = mic.key) } }
            }
        }
        if (settings.micKey != null && selected == null) {
            row { shapes ->
                NavRow(
                    shapes,
                    micLabel(mics, settings.micKey),
                    leading = { RadioButton(true, {}) },
                    supporting = stringResource(R.string.mic_not_connected),
                ) {}
            }
        }
    }
}

@Composable
private fun StreamDestinations(
    store: SettingsStore,
    profile: StreamProfile,
    engine: StreamEngine,
    editable: Boolean,
    push: (Route) -> Unit,
) {
    val states by engine.destinations.collectAsStateWithLifecycle()
    if (profile.destinations.isNotEmpty()) {
        Group {
            profile.destinations.forEach { destination ->
                row { shapes ->
                    val state = states[destination.id]
                    if (state != null) {
                        ValueRow(shapes, destination.name, stringResource(liveStateLabel(state)))
                    } else {
                        SwitchNavRow(
                            shapes,
                            destination.name,
                            destination.enabled,
                            { on ->
                                if (editable) {
                                    store.updateProfile(
                                        profile.copy(
                                            destinations = profile.destinations.map {
                                                if (it.id == destination.id) it.copy(enabled = on) else it
                                            },
                                        ),
                                    )
                                }
                            },
                        ) { push(Route.StreamDestination(profile.id, destination.id)) }
                    }
                }
            }
        }
    }
    Group {
        row { shapes ->
            val name = stringResource(R.string.destination_default_name, profile.destinations.size + 1)
            ActionRow(shapes, stringResource(R.string.add_destination), Icons.Rounded.Add, enabled = editable) {
                val destination = Destination(name = name)
                store.updateProfile(profile.copy(destinations = profile.destinations + destination))
                push(Route.StreamDestination(profile.id, destination.id))
            }
        }
    }
}

private fun liveStateLabel(state: LiveState) = when (state) {
    LiveState.LIVE -> R.string.notification_live
    LiveState.OFFLINE -> R.string.off
    LiveState.CONNECTING -> R.string.connecting
    LiveState.RECONNECTING -> R.string.reconnecting
}

@Composable
private fun StreamDestination(
    store: SettingsStore,
    profile: StreamProfile,
    destination: Destination,
    editable: Boolean,
    pop: () -> Unit,
) {
    val update = { changed: Destination ->
        store.updateProfile(profile.copy(destinations = profile.destinations.map { if (it.id == changed.id) changed else it }))
    }
    Group {
        row { TextRow(it, stringResource(R.string.name), destination.name) { name -> update(destination.copy(name = name)) } }
    }
    Group {
        row { shapes ->
            TextRow(shapes, "URL", destination.url, "rtmps://", editable, KeyboardType.Uri) { update(destination.copy(url = it.trim())) }
        }
        keyLabel(destination.url)?.let { label ->
            row { shapes ->
                TextRow(shapes, stringResource(label), destination.streamKey, enabled = editable, keyboard = KeyboardType.Password) {
                    update(destination.copy(streamKey = it.trim()))
                }
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.delete), Icons.Rounded.Delete, MaterialTheme.colorScheme.error, editable) {
                pop()
                store.updateProfile(profile.copy(destinations = profile.destinations.filterNot { it.id == destination.id }))
            }
        }
    }
}

@Composable
private fun StreamReplay(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val replay = profile.replay
    val update = { changed: com.opirl.app.settings.ReplaySettings -> store.updateProfile(profile.copy(replay = changed)) }
    Group {
        row { SwitchRow(it, stringResource(R.string.enabled), replay.enabled, editable) { on -> update(replay.copy(enabled = on)) } }
    }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.replay_length), REPLAY_SECONDS, replay.seconds, { "$it s" }) {
                update(replay.copy(seconds = it))
            }
        }
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.replay_delay), listOf(0, 1, 2, 3, 5), replay.delaySeconds, { "$it s" }) {
                update(replay.copy(delaySeconds = it))
            }
        }
    }
}

@Composable
private fun StreamPreview(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val preview = profile.previewStream
    val update = { changed: com.opirl.app.settings.PreviewStreamSettings -> store.updateProfile(profile.copy(previewStream = changed)) }
    Group {
        row { shapes ->
            TextRow(shapes, "URL", preview.url, "whip://", editable, KeyboardType.Uri) { update(preview.copy(url = it.trim())) }
        }
        row { shapes ->
            TextRow(shapes, stringResource(R.string.token), preview.token, enabled = editable, keyboard = KeyboardType.Password) {
                update(preview.copy(token = it.trim()))
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.resolution), PREVIEW_RESOLUTIONS, preview.resolution, { it.label }, enabled = editable) {
                update(preview.copy(resolution = it))
            }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                "Bitrate (kbps)",
                listOf(250, 500, 1000, 1500, 2500),
                preview.bitrateKbps,
                { "$it" },
                enabled = editable,
            ) { update(preview.copy(bitrateKbps = it)) }
        }
    }
}

@Composable
private fun QuickButtonsPage(store: SettingsStore, settings: AppSettings) {
    var moving by remember { mutableStateOf<QuickButtonId?>(null) }
    QuickButtonPage.entries.forEach { page ->
        val items = QuickButtonId.entries.filter { settings.pageOf(it) == page }
        if (items.isEmpty()) return@forEach
        Group {
            items.forEach { item ->
                row { shapes ->
                    SwitchNavRow(
                        shapes,
                        stringResource(item.label),
                        item !in settings.hiddenQuickButtons,
                        { shown -> store.update { it.copy(hiddenQuickButtons = it.hiddenQuickButtons.toggled(item, !shown)) } },
                        value = stringResource(page.label),
                    ) { moving = item }
                }
            }
        }
    }
    moving?.let { item ->
        ChoiceDialog(stringResource(item.label), QuickButtonPage.entries, settings.pageOf(item), { stringResource(it.label) }, { page ->
            store.update { it.copy(quickButtonPages = if (page == item.page) it.quickButtonPages - item else it.quickButtonPages + (item to page)) }
        }) { moving = null }
    }
}
