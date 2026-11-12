package com.opirl.app.ui

import android.graphics.Rect
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.AnimatedContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.Transport
import com.opirl.app.settings.transportOf
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.StreamEngine
import com.opirl.app.stream.outputSize
import kotlinx.coroutines.delay

@Composable
fun isLargeWindow(): Boolean {
    val window = LocalWindowInfo.current.containerSize
    return minOf(window.width, window.height) / LocalDensity.current.density >= 600f
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(engine: StreamEngine, settings: SettingsStore, access: Access, hinge: Rect?) {
    val ui by engine.ui.collectAsStateWithLifecycle()
    val stats by engine.stats.collectAsStateWithLifecycle()
    val recording by engine.recording.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle()
    val profile = appSettings.selectedProfile
    val video by engine.videoPlayer.collectAsStateWithLifecycle()
    val obsState by engine.obs.state.collectAsStateWithLifecycle()
    val obs = obsState.takeIf { profile.obs.enabled }
    var side by remember { mutableStateOf<Side?>(null) }
    var shownSide by remember { mutableStateOf(Side.SETTINGS) }
    side?.let { shownSide = it }
    var panelHidden by remember { mutableStateOf(false) }
    val settingsPages = remember { SettingsPages() }
    val chatDraft = remember { mutableStateOf("") }
    val chatTools = remember { mutableStateOf(false) }
    val browser = remember { BrowserState() }
    val drawTool = remember { DrawTool() }
    var drawing by remember { mutableStateOf(false) }
    var showOverlays by remember { mutableStateOf(true) }
    val runningMacros by engine.macros.running.collectAsStateWithLifecycle()
    val draw = drawTool.takeIf { drawing }
    val showSettings = side == Side.SETTINGS
    val toggle = { target: Side ->
        side = if (side == target && !panelHidden) null else target
        panelHidden = false
    }
    val closePanel = {
        side = null
        panelHidden = false
    }
    val motion = MaterialTheme.motionScheme
    val floatingButtons: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedVisibility(
            side != null && panelHidden,
            modifier,
            enter = scaleIn(motion.defaultSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.defaultEffectsSpec()),
            exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(motion.fastEffectsSpec()),
        ) { PanelButtons(hidden = true, onHide = { panelHidden = false }, onClose = closePanel) }
    }
    val through = fadeThrough()
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val obsPanel: @Composable () -> Unit = {
        ObsPanel(settings, engine.obs, appSettings, onHide = { panelHidden = true }, onClose = closePanel) {
            settingsPages.open(Route.Streams, Route.Stream(profile.id), Route.StreamObs(profile.id))
            side = Side.SETTINGS
        }
    }
    val remotePanel: @Composable () -> Unit = {
        RemotePanel(engine, appSettings.remoteControl, onHide = { panelHidden = true }, onClose = closePanel) {
            settingsPages.open(Route.RemoteControl, Route.RemoteAssistant)
            side = Side.SETTINGS
        }
    }
    var showBitrate by remember { mutableStateOf(false) }
    val stealth = ui.stealth
    val moblinkStatus by engine.moblink.status.collectAsStateWithLifecycle()
    val remoteStatus by engine.remote.status.collectAsStateWithLifecycle()
    val pendingLink by engine.pendingLink.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    var confirmRecordStop by remember { mutableStateOf(false) }
    val replay by engine.replay.collectAsStateWithLifecycle()
    val replayPanel = remember { ReplayPanel() }
    val replayOpen = replayPanel.open && profile.replay.enabled
    val monitoring by engine.monitoring.collectAsStateWithLifecycle()
    val busy = ui.live != LiveState.OFFLINE || recording != null || monitoring
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
        chatOpen = side == Side.CHAT && !panelHidden,
        recording = recording != null,
        confirmRecordStop = confirmRecordStop,
        widgetsOpen = side == Side.WIDGETS && !panelHidden,
        obsOpen = side == Side.OBS && !panelHidden,
        browserOpen = side == Side.BROWSER && !panelHidden,
        replay = replay.takeIf { profile.replay.enabled },
        replayOpen = replayOpen,
        monitoring = monitoring.takeIf { transportOf(profile.previewStream.url) == Transport.WHIP },
        onReplay = { replayPanel.open = !replayOpen },
        onInstantReplay = engine::instantReplay,
        onMonitor = engine::toggleMonitor,
        remote = appSettings.remoteControl.assistant,
        remoteOpen = side == Side.REMOTE && !panelHidden,
        filters = (side == Side.FILTERS && !panelHidden) || appSettings.effects.filters.isNotEmpty() ||
            appSettings.effects.luts.any { it.enabled },
        drawing = drawing,
        overlaysHidden = !showOverlays,
        switcher = (side == Side.SWITCHER && !panelHidden) || appSettings.autoSwitcherId != null,
        macros = (side == Side.MACROS && !panelHidden) || runningMacros.isNotEmpty(),
        onFilters = { toggle(Side.FILTERS) },
        onDraw = { drawing = !drawing },
        onOverlays = { showOverlays = !showOverlays },
        onSwitcher = { toggle(Side.SWITCHER) },
        onMacros = { toggle(Side.MACROS) },
        onWidgets = { toggle(Side.WIDGETS) },
        onObs = { toggle(Side.OBS) },
        onBrowser = { toggle(Side.BROWSER) },
        onRemote = { toggle(Side.REMOTE) },
        onChat = { toggle(Side.CHAT) },
        onRecord = {
            when {
                recording == null -> engine.startRecording()
                confirmRecordStop -> {
                    confirmRecordStop = false
                    engine.stopRecording()
                }
                else -> confirmRecordStop = true
            }
        },
        onSnapshot = {
            engine.takeSnapshot()
            scope.launch {
                flash.snapTo(0.5f)
                flash.animateTo(0f, motion.slowEffectsSpec())
            }
        },
        onBitrate = { showBitrate = true },
        onStealth = { engine.setStealth(true) },
        onMute = { engine.setMuted(!ui.muted) },
        onTorch = { engine.setTorch(!ui.torch) },
    )
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val large = isLargeWindow()
    val banded = !large && window.width < window.height * 1.5f
    val panel = remember { MutableTransitionState(false) }
    panel.targetState = side != null && !panelHidden
    val back = backProgress(side != null, closePanel)
    val statusInset by animateDpAsState(if (panelHidden) 36.dp else 0.dp, motion.defaultSpatialSpec(), label = "status")

    val statusView: @Composable (Boolean, Modifier) -> Unit = { compact, modifier ->
        if (showOverlays) {
            Status(
                ui,
                stats,
                recording,
                obs,
                moblinkStatus.takeIf { appSettings.moblink.streamer || appSettings.moblink.relay },
                remoteStatus,
                engine.audioLevel,
                appSettings.hiddenStatusItems,
                engine::clearError,
                compact,
                modifier,
            )
        }
    }
    val sidePanel: @Composable (Side, (@Composable () -> Unit)?) -> Unit = { shown, chatHeader ->
        when (shown) {
            Side.CHAT -> ChatPanel(engine, appSettings, chatDraft, chatTools, onHide = { panelHidden = true }, onClose = closePanel, header = chatHeader)
            Side.WIDGETS -> WidgetsPanel(settings, appSettings, onHide = { panelHidden = true }, onClose = closePanel)
            Side.OBS -> obsPanel()
            Side.REMOTE -> remotePanel()
            Side.BROWSER -> BrowserPanel(settings, appSettings, browser, onHide = { panelHidden = true }, onClose = closePanel)
            Side.FILTERS -> FiltersPanel(settings, appSettings, engine, onHide = { panelHidden = true }, onClose = closePanel)
            Side.SWITCHER -> SwitcherPanel(settings, appSettings, onHide = { panelHidden = true }, onClose = closePanel)
            Side.MACROS -> MacrosPanel(appSettings, engine, onHide = { panelHidden = true }, onClose = closePanel)
            Side.SETTINGS -> SettingsPanel(settings, engine, busy, settingsPages, onHide = { panelHidden = true }, onClose = closePanel)
        }
    }
    val panelView: @Composable (Boolean) -> Unit = { chatStatus ->
        AnimatedContent(shownSide, transitionSpec = { through }, label = "panel") { shown ->
            sidePanel(shown, if (chatStatus) ({ statusView(true, Modifier) }) else null)
        }
    }
    val picture: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier) {
            Preview(engine, ui, 16.dp, access, draw) { flash.value }
            statusView(false, Modifier.align(Alignment.TopEnd).padding(12.dp).padding(top = statusInset))
            floatingButtons(Modifier.align(Alignment.TopEnd).padding(8.dp))
            if (replayOpen) {
                ReplayOverlay(engine, settings, replayPanel, aspect, Modifier.matchParentSize())
            } else {
                CameraControls(
                    ui,
                    appSettings.scenes,
                    appSettings.selectedScene,
                    video,
                    engine,
                    draw,
                    Modifier.align(Alignment.BottomEnd).padding(10.dp),
                )
            }
        }
    }
    val enter = fadeIn(motion.defaultEffectsSpec())
    val exit = fadeOut(motion.fastEffectsSpec())

    Surface(color = Color.Black, contentColor = Color.White) {
        BoxWithConstraints(
            Modifier.fillMaxSize().windowInsetsPadding(
                if (banded || large || hinge != null) {
                    WindowInsets.safeDrawing
                } else {
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End)
                },
            ),
        ) {
            val maxWidth = with(LocalDensity.current) { constraints.maxWidth.toDp() }
            val maxHeight = with(LocalDensity.current) { constraints.maxHeight.toDp() }
            if (hinge != null) {
                val foldTop = with(density) { (hinge.top - WindowInsets.safeDrawing.getTop(this)).toDp() }
                    .coerceIn(120.dp, maxHeight - 120.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(foldTop), contentAlignment = Alignment.Center) {
                        val width = minOf(maxWidth - 12.dp, (foldTop - 12.dp) * aspect)
                        picture(Modifier.size(width, width / aspect))
                    }
                    Spacer(Modifier.height(with(density) { hinge.height().toDp() }))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp)) {
                            FlowRow(
                                Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                buttons.forEach { QuickButtonView(it, 48.dp, 60.dp) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AppLogo()
                                SettingsButton(showSettings && !panelHidden) { toggle(Side.SETTINGS) }
                                LiveButton(ui.live, confirmStop, onLive, Modifier.weight(1f).height(52.dp))
                            }
                        }
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = panel,
                            modifier = Modifier.backGesture(back),
                            enter = slideInVertically(motion.defaultSpatialSpec()) { it / 6 } + enter,
                            exit = slideOutVertically(motion.fastSpatialSpec()) { it / 6 } + exit,
                        ) { panelView(false) }
                    }
                }
            } else if (large) {
                val landscape = maxWidth > maxHeight
                val panelWidth = if (landscape) minOf(400.dp, maxWidth * 0.36f) else maxWidth
                val areaWidth = if (landscape && panel.targetState) maxWidth - panelWidth else maxWidth
                val areaHeight = if (!landscape && panel.targetState) maxHeight * 0.55f else maxHeight
                val width = minOf(areaWidth - 24.dp, (areaHeight - 108.dp) * aspect)
                val panelHeight = if (landscape) maxHeight else maxHeight - width / aspect - 108.dp
                Column(Modifier.width(areaWidth).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    picture(Modifier.size(width, width / aspect))
                    Row(
                        Modifier.width(maxOf(width, minOf(areaWidth - 24.dp, 560.dp))).height(96.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            Modifier.padding(bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AppLogo(48.dp)
                            SettingsButton(showSettings && !panelHidden, 48.dp) { toggle(Side.SETTINGS) }
                        }
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            buttons.forEach { QuickButtonView(it, 48.dp, 60.dp) }
                        }
                        LiveButton(ui.live, confirmStop, onLive, Modifier.padding(bottom = 14.dp).size(140.dp, 56.dp))
                    }
                }
                if (panel.currentState || panel.targetState) {
                    Box(
                        Modifier
                            .align(if (landscape) Alignment.CenterEnd else Alignment.BottomCenter)
                            .size(panelWidth, panelHeight)
                            .backGesture(back),
                    ) {
                        androidx.compose.animation.AnimatedVisibility(
                            visibleState = panel,
                            enter = enter + if (landscape) {
                                slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 }
                            } else {
                                slideInVertically(motion.defaultSpatialSpec()) { it / 4 }
                            },
                            exit = exit + if (landscape) {
                                slideOutHorizontally(motion.fastSpatialSpec()) { it / 4 }
                            } else {
                                slideOutVertically(motion.fastSpatialSpec()) { it / 4 }
                            },
                        ) { panelView(false) }
                    }
                }
            } else if (banded) {
                val previewHeight = minOf((maxWidth - 12.dp) / aspect, maxHeight - 140.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).padding(start = 24.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) { statusView(true, Modifier) }
                        AppLogo()
                        Spacer(Modifier.width(8.dp))
                        SettingsButton(showSettings && !panelHidden) { toggle(Side.SETTINGS) }
                    }
                    Box(Modifier.fillMaxWidth().height(previewHeight), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(previewHeight * aspect, previewHeight)) {
                            Preview(engine, ui, 14.dp, access, draw) { flash.value }
                            if (appSettings.chatOnPreview && showOverlays && !replayOpen) {
                                ChatOverlay(
                                    engine,
                                    appSettings.chatTextSize.sp - 1,
                                    3,
                                    Modifier.align(Alignment.BottomStart).fillMaxWidth(0.8f).padding(start = 10.dp, bottom = 48.dp),
                                )
                            }
                            floatingButtons(Modifier.align(Alignment.TopEnd).padding(6.dp))
                        }
                        if (replayOpen) {
                            ReplayOverlay(
                                engine,
                                settings,
                                replayPanel,
                                aspect,
                                Modifier.size(maxOf(previewHeight * aspect, minOf(maxWidth, 300.dp)), previewHeight),
                            )
                        } else {
                            Row(
                                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (draw != null) {
                                    DrawStrip(draw, engine, Modifier)
                                } else {
                                    AnimatedContent(
                                        video,
                                        if (video != null) Modifier.weight(1f) else Modifier,
                                        transitionSpec = { through },
                                        label = "source",
                                    ) { player ->
                                        if (player != null) VideoStrip(player, Modifier.fillMaxWidth()) else ZoomStops(ui, engine::setZoom)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    SceneSwitch(appSettings.scenes, appSettings.selectedScene, engine::selectScene, Modifier.weight(1f, fill = false))
                                }
                            }
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
                            buttons.forEach { QuickButtonView(it, 44.dp, 46.dp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        LiveButton(ui.live, confirmStop, onLive, Modifier.size(96.dp, 52.dp))
                    }
                }
            } else {
                val panelShown = panel.currentState || panel.targetState
                val closedBar = (maxWidth - (maxHeight - 12.dp) * aspect - 12.dp).coerceIn(100.dp, 250.dp)
                val panelWidth = minOf(350.dp, (maxWidth - 100.dp) * 0.5f)
                val open = animateFloatAsState(if (panel.targetState) 1f else 0f, motion.defaultSpatialSpec(), label = "bar")
                val barWidth: Density.() -> Int = { lerp(closedBar, 100.dp, open.value.coerceIn(0f, 1f)).roundToPx() }
                val previewArea = if (panel.targetState) maxWidth - panelWidth - 100.dp else maxWidth - closedBar
                Row(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.width(previewArea).fillMaxHeight().padding(6.dp)) {
                        val maxWidth = with(LocalDensity.current) { constraints.maxWidth.toDp() }
                        val maxHeight = with(LocalDensity.current) { constraints.maxHeight.toDp() }
                        val width = minOf(maxWidth, maxHeight * aspect)
                        val height = width / aspect
                        val below = maxHeight - height >= 96.dp
                        Column(
                            Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.size(width, height)) {
                                Preview(engine, ui, 16.dp, access, draw) { flash.value }
                                statusView(false, Modifier.align(Alignment.TopEnd).padding(12.dp).padding(top = statusInset))
                                floatingButtons(Modifier.align(Alignment.TopEnd).padding(8.dp))
                                if (replayOpen) {
                                    ReplayOverlay(engine, settings, replayPanel, aspect, Modifier.matchParentSize())
                                } else if (!below) {
                                    CameraControls(
                                        ui,
                                        appSettings.scenes,
                                        appSettings.selectedScene,
                                        video,
                                        engine,
                                        draw,
                                        Modifier.align(Alignment.BottomEnd).padding(10.dp),
                                    )
                                }
                            }
                            if (below && !replayOpen) {
                                CameraControls(
                                    ui,
                                    appSettings.scenes,
                                    appSettings.selectedScene,
                                    video,
                                    engine,
                                    draw,
                                    Modifier.width(width).padding(top = 8.dp),
                                )
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (panelShown) {
                        Box(
                            Modifier
                                .align(Alignment.CenterEnd)
                                .offset { IntOffset(-barWidth(), 0) }
                                .wrapContentWidth(Alignment.End, unbounded = true)
                                .width(panelWidth)
                                .fillMaxHeight()
                                .backGesture(back),
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visibleState = panel,
                                enter = slideInHorizontally(motion.defaultSpatialSpec()) { it / 4 } +
                                    fadeIn(motion.defaultEffectsSpec()),
                                exit = slideOutHorizontally(motion.fastSpatialSpec()) { it / 4 } +
                                    fadeOut(motion.fastEffectsSpec()),
                            ) {
                                AnimatedContent(shownSide, transitionSpec = { through }, label = "panel") { shown -> sidePanel(shown, null) }
                            }
                        }
                    }
                    Column(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(Constraints.fixed(barWidth(), constraints.maxHeight))
                                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                            }
                            .background(Color.Black)
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLogo()
                            SettingsButton(showSettings && !panelHidden) { toggle(Side.SETTINGS) }
                        }
                        QuickButtonGrid(
                            buttons,
                            (closedBar / 50.dp).toInt(),
                            { open.value },
                            Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).verticalScroll(rememberScrollState()),
                        )
                        LiveButton(
                            ui.live,
                            confirmStop,
                            onLive,
                            Modifier.padding(horizontal = 8.dp).fillMaxWidth().height(40.dp),
                        )
                    }
                    }
                }
            }
            AnimatedVisibility(stealth, enter = fadeIn(motion.defaultEffectsSpec()), exit = fadeOut(motion.defaultEffectsSpec())) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clickable(interactionSource = null, indication = null) { engine.setStealth(false) },
                )
            }
            if (banded && hinge == null) {
                AnimatedVisibility(
                    visibleState = panel,
                    modifier = Modifier.backGesture(back),
                    enter = slideInVertically(motion.defaultSpatialSpec()) { it / 6 } + enter,
                    exit = slideOutVertically(motion.fastSpatialSpec()) { it / 6 } + exit,
                ) { panelView(true) }
            }
            AlertBanner(engine, Modifier.align(Alignment.TopCenter))
        }
    }

    val left = { target: Side -> side != target && (side != null || (panel.isIdle && !panel.currentState)) }
    val settingsLeft = left(Side.SETTINGS)
    LaunchedEffect(settingsLeft) { if (settingsLeft) settingsPages.reset() }
    val browserLeft = left(Side.BROWSER)
    LaunchedEffect(browserLeft) { if (browserLeft) browser.close() }
    DisposableEffect(browser) { onDispose { browser.close() } }
    LifecycleStartEffect(browser) {
        browser.webView?.onResume()
        onStopOrDispose { browser.webView?.onPause() }
    }
    val chatOnly = banded && hinge == null && side == Side.CHAT && !panelHidden
    LaunchedEffect(stealth, chatOnly) { engine.setPreviewEnabled(!stealth && !chatOnly) }
    LaunchedEffect(confirmStop) {
        if (confirmStop) {
            delay(3000)
            confirmStop = false
        }
    }
    LaunchedEffect(confirmRecordStop, recording == null) {
        if (recording == null) {
            confirmRecordStop = false
        } else if (confirmRecordStop) {
            delay(3000)
            confirmRecordStop = false
        }
    }
    BackHandler(stealth) { engine.setStealth(false) }
    pendingLink?.let { DeepLinkDialog(it, settings, appSettings, engine, busy) }

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

private enum class Side { SETTINGS, CHAT, WIDGETS, OBS, BROWSER, FILTERS, SWITCHER, MACROS, REMOTE }

fun formatBitrate(bps: Long): String = if (bps >= 1_000_000) "%.1f Mbps".format(Locale.ROOT, bps / 1e6) else "${bps / 1000} kbps"

fun formatUptime(seconds: Long): String = "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)

fun formatZoom(zoom: Float): String = if (zoom < 10 && zoom % 1f != 0f) "%.1fx".format(Locale.ROOT, zoom) else "${zoom.toInt()}x"
