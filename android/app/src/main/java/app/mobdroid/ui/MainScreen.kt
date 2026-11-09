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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.rememberCoroutineScope
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(engine: StreamEngine, settings: SettingsStore) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val stats by engine.stats.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showCameras by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Preview(engine, ui)
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val landscape = maxWidth > maxHeight
            StatusOverlay(ui, stats, appSettings.selectedProfile.name, Modifier.align(Alignment.TopStart).padding(12.dp))
            ZoomStops(
                ui,
                onZoom = engine::setZoom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (landscape) 16.dp else 132.dp),
            )
            Controls(
                ui = ui,
                landscape = landscape,
                confirmStop = confirmStop,
                onLive = {
                    when {
                        ui.live == LiveState.OFFLINE -> engine.goLive()
                        confirmStop -> {
                            confirmStop = false
                            engine.stopLive()
                        }
                        else -> confirmStop = true
                    }
                },
                onSettings = { showSettings = true },
                onCameras = { showCameras = true },
                onMute = { engine.setMuted(!ui.muted) },
                onTorch = { engine.setTorch(!ui.torch) },
                modifier = Modifier.align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter),
            )
            ui.error?.let { error ->
                Text(
                    error,
                    style = statusStyle.copy(color = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .pointerInput(Unit) { detectTapGestures { engine.clearError() } },
                )
            }
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

    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3000)
            confirmStop = false
        }
    }
    BackHandler(showSettings) { showSettings = false }

    if (showCameras) {
        ModalBottomSheet(onDismissRequest = { showCameras = false }, containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            CameraList(ui.cameras, ui.camera) {
                engine.selectCamera(it)
                showCameras = false
            }
        }
    }
}

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
private fun StatusOverlay(ui: UiState, stats: LiveStats, profileName: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (ui.live) {
                LiveState.OFFLINE -> profileName to Color.White
                LiveState.CONNECTING -> "Connecting" to Color(0xFFFFCC00)
                LiveState.LIVE -> formatUptime(stats.uptimeSeconds) to LiveRed
                LiveState.RECONNECTING -> "Reconnecting" to Color(0xFFFFCC00)
            }
            if (ui.live != LiveState.OFFLINE) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
            }
            Text(label, style = statusStyle.copy(fontWeight = FontWeight.SemiBold))
        }
        if (ui.live != LiveState.OFFLINE) {
            Text(
                buildString {
                    append(formatBitrate(stats.bitrateBps))
                    append("  ")
                    append("%.0f fps".format(stats.fps))
                    if (stats.rttMs > 0) append("  %.0f ms".format(stats.rttMs))
                    stats.codec?.let { append("  ").append(it.label) }
                    if (stats.links > 0) append("  ${stats.links} links")
                },
                style = statusStyle,
            )
        }
        val alerts = buildList {
            if (ui.battery <= 20) add("Battery ${ui.battery}%")
            if (ui.thermal >= PowerManager.THERMAL_STATUS_SEVERE) add("Hot")
            else if (ui.thermal >= PowerManager.THERMAL_STATUS_MODERATE) add("Warm")
            if (ui.muted) add("Muted")
            (ui.cameraState as? CameraState.Error)?.let { add(it.message) }
        }
        if (alerts.isNotEmpty()) {
            Text(alerts.joinToString("  "), style = statusStyle.copy(color = Color(0xFFFFCC00)))
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
private fun Controls(
    ui: UiState,
    landscape: Boolean,
    confirmStop: Boolean,
    onLive: () -> Unit,
    onSettings: () -> Unit,
    onCameras: () -> Unit,
    onMute: () -> Unit,
    onTorch: () -> Unit,
    modifier: Modifier,
) {
    val content: @Composable () -> Unit = {
        RailButton(onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
        RailButton(onCameras) {
            Icon(if (ui.camera?.facing == Facing.EXTERNAL) Icons.Rounded.Usb else Icons.Rounded.Cameraswitch, "Camera")
        }
        LiveButton(ui.live, confirmStop, onLive)
        RailToggle(ui.muted, onMute) { Icon(if (ui.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute") }
        RailToggle(ui.torch, onTorch, enabled = ui.camera?.hasFlash == true) {
            Icon(if (ui.torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch")
        }
    }
    if (landscape) {
        Column(
            modifier.padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    } else {
        Row(
            modifier.padding(bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LiveButton(state: LiveState, confirmStop: Boolean, onClick: () -> Unit) {
    val live = state != LiveState.OFFLINE
    val haptics = LocalHapticFeedback.current
    val corner by animateDpAsState(
        if (live) 22.dp else 40.dp,
        MaterialTheme.motionScheme.fastSpatialSpec(),
    )
    val inner by animateDpAsState(
        if (live) 26.dp else 58.dp,
        MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    val innerCorner by animateDpAsState(if (live) 7.dp else 29.dp, MaterialTheme.motionScheme.defaultSpatialSpec())
    val pressScale by animateFloatAsState(if (confirmStop) 0.92f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
    Surface(
        onClick = {
            haptics.performHapticFeedback(if (live) HapticFeedbackType.Reject else HapticFeedbackType.Confirm)
            onClick()
        },
        shape = RoundedCornerShape(corner),
        color = Color.Black.copy(alpha = 0.35f),
        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
        modifier = Modifier.size(80.dp).scale(pressScale),
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
private fun RailButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
        ),
        modifier = Modifier.size(48.dp),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RailToggle(checked: Boolean, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onClick() },
        enabled = enabled,
        shapes = IconButtonDefaults.toggleableShapes(),
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = Color.Black.copy(alpha = 0.55f),
            contentColor = Color.White,
            checkedContainerColor = Color.White,
            checkedContentColor = Color.Black,
            disabledContainerColor = Color.Black.copy(alpha = 0.3f),
            disabledContentColor = Color.White.copy(alpha = 0.3f),
        ),
        modifier = Modifier.size(48.dp),
        content = content,
    )
}

@Composable
private fun CameraList(cameras: List<CameraDescriptor>, selected: CameraDescriptor?, onSelect: (CameraDescriptor) -> Unit) {
    LazyColumn(Modifier.padding(bottom = 24.dp)) {
        items(cameras, key = { it.key }) { camera ->
            ListItem(
                headlineContent = { Text(camera.label) },
                supportingContent = {
                    Text(
                        buildString {
                            append(camera.facing.name.lowercase().replaceFirstChar(Char::uppercase))
                            camera.sizes.maxByOrNull { it.width * it.height }?.let { append("  ${it.width}x${it.height}") }
                            if (camera.supports60Fps) append("  60 fps")
                        },
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
                    .pointerInput(camera) { detectTapGestures { onSelect(camera) } },
            )
        }
    }
}

fun formatBitrate(bps: Long): String = if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1e6) else "${bps / 1000} kbps"

fun formatUptime(seconds: Long): String = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

fun formatZoom(zoom: Float): String = if (zoom < 10 && zoom % 1f != 0f) "%.1fx".format(zoom) else "${zoom.toInt()}x"
