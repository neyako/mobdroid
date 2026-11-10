package app.mobdroid.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.settings.SettingsStore
import app.mobdroid.stream.LiveState
import app.mobdroid.stream.StreamEngine
import app.mobdroid.stream.outputSize
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(engine: StreamEngine, settings: SettingsStore) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val stats by engine.stats.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val profile = appSettings.selectedProfile
    var side by remember { mutableStateOf<Side?>(null) }
    var shownSide by remember { mutableStateOf(Side.SETTINGS) }
    side?.let { shownSide = it }
    val showSettings = side == Side.SETTINGS
    val toggle = { target: Side -> side = if (side == target) null else target }
    var showBitrate by remember { mutableStateOf(false) }
    var stealth by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val (outW, outH) = outputSize(profile)
    val aspect = outW.toFloat() / outH
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
        appSettings.hiddenQuickButtons,
        chatOpen = side == Side.CHAT,
        onChat = { toggle(Side.CHAT) },
        onBitrate = { showBitrate = true },
        onStealth = { stealth = true },
        onMute = { engine.setMuted(!ui.muted) },
        onTorch = { engine.setTorch(!ui.torch) },
    )
    val window = LocalWindowInfo.current.containerSize
    val banded = window.width < window.height * 1.5f
    val motion = MaterialTheme.motionScheme
    val panel = remember { MutableTransitionState(false) }
    panel.targetState = side != null

    Surface(color = Color.Black, contentColor = Color.White) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                if (banded) {
                    WindowInsets.safeDrawing
                } else {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                },
            ),
        ) {
            if (banded) {
                val previewHeight = minOf((maxWidth - 12.dp) / aspect, maxHeight - 140.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(start = 24.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Status(ui, stats, engine.audioLevel, appSettings.hiddenStatusItems, engine::clearError, compact = true, Modifier.weight(1f))
                        MoblinLogo()
                        Spacer(Modifier.width(8.dp))
                        SettingsButton(showSettings) { toggle(Side.SETTINGS) }
                    }
                    Box(Modifier.size(previewHeight * aspect, previewHeight)) {
                        Preview(engine, ui, 14.dp)
                        if (appSettings.chatOnPreview) {
                            ChatOverlay(
                                engine,
                                appSettings.chatTextSize.sp - 1,
                                3,
                                Modifier.align(Alignment.BottomStart).fillMaxWidth(0.8f).padding(start = 10.dp, bottom = 48.dp),
                            )
                        }
                        Row(
                            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            ZoomStops(ui, engine::setZoom)
                            Spacer(Modifier.width(8.dp))
                            CameraSwitch(ui, engine::selectCamera, Modifier.weight(1f, fill = false))
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            buttons.forEach { QuickButtonView(it) }
                        }
                        Spacer(Modifier.width(8.dp))
                        LiveButton(ui.live, confirmStop, onLive, Modifier.size(96.dp, 52.dp))
                    }
                }
            } else {
                val barWidth = (maxWidth - (maxHeight - 12.dp) * aspect - 12.dp).coerceIn(104.dp, 200.dp)
                val wideBar = barWidth >= 150.dp
                val panelWidth = minOf(350.dp, (maxWidth - barWidth) * 0.5f)
                Row(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxHeight().padding(6.dp)) {
                        val width = minOf(maxWidth, maxHeight * aspect)
                        val height = width / aspect
                        val below = maxHeight - height >= 96.dp
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.size(width, height)) {
                                Preview(engine, ui, 16.dp)
                                Status(
                                    ui,
                                    stats,
                                    engine.audioLevel,
                                    appSettings.hiddenStatusItems,
                                    engine::clearError,
                                    compact = false,
                                    Modifier.align(Alignment.TopEnd).padding(12.dp),
                                )
                                if (!below) {
                                    CameraControls(ui, engine, Modifier.align(Alignment.BottomEnd).padding(10.dp))
                                }
                            }
                            if (below) CameraControls(ui, engine, Modifier.width(width).padding(top = 8.dp))
                        }
                    }
                    if (panel.currentState || panel.targetState) {
                        Box(Modifier.width(panelWidth).fillMaxHeight()) {
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = panel,
                                enter = slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 } +
                                    fadeIn(motion.defaultEffectsSpec()),
                                exit = slideOutHorizontally(motion.fastSpatialSpec()) { it / 4 } +
                                    fadeOut(motion.fastEffectsSpec()),
                            ) {
                                if (shownSide == Side.CHAT) {
                                    ChatPanel(engine, appSettings, onClose = { side = null })
                                } else {
                                    SettingsPanel(settings, ui.live != LiveState.OFFLINE, onClose = { side = null })
                                }
                            }
                        }
                    }
                    Column(
                        Modifier.width(barWidth).fillMaxHeight().padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(if (wideBar) 12.dp else 8.dp)) {
                            MoblinLogo(if (wideBar) 52.dp else 40.dp)
                            SettingsButton(showSettings, if (wideBar) 52.dp else 40.dp) { toggle(Side.SETTINGS) }
                        }
                        FlowRow(
                            Modifier.weight(1f).padding(top = 8.dp).verticalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(if (wideBar) 8.dp else 4.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            maxItemsInEachRow = 2,
                        ) {
                            buttons.forEach { QuickButtonView(it, if (wideBar) 52.dp else 44.dp) }
                        }
                        LiveButton(
                            ui.live,
                            confirmStop,
                            onLive,
                            Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(if (wideBar) 48.dp else 44.dp),
                        )
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
            AlertBanner(engine, Modifier.align(Alignment.TopCenter))
            if (banded) {
                AnimatedVisibility(
                    visible = side != null,
                    enter = slideInVertically(motion.defaultSpatialSpec()) { it / 6 } + fadeIn(motion.defaultEffectsSpec()),
                    exit = slideOutVertically(motion.fastSpatialSpec()) { it / 6 } + fadeOut(motion.fastEffectsSpec()),
                ) {
                    if (shownSide == Side.CHAT) {
                        ChatPanel(engine, appSettings, onClose = { side = null }) {
                            Status(ui, stats, engine.audioLevel, appSettings.hiddenStatusItems, engine::clearError, compact = true, Modifier)
                        }
                    } else {
                        SettingsPanel(settings, ui.live != LiveState.OFFLINE, onClose = { side = null })
                    }
                }
            }
        }
    }

    val chatOnly = banded && side == Side.CHAT
    LaunchedEffect(stealth, chatOnly) { engine.setPreviewEnabled(!stealth && !chatOnly) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3000)
            confirmStop = false
        }
    }
    BackHandler(side != null) { side = null }
    BackHandler(stealth) { stealth = false }

    if (showBitrate) {
        ModalBottomSheet(
            onDismissRequest = { showBitrate = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            BitratePresets(appSettings.bitratePresetsKbps, profile.bitrateKbps) {
                engine.setMaxBitrate(it)
                showBitrate = false
            }
        }
    }
}

private enum class Side { SETTINGS, CHAT }

fun formatBitrate(bps: Long): String = if (bps >= 1_000_000) "%.1f Mbps".format(bps / 1e6) else "${bps / 1000} kbps"

fun formatUptime(seconds: Long): String = "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)

fun formatZoom(zoom: Float): String = if (zoom < 10 && zoom % 1f != 0f) "%.1fx".format(zoom) else "${zoom.toInt()}x"
