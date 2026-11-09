package app.mobdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.media.CodecCatalog
import app.mobdroid.media.label
import app.mobdroid.settings.Orientation
import app.mobdroid.settings.Platform
import app.mobdroid.settings.Resolution
import app.mobdroid.settings.SettingsStore
import app.mobdroid.settings.StabilizationSetting
import app.mobdroid.settings.StreamProfile
import app.mobdroid.settings.allowedCodecs
import app.mobdroid.settings.fpsChoices
import app.mobdroid.stream.outputSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(store: SettingsStore, live: Boolean, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<String?>(null) }
    BackHandler(editing != null) { editing = null }
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val fastSpatial = motion.fastSpatialSpec<androidx.compose.ui.unit.IntOffset>()
    val effects = motion.defaultEffectsSpec<Float>()
    val fastEffects = motion.fastEffectsSpec<Float>()
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        AnimatedContent(
            targetState = editing,
            transitionSpec = {
                val forward = targetState != null
                (slideInHorizontally(spatial) { if (forward) it / 4 else -it / 4 } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(fastSpatial) { if (forward) -it / 4 else it / 4 } + fadeOut(fastEffects))
            },
            label = "settings",
        ) { id ->
            if (id == null) {
                SettingsHome(store, live, onClose, onEdit = { editing = it })
            } else {
                ProfileEditor(store, id, live, onBack = { editing = null })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Page(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            actions = { actions() },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content() }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsHome(store: SettingsStore, live: Boolean, onClose: () -> Unit, onEdit: (String) -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle()
    Page("Settings", onClose) {
        SectionLabel("Streams")
        settings.profiles.forEach { profile ->
            val selected = profile.id == settings.selectedProfile.id
            ListItem(
                headlineContent = { Text(profile.name) },
                supportingContent = {
                    val codec = CodecCatalog.choose(
                        profile.codec,
                        profile.allowedCodecs,
                        outputSize(profile).first,
                        outputSize(profile).second,
                        profile.fps,
                    )
                    Text(
                        "${profile.platform.label}  ${profile.resolution.label}${profile.fps}  " +
                            "${codec.label}  ${profile.bitrateKbps / 1000.0} Mbps",
                    )
                },
                leadingContent = {
                    Icon(Icons.Rounded.Check, null, tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                },
                trailingContent = {
                    IconButton({ onEdit(profile.id) }) { Icon(Icons.Rounded.ChevronRight, "Edit") }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable(enabled = !live) {
                    store.update { it.copy(selectedProfileId = profile.id) }
                },
            )
        }
        ListItem(
            headlineContent = { Text("New stream") },
            leadingContent = { Icon(Icons.Rounded.Add, null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                val profile = StreamProfile(name = "Stream ${settings.profiles.size + 1}")
                store.update { it.copy(profiles = it.profiles + profile) }
                onEdit(profile.id)
            },
        )
        SectionLabel("Camera")
        Text("Stabilization", style = MaterialTheme.typography.bodyMedium)
        Choices(
            StabilizationSetting.entries,
            settings.stabilization,
            label = {
                when (it) {
                    StabilizationSetting.OFF -> "Off"
                    StabilizationSetting.STANDARD -> "Standard"
                    StabilizationSetting.PREVIEW -> "Enhanced"
                }
            },
        ) { value -> store.update { it.copy(stabilization = value) } }
        SectionLabel("Display")
        SwitchRow("Keep screen on", settings.keepScreenOn) { value -> store.update { it.copy(keepScreenOn = value) } }
    }
}

@Composable
private fun ProfileEditor(store: SettingsStore, id: String, live: Boolean, onBack: () -> Unit) {
    val settings by store.settings.collectAsStateWithLifecycle()
    val profile = settings.profiles.firstOrNull { it.id == id } ?: return
    val update: (StreamProfile) -> Unit = store::updateProfile
    val editable = !(live && profile.id == settings.selectedProfile.id)
    Page(
        profile.name,
        onBack,
        actions = {
            if (settings.profiles.size > 1 && editable) {
                IconButton({
                    store.update { s -> s.copy(profiles = s.profiles.filterNot { it.id == id }) }
                    onBack()
                }) { Icon(Icons.Rounded.Delete, "Delete") }
            }
        },
    ) {
        OutlinedTextField(
            profile.name,
            { update(profile.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Destination")
        Choices(Platform.entries, profile.platform, label = { it.label }, enabled = editable) { platform ->
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
        OutlinedTextField(
            profile.url,
            { update(profile.copy(url = it.trim())) },
            label = { Text("URL") },
            placeholder = { Text("rtmps://, srt://, srtla://") },
            singleLine = true,
            enabled = editable,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        if (profile.url.startsWith("rtmp")) {
            OutlinedTextField(
                profile.streamKey,
                { update(profile.copy(streamKey = it.trim())) },
                label = { Text("Stream key") },
                singleLine = true,
                enabled = editable,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SectionLabel("Video")
        val (w, h) = outputSize(profile)
        Text("Codec", style = MaterialTheme.typography.bodyMedium)
        val codecs = listOf<VideoCodec?>(null) + profile.allowedCodecs.reversed()
        Choices(
            codecs,
            profile.codec,
            label = { codec ->
                codec?.label ?: "Auto ${CodecCatalog.choose(null, profile.allowedCodecs, w, h, profile.fps).label}"
            },
            isEnabled = { it == null || CodecCatalog.available(it, w, h, profile.fps) },
            enabled = editable,
        ) { update(profile.copy(codec = it)) }
        Text("Resolution", style = MaterialTheme.typography.bodyMedium)
        Choices(Resolution.entries.reversed(), profile.resolution, label = { it.label }, enabled = editable) {
            update(profile.copy(resolution = it))
        }
        Text("Frame rate", style = MaterialTheme.typography.bodyMedium)
        Choices(fpsChoices, profile.fps, label = { "$it" }, enabled = editable) { update(profile.copy(fps = it)) }
        Text("Orientation", style = MaterialTheme.typography.bodyMedium)
        Choices(Orientation.entries, profile.orientation, label = { it.name.lowercase().replaceFirstChar(Char::uppercase) }, enabled = editable) {
            update(profile.copy(orientation = it))
        }
        BitrateSlider("Bitrate", profile.bitrateKbps, 1000..20000, editable) { update(profile.copy(bitrateKbps = it)) }
        SwitchRow("Adaptive bitrate", profile.adaptiveBitrate, editable) { update(profile.copy(adaptiveBitrate = it)) }
        if (profile.adaptiveBitrate) {
            BitrateSlider("Minimum", profile.minBitrateKbps, 250..profile.bitrateKbps, editable) {
                update(profile.copy(minBitrateKbps = it))
            }
        }
        SectionLabel("Audio")
        Choices(listOf(96, 128, 160, 192, 256), profile.audioBitrateKbps, label = { "$it kbps" }, enabled = editable) {
            update(profile.copy(audioBitrateKbps = it))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Choices(
    values: List<T>,
    selected: T,
    label: (T) -> String,
    isEnabled: (T) -> Boolean = { true },
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        values.forEachIndexed { index, value ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(value) },
                enabled = enabled && isEnabled(value),
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                modifier = Modifier.weight(1f).widthIn(min = 0.dp),
            ) {
                Text(label(value), maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun BitrateSlider(label: String, kbps: Int, range: IntRange, enabled: Boolean, onChange: (Int) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("%.1f Mbps".format(kbps / 1000f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
        }
        Slider(
            value = kbps.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(((it / 250).toInt() * 250).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).clickable(enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Spacer(Modifier.padding(4.dp))
        Switch(checked, onChange, enabled = enabled)
    }
}
