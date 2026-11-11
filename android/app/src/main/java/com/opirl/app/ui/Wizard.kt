package com.opirl.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.opirl.app.R
import com.opirl.app.settings.Platform
import com.opirl.app.settings.Resolution
import com.opirl.app.settings.SettingsStore

enum class Destination(val platform: Platform, val label: String?, val needsUrl: Boolean) {
    OPIRL(Platform.CUSTOM, null, true),
    TWITCH(Platform.TWITCH, "Twitch", false),
    YOUTUBE(Platform.YOUTUBE, "YouTube", false),
    KICK(Platform.KICK, "Kick", true),
    FACEBOOK(Platform.FACEBOOK, "Facebook", false),
    CUSTOM(Platform.CUSTOM, null, true),
}

enum class Quality(val resolution: Resolution, val fps: Int, val bitrateKbps: Int) {
    FULL30(Resolution.R1080, 30, 6000),
    FULL60(Resolution.R1080, 60, 8000),
    QUAD30(Resolution.R1440, 30, 12000),
    ULTRA30(Resolution.R2160, 30, 20000),
}

fun needsKey(url: String): Boolean = url.trim().startsWith("rtmp")

fun isStreamReady(destination: Destination, url: String, key: String): Boolean {
    val address = if (destination.needsUrl) url.trim() else destination.platform.defaultUrl
    if (address.substringAfter("://", "").isBlank()) return false
    return !needsKey(address) || key.isNotBlank() || destination == Destination.CUSTOM
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Wizard(store: SettingsStore, access: Access) {
    var step by remember { mutableIntStateOf(0) }
    var destination by remember { mutableStateOf(Destination.OPIRL) }
    var url by remember { mutableStateOf("srtla://") }
    var key by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(Quality.FULL30) }
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<IntOffset>()
    val name = destination.label ?: stringResource(if (destination == Destination.OPIRL) R.string.platform_opirl else R.string.platform_custom)
    val finish = {
        store.update { settings ->
            val profile = settings.selectedProfile.copy(
                name = name,
                platform = destination.platform,
                url = if (destination.needsUrl) url.trim() else destination.platform.defaultUrl,
                streamKey = key.trim(),
                codec = null,
                resolution = quality.resolution,
                fps = quality.fps,
                bitrateKbps = quality.bitrateKbps,
            )
            settings.copy(profiles = settings.profiles.map { if (it.id == profile.id) profile else it }, setupDone = true)
        }
    }
    BackHandler(step > 0) { step-- }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { } }
            .safeDrawingPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(max = 440.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.Black,
            contentColor = Color.White,
        ) {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 4.dp)) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        repeat(3) { index ->
                            Box(
                                Modifier
                                    .size(if (index == step) 18.dp else 6.dp, 6.dp)
                                    .clip(CircleShape)
                                    .background(if (index == step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest),
                            )
                        }
                    }
                    Box(Modifier.weight(1f))
                    TextButton({ store.update { it.copy(setupDone = true) } }) { Text(stringResource(R.string.skip)) }
                }
                AnimatedContent(
                    step,
                    Modifier.weight(1f, fill = false),
                    transitionSpec = {
                        val forward = targetState > initialState
                        (slideInHorizontally(spatial) { if (forward) it / 4 else -it / 4 } + fadeIn(motion.defaultEffectsSpec())) togetherWith
                            (slideOutHorizontally(motion.fastSpatialSpec()) { if (forward) -it / 4 else it / 4 } + fadeOut(motion.fastEffectsSpec()))
                    },
                    label = "step",
                ) { shown ->
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            stringResource(
                                when (shown) {
                                    0 -> R.string.wizard_access_title
                                    1 -> R.string.wizard_platform_title
                                    else -> R.string.wizard_stream_title
                                },
                            ),
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                        when (shown) {
                            0 -> AccessStep(access)
                            1 -> DestinationStep(destination) { picked ->
                                destination = picked
                                url = if (picked == Destination.OPIRL) "srtla://" else if (picked == Destination.KICK) "rtmps://" else ""
                                key = ""
                            }
                            else -> StreamStep(destination, url, key, quality, { url = it }, { key = it }, { quality = it })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 0) FilledTonalButton({ step-- }) { Text(stringResource(R.string.back)) }
                    val ready = step != 2 || isStreamReady(destination, url, key)
                    Button(
                        {
                            when {
                                step == 0 && !access.ready -> access.request()
                                step < 2 -> step++
                                else -> finish()
                            }
                        },
                        Modifier.weight(1f),
                        enabled = ready,
                    ) {
                        Text(
                            stringResource(
                                when {
                                    step == 0 && !access.ready -> if (access.blocked) R.string.open_settings else R.string.allow
                                    step < 2 -> R.string.next
                                    else -> R.string.done
                                },
                            ),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccessRow(shapes: ListItemShapes, badge: Badge, title: Int, reason: Int, granted: Boolean) {
    SegmentedListItem(
        modifier = Modifier.heightIn(max = TwoLineRowHeight),
        contentPadding = RowPadding,
        colors = rowColors(),
        shapes = shapes,
        leadingContent = { BadgeIcon(badge) },
        verticalAlignment = Alignment.CenterVertically,
        supportingContent = { Text(stringResource(reason), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            if (granted) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), tint = Color(0xFF7DDB8B))
        },
    ) { Text(stringResource(title), style = RowTitle) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AccessStep(access: Access) {
    Group {
        row { shapes ->
            AccessRow(
                shapes,
                Badge(Icons.Rounded.PhotoCamera, Color(0xFF7DDB8B), MaterialShapes.Clover4Leaf),
                R.string.camera,
                R.string.permission_camera_reason,
                access.camera,
            )
        }
        row { shapes ->
            AccessRow(
                shapes,
                Badge(Icons.Rounded.GraphicEq, Color(0xFFFFC46B), MaterialShapes.Sunny),
                R.string.microphone,
                R.string.permission_microphone_reason,
                access.microphone,
            )
        }
        row { shapes ->
            AccessRow(
                shapes,
                Badge(Icons.Rounded.Notifications, Color(0xFF8AB4FF), MaterialShapes.Square),
                R.string.notifications,
                R.string.permission_notifications_reason,
                access.notifications,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Choices(options: List<T>, selected: T, columns: Int, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    options.chunked(columns).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { option ->
                ToggleButton(
                    checked = option == selected,
                    onCheckedChange = { onSelect(option) },
                    colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(40.dp),
                ) { Text(label(option), maxLines = 1) }
            }
        }
    }
}

@Composable
private fun DestinationStep(selected: Destination, onSelect: (Destination) -> Unit) {
    Choices(
        Destination.entries,
        selected,
        2,
        { it.label ?: stringResource(if (it == Destination.OPIRL) R.string.platform_opirl else R.string.platform_custom) },
        onSelect,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Field(title: String, value: String, keyboard: KeyboardType, placeholder: String? = null, onChange: (String) -> Unit) {
    val container = MaterialTheme.colorScheme.surfaceContainer
    val primary = MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val outline by animateColorAsState(
        primary.copy(alpha = if (focused) 1f else 0f),
        MaterialTheme.motionScheme.fastEffectsSpec(),
    )
    TextField(
        value,
        onChange,
        label = { Text(title) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = true,
        visualTransformation = if (keyboard == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        interactionSource = interaction,
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier.fillMaxWidth().border(2.dp, outline, RoundedCornerShape(16.dp)),
    )
}

@Composable
private fun StreamStep(
    destination: Destination,
    url: String,
    key: String,
    quality: Quality,
    onUrl: (String) -> Unit,
    onKey: (String) -> Unit,
    onQuality: (Quality) -> Unit,
) {
    val address = if (destination.needsUrl) url else destination.platform.defaultUrl
    if (destination.needsUrl) {
        Field(
            stringResource(if (destination == Destination.OPIRL) R.string.relay_url else R.string.server_url),
            url,
            KeyboardType.Uri,
            "rtmps://, srt://, srtla://",
            onUrl,
        )
    }
    if (needsKey(address)) Field(stringResource(R.string.stream_key), key, KeyboardType.Password, onChange = onKey)
    Choices(Quality.entries, quality, 4, { "${it.resolution.label}${it.fps}" }, onQuality)
}
