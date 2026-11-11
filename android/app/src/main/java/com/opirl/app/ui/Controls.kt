package com.opirl.app.ui

import java.util.Locale
import androidx.compose.animation.core.animateDpAsState
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.camera.CameraDescriptor
import com.opirl.app.camera.CameraState
import com.opirl.app.engine.obs.ObsState
import com.opirl.app.media.AUDIO_LEVEL_FLOOR_DB
import com.opirl.app.media.VideoPlayer
import com.opirl.app.settings.QuickButtonId
import com.opirl.app.settings.Scene
import com.opirl.app.settings.StatusItem
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.LiveStats
import com.opirl.app.stream.RecordingStats
import com.opirl.app.stream.ReplayState
import com.opirl.app.stream.formatBytes
import com.opirl.app.stream.StreamEngine
import com.opirl.app.stream.UiState
import com.opirl.app.stream.key
import kotlinx.coroutines.flow.StateFlow

private val statusStyle = TextStyle(
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    shadow = Shadow(Color.Black, blurRadius = 6f),
)

private val Warning = Color(0xFFFF9500)
private val Pending = Color(0xFFFFCC00)

class QuickButton(
    @StringRes val label: Int,
    val icon: ImageVector,
    val checked: Boolean,
    val color: Color? = null,
    val onClick: () -> Unit,
)

fun quickButtons(
    ui: UiState,
    hidden: Set<QuickButtonId>,
    chatOpen: Boolean,
    recording: Boolean,
    confirmRecordStop: Boolean,
    widgetsOpen: Boolean,
    obsOpen: Boolean,
    browserOpen: Boolean,
    replay: ReplayState?,
    monitoring: Boolean?,
    onInstantReplay: () -> Unit,
    onSaveReplay: () -> Unit,
    onMonitor: () -> Unit,
    onWidgets: () -> Unit,
    onObs: () -> Unit,
    onBrowser: () -> Unit,
    onChat: () -> Unit,
    onRecord: () -> Unit,
    onSnapshot: () -> Unit,
    onBitrate: () -> Unit,
    onStealth: () -> Unit,
    onMute: () -> Unit,
    onTorch: () -> Unit,
) = QuickButtonId.entries.filter { it !in hidden }.mapNotNull { id ->
    when (id) {
        QuickButtonId.CHAT -> QuickButton(id.label, Icons.Rounded.ChatBubble, chatOpen, onClick = onChat)
        QuickButtonId.RECORD -> QuickButton(
            if (confirmRecordStop) R.string.confirm else if (recording) R.string.stop else id.label,
            if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
            recording,
            LiveRed,
            onRecord,
        )
        QuickButtonId.SNAPSHOT -> QuickButton(id.label, Icons.Rounded.PhotoCamera, false, onClick = onSnapshot)
        QuickButtonId.INSTANT_REPLAY -> replay?.let {
            QuickButton(
                if (it == ReplayState.PLAYING) R.string.stop else id.label,
                if (it == ReplayState.PLAYING) Icons.Rounded.Stop else Icons.Rounded.Replay,
                it != ReplayState.IDLE,
                onClick = onInstantReplay,
            )
        }
        QuickButtonId.SAVE_REPLAY -> replay?.let { QuickButton(id.label, Icons.Rounded.SaveAlt, false, onClick = onSaveReplay) }
        QuickButtonId.PREVIEW_STREAM -> monitoring?.let { QuickButton(id.label, Icons.Rounded.CastConnected, it, onClick = onMonitor) }
        QuickButtonId.WIDGETS -> QuickButton(id.label, Icons.Rounded.Widgets, widgetsOpen, onClick = onWidgets)
        QuickButtonId.OBS -> QuickButton(id.label, ObsLogo, obsOpen, onClick = onObs)
        QuickButtonId.BROWSER -> QuickButton(id.label, Icons.Rounded.Language, browserOpen, onClick = onBrowser)
        QuickButtonId.BITRATE -> QuickButton(id.label, Icons.Rounded.Speed, false, onClick = onBitrate)
        QuickButtonId.MIC -> QuickButton(
            id.label,
            if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            ui.muted,
            onClick = onMute,
        )
        QuickButtonId.STEALTH -> QuickButton(id.label, Icons.Rounded.VisibilityOff, false, onClick = onStealth)
        QuickButtonId.TORCH -> if (ui.activeCamera?.hasFlash == true) {
            QuickButton(
                id.label,
                if (ui.torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                ui.torch,
                onClick = onTorch,
            )
        } else {
            null
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarButton(icon: ImageVector, label: String, checked: Boolean, size: Dp, color: Color? = null, onClick: () -> Unit) {
    val through = fadeThrough()
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = Color.White,
            checkedContainerColor = color ?: MaterialTheme.colorScheme.primary,
            checkedContentColor = if (color != null) Color.White else MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.size(size),
    ) {
        AnimatedContent(icon, transitionSpec = { through }, label = "icon") { shown -> Icon(shown, label) }
    }
}

@Composable
fun QuickButtonView(button: QuickButton, size: Dp, width: Dp) {
    val label = stringResource(button.label)
    Column(Modifier.width(width), horizontalAlignment = Alignment.CenterHorizontally) {
        BarButton(button.icon, label, button.checked, size, button.color, button.onClick)
        Text(label, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
fun SettingsButton(open: Boolean, size: Dp = 40.dp, onChange: (Boolean) -> Unit) {
    BarButton(Icons.Rounded.Settings, stringResource(R.string.settings), open, size, onClick = { onChange(!open) })
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PanelButtons(hidden: Boolean, onHide: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val colors = IconButtonDefaults.filledIconButtonColors(
        containerColor = if (hidden) Color.Black.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = Color.White,
    )
    val size = Modifier.size(IconButtonDefaults.extraSmallContainerSize())
    val icon = Modifier.size(IconButtonDefaults.extraSmallIconSize)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledIconButton(onHide, IconButtonDefaults.shapes(), size, colors = colors) {
            Icon(
                if (hidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                stringResource(if (hidden) R.string.show_panel else R.string.hide_panel),
                icon,
            )
        }
        FilledIconButton(onClose, IconButtonDefaults.shapes(), size, colors = colors) {
            Icon(Icons.Rounded.Close, stringResource(R.string.close), icon)
        }
    }
}

fun quickButtonX(index: Int, count: Int, columns: Int, width: Int, cell: Int): Int =
    (width - minOf(columns, count - index / columns * columns) * cell) / 2 + index % columns * cell

@Composable
fun QuickButtonGrid(buttons: List<QuickButton>, closedColumns: Int, open: () -> Float, modifier: Modifier) {
    Layout({ buttons.forEach { QuickButtonView(it, 40.dp, 48.dp) } }, modifier) { measurables, constraints ->
        val placeables = measurables.map { it.measure(Constraints()) }
        val cell = 50.dp.roundToPx()
        val rowHeight = (placeables.maxOfOrNull { it.height } ?: 0) + 6.dp.roundToPx()
        val width = constraints.maxWidth
        val count = placeables.size
        val fraction = open()
        val x = { index: Int, columns: Int -> quickButtonX(index, count, columns, width, cell) + 1.dp.roundToPx() }
        val height = { columns: Int -> (count + columns - 1) / columns * rowHeight }
        layout(width, lerp(height(closedColumns), height(2), fraction).coerceAtLeast(constraints.minHeight)) {
            placeables.forEachIndexed { index, placeable ->
                placeable.place(
                    lerp(x(index, closedColumns), x(index, 2), fraction),
                    lerp(index / closedColumns * rowHeight, index / 2 * rowHeight, fraction),
                )
            }
        }
    }
}

@Composable
fun AppLogo(size: Dp = 40.dp) {
    Image(painterResource(R.drawable.ic_logo), "opIRL", Modifier.size(size))
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LiveButton(state: LiveState, confirmStop: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val live = state != LiveState.OFFLINE
    val haptics = LocalHapticFeedback.current
    val motion = MaterialTheme.motionScheme
    val description = stringResource(if (confirmStop) R.string.confirm_stop else if (live) R.string.stop_stream else R.string.go_live)
    val corner by animateDpAsState(if (live) 26.dp else 14.dp, motion.defaultSpatialSpec(), label = "corner")
    val border by animateDpAsState(if (live) 2.dp else 0.dp, motion.defaultSpatialSpec(), label = "border")
    Button(
        onClick = {
            haptics.performHapticFeedback(if (live) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            onClick()
        },
        shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(corner), pressedShape = RoundedCornerShape(8.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = LiveRed, contentColor = Color.White),
        border = if (border > 0.dp) BorderStroke(border, Color.White) else null,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier.semantics { contentDescription = description },
    ) {
        AnimatedContent(
            targetState = stringResource(if (confirmStop) R.string.confirm else if (live) R.string.end else R.string.go_live),
            transitionSpec = {
                (scaleIn(motion.fastSpatialSpec(), initialScale = 0.8f) + fadeIn(motion.fastEffectsSpec())) togetherWith
                    fadeOut(motion.fastEffectsSpec())
            },
            label = "live",
        ) { label ->
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun BitratePresets(presetsKbps: List<Int>, currentKbps: Int, onSelect: (Int) -> Unit) {
    Text(
        stringResource(R.string.bitrate_mbps),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        presetsKbps.forEach { kbps ->
            ToggleButton(
                checked = kbps == currentKbps,
                onCheckedChange = { onSelect(kbps) },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f),
            ) {
                Text(formatMbps(kbps), maxLines = 1)
            }
        }
    }
}

fun formatMbps(kbps: Int): String = "%.2f".format(Locale.ROOT, kbps / 1000f).trimEnd('0').trimEnd('.')

@Composable
fun Preview(engine: StreamEngine, ui: UiState, corner: Dp, access: Access, flash: () -> Float) {
    if (!access.ready) {
        AccessPrompt(access, corner)
        return
    }
    var viewSize by remember { mutableStateOf(1 to 1) }
    AndroidView(
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = engine.setPreviewSurface(holder.surface)

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        viewSize = width to height
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.setPreviewSurface(null)
                })
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(ui.camera) {
                detectTapGestures { offset ->
                    engine.tapToFocus(offset.x / viewSize.first, offset.y / viewSize.second)
                }
            }
            .pointerInput(ui.camera) {
                detectTransformGestures { _, _, zoom, _ ->
                    if (zoom != 1f) engine.setZoom(engine.ui.value.zoom * zoom, switchLens = false)
                }
            },
    )
    Spacer(
        Modifier.fillMaxSize().drawWithCache {
            val corners = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(size.toRect())
                addRoundRect(RoundRect(size.toRect(), CornerRadius(corner.toPx())))
            }
            onDrawBehind {
                val white = flash()
                if (white > 0f) drawRect(Color.White, alpha = white)
                drawPath(corners, Color.Black)
            }
        },
    )
    DisposableEffect(Unit) { onDispose { engine.setPreviewSurface(null) } }
}

@Composable
fun Status(
    ui: UiState,
    stats: LiveStats,
    recording: RecordingStats?,
    obs: ObsState?,
    audioLevel: StateFlow<Float>,
    hidden: Set<StatusItem>,
    onDismissError: () -> Unit,
    compact: Boolean,
    modifier: Modifier,
) {
    val live = ui.live != LiveState.OFFLINE
    val uptime: @Composable () -> Unit = {
        val showUptime = live && (StatusItem.UPTIME !in hidden || ui.live != LiveState.LIVE)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (StatusItem.TEMPERATURE !in hidden) {
                val color = when {
                    ui.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> Warning
                    ui.thermal >= PowerManager.THERMAL_STATUS_MODERATE -> Pending
                    else -> Color.White
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Thermostat, stringResource(R.string.temperature), tint = color, modifier = Modifier.size(14.dp))
                    Text("${ui.temperature}°C", style = statusStyle.copy(color = color))
                }
            }
            Appear(showUptime, vertical = false) { Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.battery <= 15) {
                    Icon(Icons.Rounded.BatteryAlert, stringResource(R.string.battery_low), tint = Warning, modifier = Modifier.padding(end = 6.dp).size(14.dp))
                }
                val (label, color) = when (ui.live) {
                    LiveState.LIVE -> formatUptime(stats.uptimeSeconds) to LiveRed
                    LiveState.RECONNECTING -> stringResource(R.string.reconnecting) to Pending
                    else -> stringResource(R.string.connecting) to Pending
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(label, style = statusStyle)
            } }
        }
    }
    val lastRecording = remember { mutableStateOf(RecordingStats()) }
    if (recording != null) lastRecording.value = recording
    val recorded: @Composable () -> Unit = {
        Appear(recording != null && StatusItem.RECORDING !in hidden, !compact) {
            val recording = lastRecording.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Videocam, stringResource(R.string.recording_now), tint = LiveRed, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                Text(
                    if (compact) formatUptime(recording.seconds) else "${formatUptime(recording.seconds)}  ${formatBytes(recording.bytes)}",
                    style = statusStyle,
                )
            }
        }
    }
    val lastObs = remember { mutableStateOf(ObsState()) }
    if (obs != null) lastObs.value = obs
    val remote: @Composable () -> Unit = {
        Appear(obs != null && StatusItem.OBS !in hidden, !compact) {
            val obs = lastObs.value
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (obs.connected) Color(0xFF34C759) else Warning))
                Spacer(Modifier.width(5.dp))
                Text(if (compact) "OBS" else obsStatusText(obs), style = statusStyle, maxLines = 1, softWrap = false)
            }
        }
    }
    val bitrate: @Composable () -> Unit = {
        Appear(live && StatusItem.BITRATE !in hidden, !compact) {
            Text(
                formatBitrate(stats.bitrateBps),
                style = statusStyle.copy(fontSize = if (compact) 13.sp else 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
    val bonding: @Composable () -> Unit = {
        Appear(live && StatusItem.BONDING !in hidden && stats.links.isNotEmpty(), !compact) {
            Text(stats.links.joinToString("  ") { "${it.name} ${it.percent}%" }, style = statusStyle, maxLines = 1)
        }
    }
    val meter: @Composable () -> Unit = {
        if (StatusItem.AUDIO_LEVEL !in hidden) AudioMeter(audioLevel, ui.muted)
    }
    val error = ui.error ?: (ui.cameraState as? CameraState.Error)?.message
    val errorText: @Composable () -> Unit = {
        if (error != null) {
            Text(
                localized(error),
                style = statusStyle.copy(color = Color(0xFFFF8A80), fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(interactionSource = null, indication = null) { onDismissError() },
            )
        }
    }
    if (compact) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                uptime()
                bitrate()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                recorded()
                remote()
                if (error != null) {
                    errorText()
                } else {
                    bonding()
                    meter()
                }
            }
        }
    } else {
        Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            remote()
            uptime()
            recorded()
            bitrate()
            bonding()
            meter()
            errorText()
        }
    }
}

@Composable
private fun AudioMeter(level: StateFlow<Float>, muted: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
            null,
            tint = Color.White,
            modifier = Modifier.padding(end = 4.dp).size(14.dp),
        )
        if (muted) {
            Text(stringResource(R.string.muted), style = statusStyle)
            return
        }
        val decibels = level.collectAsStateWithLifecycle()
        Canvas(Modifier.size(84.dp, 6.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(Color.Black.copy(alpha = 0.45f), cornerRadius = radius)
            val filled = size.width * (1f - decibels.value / AUDIO_LEVEL_FLOOR_DB)
            clipRect(right = filled) {
                drawRoundRect(Color(0xFF34C759), cornerRadius = radius)
                drawRect(Color(0xFFFFCC00), Offset(size.width * 0.7f, 0f), Size(size.width * 0.2f, size.height))
                drawRoundRect(Color(0xFFFF3B30), Offset(size.width * 0.9f, 0f), Size(size.width * 0.1f, size.height))
            }
        }
    }
}

@Composable
fun CameraControls(ui: UiState, scenes: List<Scene>, scene: Scene?, video: VideoPlayer?, engine: StreamEngine, modifier: Modifier) {
    val through = fadeThrough()
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnimatedContent(video, transitionSpec = { through }, contentAlignment = Alignment.CenterEnd, label = "source") { player ->
            if (player != null) VideoStrip(player, Modifier.fillMaxWidth()) else ZoomStops(ui, engine::setZoom)
        }
        SceneSwitch(scenes, scene, engine::selectScene, Modifier)
    }
}

fun formatClock(ms: Long): String = "%d:%02d".format(Locale.ROOT, ms / 60_000, ms / 1000 % 60)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoStrip(video: VideoPlayer, modifier: Modifier) {
    val state by video.state.collectAsStateWithLifecycle()
    val duration = state.durationMs.coerceAtLeast(1)
    val slider = remember { SliderState() }
    val position = stringResource(R.string.video_position)
    LaunchedEffect(state.positionMs, duration) {
        if (!slider.isDragging) slider.value = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    }
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactIconButton(Icons.Rounded.SkipPrevious, stringResource(R.string.previous_video), onClick = video::previous)
        CompactIconButton(
            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            stringResource(if (state.playing) R.string.pause_video else R.string.play_video),
            onClick = video::togglePlay,
        )
        CompactIconButton(Icons.Rounded.SkipNext, stringResource(R.string.next_video), onClick = video::next)
        Text(
            formatClock(if (slider.isDragging) (slider.value * duration).toLong() else state.positionMs),
            style = statusStyle,
            modifier = Modifier.padding(start = 4.dp),
        )
        Slider(
            state = slider,
            onValueChange = { slider.value = it },
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = position },
            onValueChangeFinished = { video.seekTo((slider.value * duration).toLong()) },
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) },
            track = { SliderDefaults.Track(it, Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
        )
        Text(formatClock(state.durationMs), style = statusStyle, modifier = Modifier.padding(end = 10.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Segments(values: List<T>, selected: T?, modifier: Modifier, label: (T) -> String, onSelect: (T) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        values.forEachIndexed { index, value ->
            ToggleButton(
                checked = value == selected,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(value)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    values.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(36.dp).widthIn(min = 46.dp, max = 132.dp),
            ) {
                Text(
                    label(value),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun ZoomStops(ui: UiState, onZoom: (Float) -> Unit) {
    val stops = ui.camera?.lensZoomStops.orEmpty()
    if (stops.size < 2) return
    val selected = ui.lens?.ratio ?: stops.lastOrNull { ui.zoom >= it - 0.01f }
    Segments(stops, selected, Modifier, { if (it == selected && ui.zoom != it) formatZoom(ui.zoom) else formatZoom(it) }, onZoom)
}

@Composable
fun SceneSwitch(scenes: List<Scene>, scene: Scene?, onSelect: (String) -> Unit, modifier: Modifier) {
    if (scenes.size < 2) return
    Segments(scenes, scene, modifier.horizontalScroll(rememberScrollState()), { it.name }) {
        if (it.id != scene?.id) onSelect(it.id)
    }
}
