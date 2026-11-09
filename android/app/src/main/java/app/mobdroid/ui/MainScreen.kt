package app.mobdroid.ui

import android.os.PowerManager
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import app.mobdroid.stream.outputSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.camera.CameraDescriptor
import app.mobdroid.camera.CameraState
import app.mobdroid.camera.Facing
import app.mobdroid.media.label
import app.mobdroid.settings.SettingsStore
import app.mobdroid.stream.LiveState
import app.mobdroid.stream.LiveStats
import app.mobdroid.stream.StreamEngine
import app.mobdroid.stream.UiState
import app.mobdroid.stream.key
import kotlinx.coroutines.delay

private val statusStyle = TextStyle(
    color = Color.White,
    fontSize = 13.sp,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    shadow = Shadow(Color.Black, blurRadius = 6f),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(engine: StreamEngine, settings: SettingsStore) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val stats by engine.stats.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val profile = appSettings.selectedProfile
    var showSettings by remember { mutableStateOf(false) }
    var showCameras by remember { mutableStateOf(false) }
    var showBitrate by remember { mutableStateOf(false) }
    var stealth by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val (outW, outH) = outputSize(profile)
    val onLive = {
        when {
            ui.live == LiveState.OFFLINE -> engine.goLive()
            confirmStop -> {
                confirmStop = false
                engine.stopLive()
            }
            else -> confirmStop = true
        }
    }
    val buttons = quickButtons(
        ui,
        onSettings = { showSettings = true },
        onBitrate = { showBitrate = true },
        onCameras = { showCameras = true },
        onStealth = { stealth = true },
        onMute = { engine.setMuted(!ui.muted) },
        onTorch = { engine.setTorch(!ui.torch) },
    )

    Surface(color = Color.Black, contentColor = Color.White) {
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val banded = maxWidth / maxHeight < 1.5f
        if (banded) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Status(ui, stats, profile.name, engine::clearError, Modifier.weight(1f))
                    if (buttons.none { it.label == "Settings" && it.inRow }) {
                        IconButton({ showSettings = true }) { Icon(Icons.Rounded.Settings, "Settings") }
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(outW.toFloat() / outH)) {
                    Preview(engine, ui)
                    ZoomStops(ui, engine::setZoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                }
                Row(
                    Modifier.fillMaxWidth().weight(1f).padding(start = 16.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        buttons.filter { it.inRow }.forEach { QuickButtonView(it) }
                    }
                    Spacer(Modifier.width(8.dp))
                    LiveButton(ui.live, confirmStop, onLive, size = 60.dp)
                }
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Preview(engine, ui)
                    Status(ui, stats, profile.name, engine::clearError, Modifier.align(Alignment.TopStart).padding(16.dp))
                    ZoomStops(ui, engine::setZoom, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                }
                Column(
                    Modifier.width(140.dp).fillMaxHeight().padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FlowRow(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        maxItemsInEachRow = 2,
                    ) {
                        buttons.forEach { QuickButtonView(it) }
                    }
                    LiveButton(ui.live, confirmStop, onLive, size = 72.dp)
                }
            }
        }
        if (stealth) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable(interactionSource = null, indication = null) { stealth = false },
            )
        }
        AnimatedVisibility(
            visible = showSettings,
            enter = slideInVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) { it / 6 } +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = slideOutVertically(MaterialTheme.motionScheme.fastSpatialSpec()) { it / 6 } +
                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
        ) {
            SettingsScreen(settings, engine.isLive, onClose = { showSettings = false })
        }
    }
    }

    LaunchedEffect(stealth) { engine.setPreviewEnabled(!stealth) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3000)
            confirmStop = false
        }
    }
    BackHandler(showSettings) { showSettings = false }
    BackHandler(stealth) { stealth = false }

    if (showCameras) {
        ModalBottomSheet(
            onDismissRequest = { showCameras = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            CameraList(ui.cameras, ui.camera) {
                engine.selectCamera(it)
                showCameras = false
            }
        }
    }
    if (showBitrate) {
        ModalBottomSheet(
            onDismissRequest = { showBitrate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            BitratePresets(profile.bitrateKbps) {
                engine.setMaxBitrate(it)
                showBitrate = false
            }
        }
    }
}

private class QuickButton(
    val label: String,
    val icon: ImageVector,
    val checked: Boolean?,
    val inRow: Boolean,
    val onClick: () -> Unit,
)

private fun quickButtons(
    ui: UiState,
    onSettings: () -> Unit,
    onBitrate: () -> Unit,
    onCameras: () -> Unit,
    onStealth: () -> Unit,
    onMute: () -> Unit,
    onTorch: () -> Unit,
) = buildList {
    add(QuickButton("Settings", Icons.Rounded.Settings, null, inRow = false, onSettings))
    add(QuickButton("Bitrate", Icons.Rounded.Speed, null, inRow = true, onBitrate))
    add(QuickButton("Mic", if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, ui.muted, inRow = true, onMute))
    add(
        QuickButton(
            "Camera",
            if (ui.camera?.facing == Facing.EXTERNAL) Icons.Rounded.Usb else Icons.Rounded.Cameraswitch,
            null,
            inRow = true,
            onCameras,
        ),
    )
    add(QuickButton("Stealth", Icons.Rounded.VisibilityOff, null, inRow = true, onStealth))
    if (ui.camera?.hasFlash == true) {
        add(
            QuickButton(
                "Torch",
                if (ui.torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
                ui.torch,
                inRow = true,
                onTorch,
            ),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QuickButtonView(button: QuickButton) {
    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = Color(0xFF2A2A2A),
            contentColor = Color.White,
            checkedContainerColor = Color.White,
            checkedContentColor = Color.Black,
        )
        IconToggleButton(
            checked = button.checked == true,
            onCheckedChange = { button.onClick() },
            shapes = IconButtonDefaults.toggleableShapes(),
            colors = colors,
            modifier = Modifier.size(44.dp),
        ) { Icon(button.icon, button.label) }
        Text(button.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun BitratePresets(currentKbps: Int, onSelect: (Int) -> Unit) {
    Text(
        "Bitrate, Mbps",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
    ) {
        BITRATE_PRESETS_KBPS.forEach { kbps ->
            ToggleButton(
                checked = kbps == currentKbps,
                onCheckedChange = { onSelect(kbps) },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.weight(1f),
            ) {
                Text(formatBitrate(kbps * 1000L).removeSuffix(" Mbps").removeSuffix(".0"), maxLines = 1)
            }
        }
    }
}

private val BITRATE_PRESETS_KBPS = listOf(1000, 2500, 4000, 6000, 8000, 10000, 15000, 20000)

@Composable
private fun Preview(engine: StreamEngine, ui: UiState) {
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
                    if (zoom != 1f) engine.setZoom(engine.ui.value.zoom * zoom)
                }
            },
    )
    DisposableEffect(Unit) { onDispose { engine.setPreviewSurface(null) } }
}

@Composable
private fun Status(ui: UiState, stats: LiveStats, profileName: String, onDismissError: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        if (ui.live == LiveState.OFFLINE) {
            Text(profileName, style = statusStyle.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), maxLines = 1)
        } else {
            Text(formatBitrate(stats.bitrateBps), style = statusStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (label, color) = when (ui.live) {
                    LiveState.LIVE -> formatUptime(stats.uptimeSeconds) to LiveRed
                    LiveState.RECONNECTING -> "Reconnecting" to Color(0xFFFFCC00)
                    else -> "Connecting" to Color(0xFFFFCC00)
                }
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(5.dp))
                Text(label, style = statusStyle)
                if (ui.thermal >= PowerManager.THERMAL_STATUS_SEVERE) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Thermostat, "Phone is hot", tint = Color(0xFFFF9500), modifier = Modifier.size(14.dp))
                }
                if (ui.battery <= 15) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.BatteryAlert, "Battery low", tint = Color(0xFFFF9500), modifier = Modifier.size(14.dp))
                }
            }
        }
        val error = ui.error ?: (ui.cameraState as? CameraState.Error)?.message
        if (error != null) {
            Text(
                error,
                style = statusStyle.copy(color = Color(0xFFFF8A80), fontSize = 12.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(interactionSource = null, indication = null) { onDismissError() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoomStops(ui: UiState, onZoom: (Float) -> Unit, modifier: Modifier) {
    val stops = ui.camera?.lensZoomStops.orEmpty()
    if (stops.size < 2) return
    val haptics = LocalHapticFeedback.current
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        stops.forEachIndexed { index, stop ->
            val selected = stops.lastOrNull { ui.zoom >= it - 0.01f } == stop
            ToggleButton(
                checked = selected,
                onCheckedChange = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onZoom(stop)
                },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    stops.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                colors = ToggleButtonDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                ),
                modifier = Modifier.height(36.dp),
            ) {
                Text(
                    if (selected && ui.zoom != stop) formatZoom(ui.zoom) else formatZoom(stop),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveButton(state: LiveState, confirmStop: Boolean, onClick: () -> Unit, size: Dp) {
    val live = state != LiveState.OFFLINE
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(
        if (live) size * 0.28f else size / 2,
        MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val inner by animateDpAsState(
        if (live) size * 0.33f else size * 0.72f,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    val innerCorner by animateDpAsState(if (live) size * 0.09f else size * 0.36f, MaterialTheme.motionScheme.defaultSpatialSpec())
    val pressScale by animateFloatAsState(if (confirmStop) 0.92f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    Surface(
        onClick = {
            haptics.performHapticFeedback(if (live) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            onClick()
        },
        shape = RoundedCornerShape(corner),
        color = Color.Black.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
        modifier = Modifier
            .size(size)
            .scale(pressScale)
            .semantics { contentDescription = if (confirmStop) "Confirm stop" else if (live) "Stop stream" else "Go live" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(Modifier.size(inner).clip(RoundedCornerShape(innerCorner)).background(LiveRed))
            if (confirmStop) {
                Text("Stop", style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

@Composable
private fun CameraList(cameras: List<CameraDescriptor>, selected: CameraDescriptor?, onSelect: (CameraDescriptor) -> Unit) {
    LazyColumn(Modifier.padding(bottom = 24.dp)) {
        items(cameras, key = { it.key }) { camera ->
            ListItem(
                headlineContent = { Text(camera.label) },
                supportingContent = {
                    Text(
                        listOfNotNull(
                            camera.facing.name.lowercase().replaceFirstChar(Char::uppercase).takeIf { it != camera.label },
                            camera.sizes.maxByOrNull { it.width * it.height }?.let { "${it.width}x${it.height}" },
                            "60 fps".takeIf { camera.supports60Fps },
                        ).joinToString("  "),
                    )
                },
                leadingContent = {
                    Icon(if (camera.facing == Facing.EXTERNAL) Icons.Rounded.Usb else Icons.Rounded.Videocam, null)
                },
                colors = ListItemDefaults.colors(
                    containerColor = if (camera.key == selected?.key) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                ),
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onSelect(camera) },
            )
        }
    }
}

fun formatBitrate(bps: Long): String = if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1e6) else "${bps / 1000} kbps"

fun formatUptime(seconds: Long): String = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

fun formatZoom(zoom: Float): String = if (zoom < 10 && zoom % 1f != 0f) "%.1fx".format(zoom) else "${zoom.toInt()}x"
