package app.mobdroid.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.BuildConfig
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.net.LinkType
import app.mobdroid.media.CodecCatalog
import app.mobdroid.media.label
import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.BONDING_NETWORKS
import app.mobdroid.settings.ChatTextSize
import app.mobdroid.settings.MicSource
import app.mobdroid.settings.Platform
import app.mobdroid.settings.QuickButtonId
import app.mobdroid.settings.Resolution
import app.mobdroid.settings.SettingsStore
import app.mobdroid.settings.StabilizationSetting
import app.mobdroid.settings.StatusItem
import app.mobdroid.settings.StreamProfile
import app.mobdroid.settings.allowedCodecs
import app.mobdroid.settings.fpsChoices
import app.mobdroid.stream.outputSize
import java.util.UUID

private sealed interface Route {
    data object Home : Route

    data object Streams : Route

    data class Stream(val id: String) : Route

    data class StreamUrl(val id: String) : Route

    data class StreamVideo(val id: String) : Route

    data class StreamAudio(val id: String) : Route

    data class StreamTransport(val id: String) : Route

    data class StreamChat(val id: String) : Route

    data object Chat : Route

    data object Display : Route

    data object StatusItems : Route

    data object QuickButtons : Route

    data object Camera : Route

    data object Audio : Route

    data object BitratePresets : Route

    data object About : Route

    data object ImportExport : Route
}

private fun AppSettings.profile(id: String) = profiles.firstOrNull { it.id == id }

private fun transportName(profile: StreamProfile) = when {
    profile.url.startsWith("srtla") -> "SRTLA"
    profile.url.startsWith("srt") -> "SRT"
    else -> "RTMP"
}

private fun title(route: Route, settings: AppSettings) = when (route) {
    Route.Home -> "Settings"
    Route.Streams -> "Streams"
    is Route.Stream -> settings.profile(route.id)?.name.orEmpty()
    is Route.StreamUrl -> "URL"
    is Route.StreamVideo -> "Video"
    is Route.StreamAudio -> "Audio"
    is Route.StreamTransport -> settings.profile(route.id)?.let(::transportName).orEmpty()
    is Route.StreamChat -> "Chat"
    Route.Chat -> "Chat"
    Route.Display -> "Display"
    Route.StatusItems -> "Status"
    Route.QuickButtons -> "Quick buttons"
    Route.Camera -> "Camera"
    Route.Audio -> "Audio"
    Route.BitratePresets -> "Bitrate presets"
    Route.About -> "About"
    Route.ImportExport -> "Import and export settings"
}

private fun videoSummary(profile: StreamProfile): String {
    val (w, h) = outputSize(profile)
    val codec = CodecCatalog.choose(profile.codec, profile.allowedCodecs, w, h, profile.fps)
    return "${profile.resolution.label}${profile.fps} ${codec.label} ${formatMbps(profile.bitrateKbps)} Mbps"
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsPanel(store: SettingsStore, live: Boolean, onClose: () -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val push: (Route) -> Unit = { stack += it }
    val pop = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { pop() }
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<IntOffset>()
    val fastSpatial = motion.fastSpatialSpec<IntOffset>()
    val effects = motion.defaultEffectsSpec<Float>()
    val fastEffects = motion.fastEffectsSpec<Float>()
    Surface(
        Modifier.fillMaxSize(),
        color = Color.Black,
    ) {
        Column {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    IconButton({ pop() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Navigate back") }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
                Text(
                    title(stack.last(), settings),
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClose) { Icon(Icons.Rounded.Close, "Close") }
            }
            AnimatedContent(
                targetState = stack.size to stack.last(),
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
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (route) {
                        Route.Home -> Home(settings, push)
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
                                    TextRow(shapes, "Twitch channel", profile.twitchChannel) {
                                        store.updateProfile(profile.copy(twitchChannel = it.trim()))
                                    }
                                }
                                row { shapes ->
                                    TextRow(shapes, "YouTube handle or video ID", profile.youtubeChannel) {
                                        store.updateProfile(profile.copy(youtubeChannel = it.trim()))
                                    }
                                }
                                row { shapes ->
                                    TextRow(shapes, "Kick channel", profile.kickChannel) {
                                        store.updateProfile(profile.copy(kickChannel = it.trim()))
                                    }
                                }
                            }
                        }
                        Route.Chat -> Group {
                            row { shapes ->
                                ChoiceRow(shapes, "Text size", ChatTextSize.entries, settings.chatTextSize, { it.label }) { size ->
                                    store.update { it.copy(chatTextSize = size) }
                                }
                            }
                            row { shapes ->
                                SwitchRow(shapes, "Show on the preview on small screens", settings.chatOnPreview) { on ->
                                    store.update { it.copy(chatOnPreview = on) }
                                }
                            }
                            row { shapes ->
                                SwitchRow(shapes, "Text to speech", settings.chatSpeech) { on -> store.update { it.copy(chatSpeech = on) } }
                            }
                            if (settings.chatSpeech) {
                                row { shapes ->
                                    SwitchRow(shapes, "Say who wrote the message", settings.chatSpeechNames) { on ->
                                        store.update { it.copy(chatSpeechNames = on) }
                                    }
                                }
                            }
                        }
                        Route.Display -> Display(store, settings, push)
                        Route.StatusItems -> Group {
                            StatusItem.entries.forEach { item ->
                                row { shapes ->
                                    SwitchRow(shapes, item.label, item !in settings.hiddenStatusItems) { shown ->
                                        store.update { it.copy(hiddenStatusItems = it.hiddenStatusItems.toggled(item, !shown)) }
                                    }
                                }
                            }
                        }
                        Route.QuickButtons -> Group {
                            QuickButtonId.entries.forEach { item ->
                                row { shapes ->
                                    SwitchRow(shapes, item.label, item !in settings.hiddenQuickButtons) { shown ->
                                        store.update { it.copy(hiddenQuickButtons = it.hiddenQuickButtons.toggled(item, !shown)) }
                                    }
                                }
                            }
                        }
                        Route.Camera -> Camera(store, settings)
                        Route.Audio -> Group {
                            row { shapes ->
                                ChoiceRow(shapes, "Microphone", MicSource.entries, settings.mic, { it.label }, enabled = !live) { mic ->
                                    store.update { it.copy(mic = mic) }
                                }
                            }
                        }
                        Route.BitratePresets -> BitratePresetsPage(store, settings)
                        Route.About -> Group {
                            row { ValueRow(it, "Version", BuildConfig.VERSION_NAME) }
                            row { ValueRow(it, "Based on", "Moblin") }
                            row { ValueRow(it, "License", "MIT") }
                        }
                        Route.ImportExport -> ImportExport(store, live)
                    }
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
private fun Home(settings: AppSettings, push: (Route) -> Unit) {
    Group {
        row {
            NavRow(
                it,
                "Streams",
                settings.selectedProfile.name,
                Badge(Icons.Rounded.Sensors, Color(0xFFFF8A7A), MaterialShapes.Cookie9Sided),
            ) { push(Route.Streams) }
        }
        row {
            NavRow(it, "Chat", badge = Badge(Icons.Rounded.ChatBubble, Color(0xFFFF9ECF), MaterialShapes.Pill)) {
                push(Route.Chat)
            }
        }
        row {
            NavRow(it, "Display", badge = Badge(Icons.Rounded.PictureInPictureAlt, Color(0xFF8AB4FF), MaterialShapes.Square)) {
                push(Route.Display)
            }
        }
        row {
            NavRow(it, "Camera", badge = Badge(Icons.Rounded.PhotoCamera, Color(0xFF7DDB8B), MaterialShapes.Clover4Leaf)) {
                push(Route.Camera)
            }
        }
        row {
            NavRow(it, "Audio", settings.mic.label, Badge(Icons.Rounded.GraphicEq, Color(0xFFFFC46B), MaterialShapes.Sunny)) {
                push(Route.Audio)
            }
        }
    }
    Group {
        row {
            NavRow(it, "Bitrate presets", badge = Badge(Icons.Rounded.Speed, Color(0xFFC3A6FF), MaterialShapes.Pentagon)) {
                push(Route.BitratePresets)
            }
        }
    }
    Group {
        row {
            NavRow(it, "About", BuildConfig.VERSION_NAME, Badge(Icons.Rounded.Info, Color(0xFF9FDCE8), MaterialShapes.Circle)) {
                push(Route.About)
            }
        }
        row {
            NavRow(
                it,
                "Import and export settings",
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
                    supporting = "${profile.platform.label}  ${videoSummary(profile)}",
                ) { push(Route.Stream(profile.id)) }
            }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, "Create stream", Icons.Rounded.Add) {
                val profile = StreamProfile(name = "Stream ${settings.profiles.size + 1}")
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
        row { TextRow(it, "Name", profile.name) { name -> store.updateProfile(profile.copy(name = name)) } }
    }
    Group {
        row { shapes ->
            NavRow(shapes, "URL", profile.url.substringAfter("://").substringBefore('/').substringBefore('?').ifEmpty { "Not set" }) {
                push(Route.StreamUrl(profile.id))
            }
        }
        row { NavRow(it, "Video", videoSummary(profile)) { push(Route.StreamVideo(profile.id)) } }
        row { NavRow(it, "Audio", "${profile.audioBitrateKbps} kbps") { push(Route.StreamAudio(profile.id)) } }
        row { shapes ->
            NavRow(shapes, transportName(profile), if (profile.adaptiveBitrate) "Adaptive bitrate" else "Fixed bitrate") {
                push(Route.StreamTransport(profile.id))
            }
        }
        row { shapes ->
            val channels = listOf(profile.twitchChannel, profile.youtubeChannel, profile.kickChannel).count { it.isNotBlank() }
            NavRow(shapes, "Chat", if (channels == 0) "Off" else "$channels on") { push(Route.StreamChat(profile.id)) }
        }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, "Duplicate", Icons.Rounded.ContentCopy) {
                val copy = profile.copy(id = UUID.randomUUID().toString(), name = "${profile.name} copy")
                store.update { it.copy(profiles = it.profiles + copy) }
                pop()
            }
        }
        if (settings.profiles.size > 1) {
            row { shapes ->
                ActionRow(shapes, "Delete", Icons.Rounded.Delete, MaterialTheme.colorScheme.error, editable) {
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
            ChoiceRow(shapes, "Platform", Platform.entries, profile.platform, { it.label }, enabled = editable) { platform ->
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
            TextRow(shapes, "URL", profile.url, "rtmps://, srt://, srtla://", editable, KeyboardType.Uri) {
                update(profile.copy(url = it.trim()))
            }
        }
        if (profile.url.startsWith("rtmp")) {
            row { shapes ->
                TextRow(shapes, "Stream key", profile.streamKey, enabled = editable, keyboard = KeyboardType.Password) {
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
            ChoiceRow(shapes, "Resolution", Resolution.entries.reversed(), profile.resolution, { it.label }, enabled = editable) {
                update(profile.copy(resolution = it))
            }
        }
        row { shapes ->
            ChoiceRow(shapes, "Frame rate", fpsChoices, profile.fps, { "$it" }, enabled = editable) { update(profile.copy(fps = it)) }
        }
        row { shapes ->
            ChoiceRow(
                shapes,
                "Codec",
                listOf<VideoCodec?>(null) + profile.allowedCodecs.reversed(),
                profile.codec,
                { it?.label ?: "Auto ${CodecCatalog.choose(null, profile.allowedCodecs, w, h, profile.fps).label}" },
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
            ChoiceRow(shapes, "Key frame interval", listOf(1, 2, 3, 4, 5), profile.keyFrameIntervalSeconds, { "$it s" }, enabled = editable) {
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
private fun StreamTransport(store: SettingsStore, profile: StreamProfile, editable: Boolean) {
    val update: (StreamProfile) -> Unit = store::updateProfile
    Group {
        row { SwitchRow(it, "Adaptive bitrate", profile.adaptiveBitrate, editable) { on -> update(profile.copy(adaptiveBitrate = on)) } }
        if (profile.adaptiveBitrate) {
            row { shapes ->
                BitrateRow(shapes, "Minimum bitrate", profile.minBitrateKbps, 250..profile.bitrateKbps, editable) {
                    update(profile.copy(minBitrateKbps = it))
                }
            }
        }
    }
    if (profile.url.startsWith("srt")) {
        Group {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    "Latency",
                    (listOf(1000, 2000, 3000, 4000, 6000, 8000) + profile.srtLatencyMs).distinct().sorted(),
                    profile.srtLatencyMs,
                    { "${formatMbps(it)} s" },
                    enabled = editable,
                ) { update(profile.copy(srtLatencyMs = it)) }
            }
        }
    }
    if (profile.url.startsWith("srtla")) {
        Group {
            for ((type, label) in listOf(LinkType.WIFI to "Wi-Fi", LinkType.CELLULAR to "Cellular", LinkType.ETHERNET to "Ethernet")) {
                row { shapes ->
                    SwitchRow(shapes, "Bond over $label", type in profile.bondingNetworks, editable) { on ->
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
        row { SwitchRow(it, "Keep screen on", settings.keepScreenOn) { on -> store.update { s -> s.copy(keepScreenOn = on) } } }
        row { SwitchRow(it, "Vibrate on alerts", settings.alertHaptics) { on -> store.update { s -> s.copy(alertHaptics = on) } } }
    }
    Group {
        row { shapes ->
            NavRow(shapes, "Status", "${StatusItem.entries.size - settings.hiddenStatusItems.size} shown") {
                push(Route.StatusItems)
            }
        }
        row { shapes ->
            NavRow(shapes, "Quick buttons", "${QuickButtonId.entries.size - settings.hiddenQuickButtons.size} shown") {
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
                "Stabilization",
                StabilizationSetting.entries,
                settings.stabilization,
                {
                    when (it) {
                        StabilizationSetting.OFF -> "Off"
                        StabilizationSetting.STANDARD -> "Standard"
                        StabilizationSetting.PREVIEW -> "Enhanced"
                    }
                },
            ) { value -> store.update { it.copy(stabilization = value) } }
        }
        row { shapes ->
            SwitchRow(shapes, "Mirror front camera on stream", settings.mirrorFrontCameraOnStream) { on ->
                store.update { it.copy(mirrorFrontCameraOnStream = on) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BitratePresetsPage(store: SettingsStore, settings: AppSettings) {
    var kbps by remember { mutableIntStateOf(5000) }
    Group {
        row { BitrateRow(it, "New preset", kbps, 250..30000) { value -> kbps = value } }
        row { shapes ->
            ActionRow(shapes, "Add preset", Icons.Rounded.Add, enabled = kbps !in settings.bitratePresetsKbps) {
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
                    trailingContent = {
                        IconButton(
                            { store.update { it.copy(bitratePresetsKbps = it.bitratePresetsKbps - preset) } },
                            enabled = settings.bitratePresetsKbps.size > 1,
                        ) { Icon(Icons.Rounded.Delete, "Delete ${formatMbps(preset)} Mbps") }
                    },
                ) { Text("${formatMbps(preset)} Mbps") }
            }
        }
    }
}

@Composable
private fun ImportExport(store: SettingsStore, live: Boolean) {
    val context = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            message = runCatching {
                context.contentResolver.openOutputStream(uri)!!.use { it.write(store.exportJson().toByteArray()) }
            }.fold({ "Settings exported" }, { "Export failed" })
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            message = if (text.isSuccess && store.importJson(text.getOrThrow())) "Settings imported" else "Not a settings file"
        }
    }
    Group {
        row { ActionRow(it, "Export settings", Icons.Rounded.FileUpload) { export.launch("mobdroid-settings.json") } }
        row { ActionRow(it, "Import settings", Icons.Rounded.FileDownload, enabled = !live) { import.launch(arrayOf("*/*")) } }
    }
    Group {
        row { shapes ->
            ActionRow(shapes, "Reset settings", Icons.Rounded.RestartAlt, MaterialTheme.colorScheme.error, !live) {
                confirmReset = true
            }
        }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp)) }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset settings?") },
            text = { Text("All streams and settings are removed.") },
            confirmButton = {
                TextButton({
                    confirmReset = false
                    store.update { AppSettings() }
                    message = "Settings reset"
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ confirmReset = false }) { Text("Cancel") } },
        )
    }
}
