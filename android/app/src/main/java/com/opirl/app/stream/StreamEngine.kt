package com.opirl.app.stream

import java.util.Locale
import com.opirl.app.R
import androidx.annotation.StringRes
import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.Movie
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.Surface
import com.opirl.app.camera.CameraCatalog
import com.opirl.app.chat.ChatBot
import com.opirl.app.chat.ChatController
import com.opirl.app.chat.ChatSpeech
import com.opirl.app.chat.EmoteImages
import com.opirl.app.chat.EmoteStore
import com.opirl.app.chat.Platforms
import com.opirl.app.chat.RemoteVoice
import com.opirl.app.chat.YouTubeAuth
import com.opirl.app.chat.createChatClients
import com.opirl.app.effects.EffectLoader
import com.opirl.app.effects.horizonTilt
import com.opirl.app.macros.MacroEvent
import com.opirl.app.macros.MacroHost
import com.opirl.app.macros.MacroRunner
import com.opirl.app.media.gl.EffectSettings
import com.opirl.app.scenes.AutoSceneSwitcher
import com.opirl.app.scenes.expandText
import com.opirl.app.settings.Filter
import com.opirl.app.settings.MacroEventType
import com.opirl.app.settings.Reaction
import com.opirl.app.widgets.DrawBoard
import java.time.LocalDateTime
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import com.opirl.app.chat.spokenText
import com.opirl.app.chat.voiceService
import com.opirl.app.engine.chat.emotes.EmoteProvider
import com.opirl.app.engine.obs.ObsClient
import com.opirl.app.engine.obs.ObsOutput
import com.opirl.app.engine.obs.ObsState
import com.opirl.app.engine.obs.obsUrl
import okhttp3.OkHttpClient
import com.opirl.app.camera.CameraController
import com.opirl.app.camera.CameraDescriptor
import com.opirl.app.camera.CameraState
import com.opirl.app.camera.Facing
import com.opirl.app.camera.LensCamera
import com.opirl.app.camera.Stabilization
import com.opirl.app.engine.abr.ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS
import com.opirl.app.engine.abr.AdaptiveBitrate
import com.opirl.app.engine.abr.BelaboxAdaptiveBitrate
import com.opirl.app.engine.abr.LossAdaptiveBitrate
import com.opirl.app.engine.abr.QueueAdaptiveBitrate
import com.opirl.app.engine.abr.RistAdaptiveBitrate
import com.opirl.app.engine.abr.TransportBitrateMeter
import com.opirl.app.engine.abr.toStreamStats
import com.opirl.app.engine.rist.RistPublisher
import com.opirl.app.engine.rtmp.RtmpPublisher
import com.opirl.app.engine.srt.SrtPublisher
import com.opirl.app.engine.whip.WhipPublisher
import com.opirl.app.settings.allowedCodecs
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.mp4.Mp4Recorder
import com.opirl.app.engine.mp4.Mp4Summary
import com.opirl.app.engine.replay.ReplayBuffer
import com.opirl.app.engine.replay.ReplayClip
import com.opirl.app.engine.media.MediaSink
import com.opirl.app.engine.net.BondedPublisher
import com.opirl.app.engine.net.Publisher
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.media.AUDIO_LEVEL_FLOOR_DB
import com.opirl.app.media.AudioEncoder
import com.opirl.app.media.AudioMix
import com.opirl.app.media.Mic
import com.opirl.app.media.MicCatalog
import com.opirl.app.media.MicInput
import com.opirl.app.media.findMic
import com.opirl.app.media.micLabel
import com.opirl.app.media.CodecCatalog
import com.opirl.app.media.VideoEncoder
import com.opirl.app.media.VideoFile
import com.opirl.app.media.VideoPlayer
import com.opirl.app.media.gl.BrowserPage
import com.opirl.app.media.gl.Overlay
import com.opirl.app.media.gl.VideoCompositor
import com.opirl.app.scenes.defaultScenes
import com.opirl.app.scenes.sceneCamera
import com.opirl.app.widgets.BrowserWidgets
import com.opirl.app.widgets.WidgetRenderer
import com.opirl.app.settings.LoginStore
import com.opirl.app.settings.Scene
import com.opirl.app.settings.SceneVideo
import com.opirl.app.settings.ObsSettings
import com.opirl.app.settings.RECORDING_CODECS
import com.opirl.app.settings.REPLAY_SECONDS
import com.opirl.app.settings.RecordingSettings
import com.opirl.app.recording.RecordingFile
import com.opirl.app.recording.RecordingStore
import java.io.File
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.streamUrl
import com.opirl.app.settings.StabilizationSetting
import com.opirl.app.settings.StreamProfile
import com.opirl.app.settings.Transport
import com.opirl.app.settings.bonded
import com.opirl.app.settings.transport
import com.opirl.app.settings.transportOf
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.Need
import com.opirl.app.widgets.NowPlaying
import com.opirl.app.widgets.WidgetRuntime
import com.opirl.app.widgets.alerts.AlertsSource
import com.opirl.app.widgets.snapshot.SnapshotWidget
import com.opirl.app.widgets.videosource.USB_CAMERA
import com.opirl.app.media.gl.CameraFeed
import com.opirl.app.media.gl.FeedInput
import com.opirl.app.media.gl.FrameTap
import com.opirl.app.media.gl.VTuberSource
import com.opirl.app.widgets.vtuber.VTubers
import com.opirl.app.widgets.sources.FaceTracker
import kotlin.math.abs
import com.opirl.app.widgets.sources.LocationSource
import com.opirl.app.uvc.UvcCamera
import com.opirl.app.uvc.UvcDevices
import com.opirl.app.uvc.closest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbDevice
import android.os.Build
import com.opirl.app.settings.AppSettings
import android.hardware.usb.UsbManager
import android.util.Range
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.opirl.app.control.Controls
import com.opirl.app.control.StreamDecks
import com.opirl.app.engine.net.NetworkLink
import com.opirl.app.engine.net.NetworkLinkProvider
import com.opirl.app.engine.remote.RemoteControlTarget
import com.opirl.app.engine.remote.RemoteRequest
import com.opirl.app.engine.remote.RemoteSettings
import com.opirl.app.engine.remote.RemoteState
import com.opirl.app.engine.remote.RemoteStatus
import com.opirl.app.engine.remote.RemoteStatusItem
import com.opirl.app.media.label
import com.opirl.app.moblink.MoblinkController
import com.opirl.app.remote.REMOTE_MIC_AUTO
import com.opirl.app.remote.RemoteControlController
import com.opirl.app.remote.RemoteStatusInput
import com.opirl.app.remote.bitratePresetId
import com.opirl.app.remote.remoteSettings
import com.opirl.app.remote.remoteState
import com.opirl.app.remote.remoteStatus
import com.opirl.app.remote.zoomPresetId
import com.opirl.app.scenes.nextScene
import com.opirl.app.scenes.placement
import com.opirl.app.scenes.withPlacement
import com.opirl.app.settings.ControllerAction
import com.opirl.app.settings.ControllerFunction
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class LiveStats(
    val bitrateBps: Long = 0,
    val targetBitrateBps: Int = 0,
    val fps: Float = 0f,
    val rttMs: Double = 0.0,
    val queuedBytes: Long = 0,
    val droppedFrames: Long = 0,
    val uptimeSeconds: Long = 0,
    val bytesSent: Long = 0,
    val codec: VideoCodec? = null,
    val links: List<LinkShare> = emptyList(),
    val totalBytes: Long = 0,
)

data class UiState(
    val cameras: List<CameraDescriptor> = emptyList(),
    val camera: CameraDescriptor? = null,
    val lens: LensCamera? = null,
    val cameraState: CameraState = CameraState.Closed,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val muted: Boolean = false,
    val live: LiveState = LiveState.OFFLINE,
    val error: String? = null,
    val thermal: Int = PowerManager.THERMAL_STATUS_NONE,
    val battery: Int = 100,
    val temperature: Int = 0,
    val charging: Boolean = false,
    val stealth: Boolean = false,
) {
    val activeCamera: CameraDescriptor? get() = lens?.camera ?: camera
}

data class RecordingStats(val seconds: Long = 0, val bytes: Long = 0)

private class Feed(val key: String, val camera: CameraDescriptor, val compositor: VideoCompositor) {
    var uvc: UvcCamera? = null
    var width = 0
    var height = 0
}

enum class ReplayState { IDLE, PENDING, PLAYING }

private class LiveOutput(val id: String, val name: String, val session: StreamSession, opus: Boolean) {
    val sink: MediaSink = if (opus) VideoOnlySink(session) else session
    val opusSink: MediaSink? = if (opus) AudioOnlySink(session) else null
}

private class ActiveRecording(
    val file: RecordingFile,
    val recorder: Mp4Recorder,
    val settings: RecordingSettings,
    val startedAt: Long,
)

class StreamEngine(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val camera = CameraController(context)
    private val feedCamera = CameraController(context)
    private var feed: Feed? = null
    private val links = AndroidNetworkLinks(context)
    private val uvcDevices = UvcDevices(context)
    private var uvcCamera: UvcCamera? = null
    private var uvcSurface: Surface? = null
    private var cameraGeneration = 0
    private var uvcFailures = 0
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()
    val http = OkHttpClient()
    val logins = LoginStore(File(context.filesDir, "logins.json"))
    val speech = ChatSpeech(context) { showAlert(AlertLevel.WARNING, text(R.string.chat_speech), it) }
    val youtubeAuth = YouTubeAuth(context)
    val platforms = Platforms(http, { settings.settings.value.selectedProfile }, { logins.logins.value }, youtubeAuth)
    private val emotes = EmoteStore(scope, http)
    private val emoteImages = EmoteImages(context, scope)
    private val bot = ChatBot(this, settings, scope, text(R.string.chat_bot))
    val chat = ChatController(
        scope,
        { listener -> createChatClients(settings.settings.value.selectedProfile, logins.logins.value, http, listener) },
        emotes::apply,
        emotes::setChannel,
        { message ->
            val current = settings.settings.value
            if (current.chatSpeech) {
                spokenText(message, current.chatSpeechNames, context.getString(R.string.chat_speech_said))?.let(speech::say)
            }
            bot.onMessage(message)
        },
    )
    val obs = ObsClient(http, { showAlert(AlertLevel.ERROR, "OBS", it) })
    val moblink = MoblinkController(context, scope, http, { _ui.value.battery }, { _ui.value.thermal })
    private val streamLinks = object : NetworkLinkProvider {
        override val links: StateFlow<List<NetworkLink>> =
            combine(this@StreamEngine.links.links, moblink.links) { own, borrowed -> own + borrowed }
                .stateIn(scope, SharingStarted.Eagerly, emptyList())
    }
    val controls = Controls(context, { settings.settings.value }, ::perform) { connected, number ->
        showAlert(AlertLevel.INFO, text(if (connected) R.string.gamepad_connected else R.string.gamepad_disconnected, number))
    }
    val streamDecks = StreamDecks(context, { settings.settings.value.selectedStreamDeck }, ::perform)
    private val remoteTarget = object : RemoteControlTarget {
        override fun status(): RemoteStatus = remoteStatus()

        override fun settings(): RemoteSettings =
            remoteSettings(settings.settings.value, _ui.value.cameras, text(R.string.mic_auto), mics.value.map { it.key to micLabel(it) })

        override fun state(): RemoteState = remoteState(settings.settings.value, _ui.value, _recording.value != null)

        override fun handle(request: RemoteRequest): Boolean = handleRemote(request)
    }
    val remote = RemoteControlController(
        context,
        scope,
        http,
        remoteTarget,
        { width, onResult -> synchronized(this) { compositor }?.takeThumbnail(width, onResult) ?: onResult(null) },
        { showAlert(AlertLevel.ERROR, text(R.string.remote_control), it) },
    )
    private var zoomRamp: Job? = null
    val pendingLink = MutableStateFlow<String?>(null)

    internal fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var alertJob: Job? = null
    private var alertId = 0L
    private var lowBitrate = LowBitrateWatch()
    private val _audioLevel = MutableStateFlow(AUDIO_LEVEL_FLOOR_DB)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private val micCatalog = MicCatalog(context)
    val mics: StateFlow<List<Mic>> = micCatalog.mics
    private var micInput = MicInput(null)
    private var linkBytes = emptyMap<String, Long>()

    @Volatile
    private var compositor: VideoCompositor? = null
    private var overlays = emptyList<Overlay>()
    @Volatile
    private var widgetNeeds = emptySet<Need>()
    private var widgetOverlays = emptyList<Overlay>()
    private val drawBoard = DrawBoard()
    val drawnLines: StateFlow<Int> = drawBoard.count
    private var drawPending = false
    private var effectSettings = EffectSettings()
    private val effectLoader = EffectLoader(context) { name, message ->
        showAlert(AlertLevel.ERROR, text(R.string.alert_lut_failed), "$name: $message")
    }
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var horizonOn = false
    private var macrosStarted = false
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val target = compositor ?: return
            val descriptor = _ui.value.activeCamera
            if (_videoPlayer.value != null || descriptor == null || descriptor.facing == Facing.EXTERNAL) {
                target.horizonTilt = null
            } else {
                horizonTilt(event.values[0], event.values[1], displayRotation)?.let { target.horizonTilt = it }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }
    val macros = MacroRunner(
        scope,
        object : MacroHost {
            override fun selectScene(id: String) {
                this@StreamEngine.selectScene(id)
                syncScene()
            }

            override fun setZoom(ratio: Float) = this@StreamEngine.setZoom(ratio)

            override fun setFilters(filters: Set<Filter>) = settings.update { it.copy(effects = it.effects.copy(filters = filters)) }

            override fun react(reaction: Reaction) = this@StreamEngine.react(reaction)

            override fun setRecording(on: Boolean) = if (on) startRecording() else stopRecording()

            override fun snapshot() = takeSnapshot()

            override fun setMuted(muted: Boolean) = this@StreamEngine.setMuted(muted)

            override fun setTorch(on: Boolean) = this@StreamEngine.setTorch(on)

            override fun setAutoSwitcher(id: String?) = settings.update { it.copy(autoSwitcherId = id) }

            override fun sendChat(text: String) = chat.canSend.value.forEach { platform -> chat.send(platform, text) {} }

            override fun notify(title: String, text: String) = showAlert(AlertLevel.INFO, title, text)

            override fun format(text: String) = expandText(text, LocalDateTime.now())
        },
    ) { settings.settings.value.macros }
    private val browsers = BrowserWidgets(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSurface: Surface? = null

    @Volatile
    private var displayRotation = 0
    private var videoEncoder: VideoEncoder? = null
    private var recordEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var audioLive = false
    private val tee = MediaTee()
    private val opusTee = MediaTee()
    private var session: StreamSession? = null
    private var mainOutput: LiveOutput? = null
    private var extraOutputs = emptyList<LiveOutput>()
    private var monitorOutput: LiveOutput? = null
    private var monitorEncoder: VideoEncoder? = null
    private var monitorJob: Job? = null
    private val _monitoring = MutableStateFlow(false)
    val monitoring: StateFlow<Boolean> = _monitoring.asStateFlow()
    private val _destinations = MutableStateFlow<Map<String, LiveState>>(emptyMap())
    val destinations: StateFlow<Map<String, LiveState>> = _destinations.asStateFlow()
    private var replayBuffer: ReplayBuffer? = null
    private var replayJob: Job? = null
    private var replayPlaying: SceneVideo? = null
    private var replayStartMs = 0L
    private val _replay = MutableStateFlow(ReplayState.IDLE)
    val replay: StateFlow<ReplayState> = _replay.asStateFlow()
    private var activeRecording: ActiveRecording? = null
    private var liveProfile: StreamProfile? = null
    private var liveCodec: VideoCodec? = null
    private var liveJob: Job? = null
    private var ticker: Job? = null
    val recordings = RecordingStore(context)
    private val audioMix = AudioMix()
    val nowPlaying = NowPlaying(context)
    val location = LocationSource(context)
    private val faceTracker = FaceTracker()
    private var faceTap: Pair<VideoCompositor, String?>? = null
    private val vtubers = VTubers(File(context.filesDir, "widgets"), faceTracker.faces)
    val widgets = WidgetRuntime(context, scope, settings, http, chat.lines, emoteImages, nowPlaying.track, location.location, faceTracker.faces, stats, ui)
    private val alertsSource = AlertsSource(widgets, logins.logins, chat.alerts) { showAlert(AlertLevel.ERROR, "Twitch", it) }
    private val widgetRenderer = WidgetRenderer(
        widgets,
        { next ->
            synchronized(this) {
                widgetOverlays = next
                applyOverlays()
                syncBrowsers()
                syncVTubers()
                syncFeed(true)
            }
        },
        { needs ->
            widgetNeeds = needs
            syncWidgetSources()
        },
    )
    private val _videoPlayer = MutableStateFlow<VideoPlayer?>(null)
    val videoPlayer: StateFlow<VideoPlayer?> = _videoPlayer.asStateFlow()
    private var videoInput: Triple<Int, Int, Int>? = null
    private var playingVideo: Pair<String, List<SceneVideo>>? = null
    private val _recording = MutableStateFlow<RecordingStats?>(null)
    val recording: StateFlow<RecordingStats?> = _recording.asStateFlow()
    private var liveStartedAt = 0L
    private var targetBitrate = 0
    private var previewActive = false

    init {
        scope.launch {
            camera.state.collect { s ->
                _ui.update { it.copy(cameraState = s) }
                if (s is CameraState.Error && !s.recoverable) dropFixedLenses()
            }
        }
        camera.onActiveLens = ::onActiveLens
        scope.launch {
            settings.settings
                .map { s -> listOf(s.selectedProfile.resolution, s.selectedProfile.portrait, s.selectedProfile.fps, s.stabilization) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    synchronized(this@StreamEngine) {
                        if (busy || !previewActive) return@collect
                        val (w, h) = outputSize(settings.settings.value.selectedProfile)
                        compositor?.setOutputSize(w, h)
                        drawBoard.setSize(w, h)
                        applyOverlays()
                        openCamera()
                    }
                }
        }
        scope.launch {
            settings.settings.map { it.effects to outputSize(it.selectedProfile) }.distinctUntilChanged().conflate().collect { (effects, size) ->
                val loaded = withContext(Dispatchers.IO) { effectLoader.load(effects, size.first, size.second) }
                synchronized(this@StreamEngine) {
                    effectSettings = loaded
                    compositor?.setEffects(loaded)
                }
            }
        }
        scope.launch {
            settings.settings.map { it.fixedHorizon }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { syncHorizon() }
            }
        }
        scope.launch {
            settings.settings.map { it.autoSwitcherId }.distinctUntilChanged().collectLatest { id ->
                val switching = AutoSceneSwitcher()
                while (id != null) {
                    val current = settings.settings.value
                    val next = current.autoSwitchers.firstOrNull { it.id == id }?.takeIf { previewActive }?.let { switcher ->
                        switching.next(switcher) { sceneId -> current.scenes.any { it.id == sceneId } }
                    }
                    if (next == null) {
                        delay(1000)
                    } else {
                        selectScene(next.sceneId!!)
                        delay(next.seconds.coerceAtLeast(1) * 1000L)
                    }
                }
            }
        }
        scope.launch {
            settings.settings.map { it.selectedScene?.id }.distinctUntilChanged().drop(1).collect { id ->
                macros.onEvent(MacroEvent(MacroEventType.SWITCH_SCENE, id))
            }
        }
        scope.launch {
            settings.settings.map { it.scenes.isEmpty() }.distinctUntilChanged().drop(1).collect { empty ->
                if (empty) ensureScenes(_ui.value.cameras)
            }
        }
        scope.launch {
            settings.settings
                .map { Triple(it.selectedScene?.cameraKey, it.selectedScene?.let(::videoKey), usbIsFeed(it)) }
                .distinctUntilChanged()
                .drop(1)
                .collect { synchronized(this@StreamEngine) { syncScene() } }
        }
        scope.launch {
            settings.settings.map { it.selectedScene?.let { scene -> scene.videoVolume to scene.micWithVideo } }
                .distinctUntilChanged()
                .collect { sound ->
                    synchronized(this@StreamEngine) {
                        val player = _videoPlayer.value
                        if (sound != null && player != null) {
                            player.volume = sound.first / 100f
                            player.micWhilePlaying = sound.second
                        }
                    }
                }
        }
        scope.launch {
            settings.settings.map { it.mirrorFrontCameraOnStream to it.usbCameraRotation }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { applyInputTransform() }
            }
        }
        scope.launch {
            settings.settings.map { it.chatSpeech }.distinctUntilChanged().collect { speech.setEnabled(it) }
        }
        scope.launch {
            settings.settings
                .map { s -> s.selectedProfile.let { listOf(it.twitchChannel, it.kickChannel, it.youtubeChannel) } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1000)
                .collect { synchronized(this@StreamEngine) { if (previewActive || isLive) startChat() } }
        }
        scope.launch {
            settings.settings.map { it.disabledEmotes }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { if (previewActive || isLive) startChat() }
            }
        }
        scope.launch {
            combine(
                settings.settings.map { it.chatSpeechProvider to it.chatSpeechVoices }.distinctUntilChanged(),
                logins.logins.map { it.elevenLabsKey to it.ttsMonsterToken }.distinctUntilChanged(),
            ) { (provider, voices), _ ->
                val voice = voices[provider]
                val service = voiceService(http, provider, logins.logins.value)
                if (voice == null || service == null) null else RemoteVoice(service, voice.id)
            }.collect { speech.voice = it }
        }
        scope.launch {
            settings.settings
                .map { s -> s.selectedProfile.obs.let { Triple(it.enabled, it.url, it.password) } }
                .distinctUntilChanged()
                .drop(1)
                .debounce(1000)
                .collect { synchronized(this@StreamEngine) { syncObs() } }
        }
        scope.launch {
            settings.settings.map { s -> s.widgets.any { it.type == WidgetType.MUSIC } }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { syncWidgetSources() }
            }
        }
        scope.launch {
            logins.logins.map { it.twitch to it.kick }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { if (previewActive || isLive) startChat() }
            }
        }
        scope.launch {
            combine(settings.settings.map { it.micKey }.distinctUntilChanged(), micCatalog.mics) { key, _ -> key }.drop(1).collect {
                synchronized(this@StreamEngine) { audioEncoder?.let { it.input = openMic() } }
            }
        }
        scope.launch {
            settings.settings.map { it.moblink to it.remoteControl }.distinctUntilChanged().drop(1).debounce(500).collect {
                synchronized(this@StreamEngine) { syncConnections() }
            }
        }
        scope.launch {
            settings.settings.map { it.selectedStreamDeck }.distinctUntilChanged().drop(1).collect { streamDecks.show() }
        }
        scope.launch {
            combine(settings.settings, _ui, _recording) { current, ui, recording -> remoteState(current, ui, recording != null) }
                .distinctUntilChanged()
                .conflate()
                .collect(remote::stateChanged)
        }
        scope.launch { moblink.relayRunning.drop(1).collect { synchronized(this@StreamEngine) { updateService() } } }
        controls.start()
        ioScope.launch {
            val recovered = recordings.recover()
            if (recovered > 0) {
                showAlert(AlertLevel.INFO, context.resources.getQuantityString(R.plurals.recordings_recovered, recovered, recovered))
            }
        }
        powerManager.addThermalStatusListener(context.mainExecutor) { status ->
            val previous = _ui.value.thermal
            _ui.update { it.copy(thermal = status) }
            scope.launch {
                val lowered = applyThermal(status)
                if (status >= PowerManager.THERMAL_STATUS_CRITICAL && previous < PowerManager.THERMAL_STATUS_CRITICAL) {
                    showAlert(
                        AlertLevel.ERROR,
                        text(R.string.alert_overheating),
                        text(if (lowered) R.string.alert_frame_rate_lowered else R.string.alert_overheating_detail),
                    )
                } else if (status == PowerManager.THERMAL_STATUS_SEVERE && previous < PowerManager.THERMAL_STATUS_SEVERE) {
                    showAlert(AlertLevel.WARNING, text(R.string.alert_hot), text(R.string.alert_hot_detail))
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                    val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10
                    val battery = level * 100 / scale
                    val previous = _ui.value.battery
                    val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                    _ui.update { it.copy(battery = battery, temperature = temperature, charging = charging) }
                    if (!charging && listOf(15, 5).any { battery <= it && previous > it }) {
                        showAlert(AlertLevel.WARNING, text(R.string.battery_low), "$battery%")
                    }
                }
            },
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    onUsbChanged(
                        device?.takeIf { intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED }
                            ?.let { UVC_PREFIX + it.deviceName },
                    )
                }
            },
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun listCameras(): List<CameraDescriptor> {
        val builtIn = cachedCameras ?: runCatching { CameraCatalog.list(context) }
            .onFailure { Log.e(TAG, "Camera catalog failed", it) }
            .getOrDefault(emptyList())
            .also { cachedCameras = it }
        return builtIn + uvcDevices.list().map(::uvcDescriptor)
    }

    private fun uvcDescriptor(device: UsbDevice) = CameraDescriptor(
        id = UVC_PREFIX + device.deviceName,
        physicalId = null,
        facing = Facing.EXTERNAL,
        label = device.productName?.trim()?.takeIf { it.isNotEmpty() } ?: text(R.string.camera_usb),
        zoomRatioRange = 1f..1f,
        lensZoomStops = emptyList(),
        sensorOrientation = 0,
        sizes = emptyList(),
        fpsRanges = listOf(Range(30, 30)),
        supports60Fps = true,
        stabilizationModes = listOf(Stabilization.OFF),
        hasFlash = false,
    )

    private fun onUsbChanged(attachedId: String?) {
        streamDecks.refresh()
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                val current = _ui.value.camera
                val attached = cameras.firstOrNull { it.id == attachedId }.takeUnless { usbIsFeed(settings.settings.value) }
                val next = attached ?: sceneCamera(cameras)
                _ui.update { it.copy(cameras = cameras) }
                if (next != null && next.key != current?.key) {
                    _ui.update { it.copy(camera = next, lens = defaultLens(next), zoom = 1f, torch = false) }
                    if (previewActive) openCamera()
                } else {
                    syncFeed(true)
                }
            }
        }
    }

    private val unopenableLenses = HashSet<String>()

    fun showAlert(level: AlertLevel, title: String, detail: String? = null) {
        val alert = Alert(++alertId, level, title, detail)
        _alert.value = alert
        if (settings.settings.value.alertHaptics) {
            vibrator?.vibrate(
                when (level) {
                    AlertLevel.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120, 80, 220), -1)
                    AlertLevel.WARNING -> VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100), -1)
                    AlertLevel.INFO -> VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE)
                },
            )
        }
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(if (level == AlertLevel.INFO) 3000 else 6000)
            _alert.compareAndSet(alert, null)
        }
    }

    fun dismissAlert() {
        _alert.value = null
    }

    private fun lenses(main: CameraDescriptor): List<LensCamera> =
        if (main.id in settings.settings.value.fixedLensCameras) main.lenses + main.fallbackLenses else main.lenses

    private fun defaultLens(main: CameraDescriptor): LensCamera? =
        lenses(main).firstOrNull { it.ratio == 1f && main.id in settings.settings.value.fixedLensCameras }

    private fun onActiveLens(opened: CameraDescriptor, activeId: String?) {
        scope.launch {
            synchronized(this@StreamEngine) {
                val state = _ui.value
                val main = state.camera ?: return@launch
                if (state.lens != null || opened.key != main.key || activeId == null) return@launch
                if (main.id in unopenableLenses) return@launch
                val expected = main.fallbackLenses.lastOrNull { it.ratio <= state.zoom + 0.01f } ?: return@launch
                if (main.lenses.none { it.camera.id == activeId }) return@launch
                Log.w(TAG, "Camera ${main.id} stays on lens $activeId, opening its lenses by their own ids")
                settings.update { it.copy(fixedLensCameras = it.fixedLensCameras + main.id) }
                _ui.update { it.copy(lens = expected, zoom = expected.ratio, torch = false) }
                if (previewActive) openCamera()
            }
        }
    }

    @Synchronized
    private fun dropFixedLenses() {
        val state = _ui.value
        val main = state.camera ?: return
        if (state.lens == null || main.fallbackLenses.none { it.camera.key == state.lens.camera.key }) return
        Log.w(TAG, "Lens camera ${state.lens.camera.id} cannot be opened, going back to camera ${main.id}")
        unopenableLenses += main.id
        settings.update { it.copy(fixedLensCameras = it.fixedLensCameras - main.id) }
        _ui.update { it.copy(lens = null, zoom = 1f, torch = false) }
        if (previewActive) openCamera()
    }

    private val thermalGuard = ThermalGuard()

    @Synchronized
    private fun applyThermal(status: Int): Boolean {
        val wanted = (liveProfile ?: settings.settings.value.selectedProfile).fps
        val before = thermalGuard.fps(wanted)
        thermalGuard.update(status, busy)
        val after = thermalGuard.fps(wanted)
        if (after == before) return false
        Log.w(TAG, "Thermal status $status, frame rate $after")
        compositor?.setFrameRate(after)
        if (previewActive) openCamera()
        restartVideoEncoders("thermal status $status")
        return after < before
    }

    private fun defaultCamera(cameras: List<CameraDescriptor>) =
        cameras.firstOrNull { it.facing == Facing.BACK } ?: cameras.firstOrNull()

    private fun sceneCamera(cameras: List<CameraDescriptor>): CameraDescriptor? = sceneCamera(
        cameras,
        _ui.value.camera?.key,
        settings.settings.value.selectedScene?.cameraKey,
        { it.key },
        { it.facing == Facing.EXTERNAL && !usbIsFeed(settings.settings.value) },
    ) ?: defaultCamera(cameras)

    private fun usbIsFeed(settings: AppSettings): Boolean {
        val scene = settings.selectedScene ?: return false
        return scene.widgets.any { placement ->
            placement.enabled && settings.widgets.any {
                it.id == placement.widgetId && it.type == WidgetType.VIDEO_SOURCE && it.videoSource.camera == USB_CAMERA
            }
        }
    }

    fun selectScene(id: String) = settings.update { it.copy(selectedSceneId = id) }

    private var sceneVideo = settings.settings.value.selectedScene?.let(::videoKey)

    @Synchronized
    private fun syncScene() {
        val video = settings.settings.value.selectedScene?.let(::videoKey)
        val next = sceneCamera(_ui.value.cameras)
        val changed = next != null && next.key != _ui.value.camera?.key
        if (changed) _ui.update { it.copy(camera = next, lens = defaultLens(next!!), zoom = 1f, torch = false) }
        if (previewActive && (changed || video != sceneVideo)) openCamera()
        sceneVideo = video
    }

    private fun ensureScenes(cameras: List<CameraDescriptor>) = settings.update { current ->
        if (current.scenes.isNotEmpty()) return@update current
        val scenes = defaultScenes(cameras.filter { it.facing != Facing.EXTERNAL }.map { it.key to it.label })
        current.copy(
            scenes = scenes,
            selectedSceneId = (scenes.firstOrNull { it.cameraKey == current.cameraKey } ?: scenes.firstOrNull())?.id,
        )
    }

    val isLive: Boolean get() = session != null

    private fun startChat() {
        emotes.start(EmoteProvider.entries.toSet() - settings.settings.value.disabledEmotes)
        chat.start()
    }

    private fun stopChat() {
        emotes.stop()
        chat.stop()
    }

    private val busy: Boolean get() = session != null || activeRecording != null || monitorOutput != null

    fun startPreview() {
        synchronized(this) {
            if (previewActive) return
            previewActive = true
        }
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                if (!previewActive) return@launch
                if (!macrosStarted) {
                    macrosStarted = true
                    macros.startAutomatic()
                }
                ensureScenes(cameras)
                val selected = sceneCamera(cameras)
                _ui.update { it.copy(cameras = cameras, camera = selected, lens = selected?.let(::defaultLens)) }
                ensureCompositor()
                openCamera()
                if (!busy) {
                    startAudioMeter()
                    startChat()
                }
                syncObs()
                syncWidgetSources()
                syncConnections()
                syncHorizon()
            }
        }
    }

    private fun micLabel(mic: Mic): String = micLabel(mic, text(mic.kind.label))

    private fun openMic(): MicInput {
        val next = micCatalog.open(settings.settings.value.micKey)
        if (next.device?.id != micInput.device?.id || next.source != micInput.source) micInput = next
        return micInput
    }

    private fun syncHorizon() {
        val sensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val wanted = sensor != null && previewActive && settings.settings.value.fixedHorizon
        if (wanted == horizonOn) return
        horizonOn = wanted
        if (wanted) {
            sensors.registerListener(gravityListener, sensor, 100_000, mainHandler)
        } else {
            sensors.unregisterListener(gravityListener)
            compositor?.horizonTilt = null
        }
    }

    @Suppress("DEPRECATION")
    fun react(reaction: Reaction) {
        val target = compositor ?: return
        ioScope.launch {
            val movie = runCatching {
                context.assets.open("props/${reaction.name.lowercase(Locale.ROOT)}.gif").use { Movie.decodeStream(it) }
            }.getOrNull() ?: return@launch
            target.playProp(reaction, movie)
        }
    }

    fun drawBegin(x: Float, y: Float, color: Int, width: Float) {
        drawBoard.begin(x, y, color, width)
        drawChanged()
    }

    fun drawExtend(x: Float, y: Float) {
        drawBoard.extend(x, y)
        drawChanged()
    }

    fun drawEnd() {
        drawBoard.end()
        drawChanged()
    }

    fun drawUndo() {
        drawBoard.undo()
        drawChanged()
    }

    fun drawWipe() {
        drawBoard.wipe()
        drawChanged()
    }

    private fun drawChanged() {
        if (drawPending) return
        drawPending = true
        mainHandler.postDelayed(
            {
                drawPending = false
                synchronized(this) { applyOverlays() }
            },
            33,
        )
    }

    private fun applyOverlays() {
        overlays = widgetOverlays + listOfNotNull(drawBoard.overlay())
        compositor?.setOverlays(overlays)
    }

    private fun startAudioMeter() {
        audioEncoder?.release()
        audioEncoder = null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        audioEncoder = runCatching {
            AudioEncoder(0, null, openMic(), audioMix, { _audioLevel.value = it }) { Log.w(TAG, "Audio meter: $it") }
        }.onFailure { Log.w(TAG, "Audio meter failed", it) }.getOrNull()?.also { it.muted = _ui.value.muted }
    }

    @Volatile
    private var cachedCameras: List<CameraDescriptor>? = null

    @Synchronized
    fun stopPreviewIfIdle() {
        if (!previewActive || busy) return
        previewActive = false
        syncConnections()
        syncHorizon()
        stopChat()
        obs.stop()
        syncWidgetSources()
        audioEncoder?.release()
        audioEncoder = null
        micCatalog.close()
        closeSources()
        val closing = compositor
        compositor = null
        syncBrowsers()
        syncVTubers()
        syncFeed(false)
        syncFaces()
        closing?.release()
    }

    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        compositor?.setPreview(if (previewEnabled) surface else null)
    }

    @Synchronized
    fun setDisplayRotation(degrees: Int) {
        if (degrees == displayRotation) return
        displayRotation = degrees
        applyInputTransform()
    }

    @Synchronized
    fun setZoom(ratio: Float, switchLens: Boolean = true) {
        val main = _ui.value.camera ?: return
        val lens = if (switchLens) lenses(main).firstOrNull { it.ratio == ratio } else _ui.value.lens
        val descriptor = lens?.camera ?: main
        val base = lens?.ratio ?: 1f
        val zoom = (ratio / base).coerceIn(descriptor.zoomRatioRange.start, descriptor.zoomRatioRange.endInclusive)
        if (descriptor.key != _ui.value.activeCamera?.key) {
            _ui.update { it.copy(lens = lens, torch = false) }
            if (previewActive) openCamera()
        }
        camera.setZoom(zoom)
        _ui.update { it.copy(zoom = base * zoom) }
    }

    fun setTorch(on: Boolean) {
        camera.setTorch(on)
        _ui.update { it.copy(torch = on) }
    }

    fun setMuted(muted: Boolean) {
        audioEncoder?.muted = muted
        _ui.update { it.copy(muted = muted) }
    }

    fun setStealth(on: Boolean) = _ui.update { it.copy(stealth = on) }

    fun reloadBrowserWidgets() {
        mainHandler.post(browsers::reload)
    }

    private fun syncConnections() {
        val current = settings.settings.value
        val active = previewActive || busy
        moblink.sync(current.moblink, active)
        remote.sync(current.remoteControl, active)
        streamDecks.setActive(active)
    }

    @Synchronized
    fun restartConnections() {
        val current = settings.settings.value
        moblink.sync(current.moblink, false)
        remote.sync(current.remoteControl, false)
        obs.stop()
        syncConnections()
        syncObs()
    }

    fun perform(action: ControllerAction, pressed: Boolean) {
        val current = settings.settings.value
        when (action.function) {
            ControllerFunction.ZOOM_IN -> rampZoom(if (pressed) 1.03f else null)
            ControllerFunction.ZOOM_OUT -> rampZoom(if (pressed) 1 / 1.03f else null)
            else -> if (!pressed) {
                when (action.function) {
                    ControllerFunction.RECORD -> if (_recording.value == null) startRecording() else stopRecording()
                    ControllerFunction.STREAM -> if (isLive) stopLive() else goLive()
                    ControllerFunction.MUTE -> setMuted(!_ui.value.muted)
                    ControllerFunction.TORCH -> if (_ui.value.activeCamera?.hasFlash == true) setTorch(!_ui.value.torch)
                    ControllerFunction.STEALTH -> setStealth(!_ui.value.stealth)
                    ControllerFunction.SCENE -> current.scenes.firstOrNull { it.id == action.sceneId }?.let { selectScene(it.id) }
                    ControllerFunction.SWITCH_SCENE -> nextScene(current.scenes.map { it.id }, current.selectedScene?.id)?.let(::selectScene)
                    ControllerFunction.WIDGET -> {
                        val scene = current.selectedScene
                        val widget = current.widgets.firstOrNull { it.id == action.widgetId }
                        if (scene != null && widget != null) {
                            settings.update { it.withPlacement(scene.id, scene.placement(widget).let { p -> p.copy(enabled = !p.enabled) }) }
                        }
                    }
                    ControllerFunction.SNAPSHOT -> takeSnapshot()
                    ControllerFunction.STREAM_DECK_LAYOUT -> settings.update { it.copy(selectedStreamDeckId = action.layoutId) }
                    else -> Unit
                }
            }
        }
    }

    @Synchronized
    private fun rampZoom(factor: Float?) {
        zoomRamp?.cancel()
        zoomRamp = factor?.let {
            scope.launch {
                while (isActive) {
                    val before = _ui.value.zoom
                    setZoom(before * it, switchLens = false)
                    if (_ui.value.zoom == before) break
                    delay(33)
                }
            }
        }
    }

    private fun handleRemote(request: RemoteRequest): Boolean {
        val current = settings.settings.value
        when (request) {
            is RemoteRequest.SetRecord -> if (request.on) startRecording() else stopRecording()
            is RemoteRequest.SetLive -> if (request.on) goLive() else stopLive()
            is RemoteRequest.SetMute -> setMuted(request.on)
            is RemoteRequest.SetStealthMode -> setStealth(request.on)
            is RemoteRequest.SetTorch -> setTorch(request.on)
            is RemoteRequest.SetZoom -> setZoom(request.x)
            is RemoteRequest.SetZoomPreset -> setZoom(
                _ui.value.camera?.lensZoomStops?.firstOrNull { zoomPresetId(it).equals(request.id, ignoreCase = true) } ?: return false,
            )
            is RemoteRequest.SetStream -> {
                val id = current.profiles.firstOrNull { it.id.equals(request.id, ignoreCase = true) }?.id ?: return false
                if (synchronized(this) { busy }) return false
                settings.update { it.copy(selectedProfileId = id) }
            }
            is RemoteRequest.SetScene -> selectScene(current.scenes.firstOrNull { it.id.equals(request.id, ignoreCase = true) }?.id ?: return false)
            is RemoteRequest.SetBitratePreset -> setMaxBitrate(
                current.bitratePresetsKbps.firstOrNull { bitratePresetId(it).equals(request.id, ignoreCase = true) } ?: return false,
            )
            is RemoteRequest.SetMic -> {
                val key = if (request.id == REMOTE_MIC_AUTO) null else mics.value.firstOrNull { it.key == request.id }?.key ?: return false
                settings.update { it.copy(micKey = key) }
            }
            RemoteRequest.ReloadBrowserWidgets -> reloadBrowserWidgets()
            is RemoteRequest.SetDebugLogging -> Unit
            else -> return false
        }
        return true
    }

    private fun remoteStatus(): RemoteStatus {
        val current = settings.settings.value
        val profile = liveProfile ?: current.selectedProfile
        val (w, h) = outputSize(profile)
        val codec = liveCodec ?: CodecCatalog.choose(profile.codec, profile.allowedCodecs, w, h, profile.fps)
        val transport = profile.url.substringBefore("://").uppercase()
        val obsState = obs.state.value
        return remoteStatus(
            RemoteStatusInput(
                settings = current,
                ui = _ui.value,
                stats = _stats.value,
                recording = _recording.value,
                audioLevel = _audioLevel.value,
                streamText = "${profile.name} (${profile.resolution.label}${profile.fps}, ${codec.label}, $transport)",
                micName = findMic(mics.value, current.micKey)?.let(::micLabel) ?: text(R.string.mic_auto),
                obs = when {
                    !profile.obs.enabled -> null
                    obsState.connected -> obsState.scene ?: text(R.string.connected)
                    else -> obsState.error ?: text(R.string.connecting)
                },
                moblink = moblink.status.value?.let { RemoteStatusItem(it.text, it.ok) },
                remoteControl = remote.status.value?.let { RemoteStatusItem(it.text, it.ok) },
                gameControllers = controls.gamepadCount.value,
                mutedText = text(R.string.muted),
            ),
        )
    }

    @Synchronized
    fun tapToFocus(x: Float, y: Float) {
        compositor?.previewToInput(x, y, camera::tapToFocus)
    }

    fun setExposureCompensation(ev: Float) = camera.setExposureCompensation(ev)

    private fun hasLivePermissions(): Boolean {
        if (LIVE_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) return true
        _ui.update { it.copy(error = text(R.string.error_permissions)) }
        return false
    }

    private fun begin(profile: StreamProfile): Boolean {
        val (w, h) = outputSize(profile)
        val codec = CodecCatalog.choose(profile.codec, profile.allowedCodecs, w, h, profile.fps)
        if (CodecCatalog.encoders[codec] == null) {
            _ui.update { it.copy(error = text(R.string.error_no_encoder)) }
            return false
        }
        liveProfile = profile
        liveCodec = codec
        targetBitrate = profile.bitrateKbps * 1000
        liveMaxBitrate = targetBitrate.toLong()
        ensureCompositor()
        if (!previewActive) startPreview()
        compositor!!.setOutputSize(w, h)
        drawBoard.setSize(w, h)
        applyOverlays()
        compositor!!.setFrameRate(encoderFps(profile))
        return true
    }

    private fun end() {
        updateService()
        if (busy) return
        ticker?.cancel()
        ticker = null
        liveProfile = null
        stopReplay()
        replayBuffer = null
        syncSinks()
        scope.launch { applyThermal(_ui.value.thermal) }
        if (previewSurface == null) stopPreviewIfIdle() else if (previewActive) startAudioMeter()
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (isActive) {
                delay(1000)
                tick()
            }
        }
    }

    private fun updateService() {
        val relay = moblink.relayRunning.value
        val title = when {
            session != null && activeRecording != null -> text(R.string.notification_live_recording)
            session != null -> text(R.string.notification_live)
            activeRecording != null -> text(R.string.recording_now)
            monitorOutput != null -> text(R.string.notification_preview_stream)
            relay -> text(R.string.notification_moblink_relay)
            else -> null
        }
        val service = Intent(context, StreamService::class.java)
        if (title != null) {
            service.putExtra(StreamService.EXTRA_USB, uvcCamera != null || relay)
                .putExtra(StreamService.EXTRA_MEDIA, busy)
                .putExtra(StreamService.EXTRA_LOCATION, Need.Location in widgetNeeds && location.allowed)
                .putExtra(StreamService.EXTRA_TITLE, title)
            if (serviceStarted) {
                runCatching { context.startForegroundService(service) }.onFailure { Log.w(TAG, "Service update failed", it) }
            } else {
                context.startForegroundService(service)
                serviceStarted = true
            }
        } else if (serviceStarted) {
            serviceStarted = false
            try {
                context.startService(service.setAction(StreamService.ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stopping the stream service directly", e)
                context.stopService(service)
            }
        }
    }

    @Synchronized
    fun goLive() {
        if (isLive || !hasLivePermissions()) return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        if (!busy && !begin(profile)) return
        targetBitrate = profile.bitrateKbps * 1000
        liveMaxBitrate = targetBitrate.toLong()
        transportMeter = TransportBitrateMeter(ADAPTIVE_BITRATE_START_BPS)
        val requestKeyFrame: () -> Unit = { videoEncoder?.requestKeyFrame() }
        val newSession = StreamSession(
            ioScope,
            { listener -> createPublisher(streamUrl(profile), profile.streamKey, profile.bonded, true, requestKeyFrame, listener) },
            requestKeyFrame,
        )
        session = newSession
        mainOutput = LiveOutput(profile.id, profile.name, newSession, profile.transport == Transport.WHIP)
        extraOutputs = profile.destinations.filter { it.enabled && it.url.isNotBlank() }.map { destination ->
            LiveOutput(
                destination.id,
                destination.name,
                StreamSession(
                    ioScope,
                    { listener -> createPublisher(destination.url.trim(), destination.streamKey, false, false, requestKeyFrame, listener) },
                    requestKeyFrame,
                ),
                transportOf(destination.url) == Transport.WHIP,
            )
        }
        if (profile.replay.enabled && replayBuffer == null) {
            replayBuffer = ReplayBuffer((REPLAY_SECONDS.max() + profile.replay.delaySeconds) * 1_000_000L, REPLAY_MAX_BYTES)
        }
        if (profile.bonded) links.start(profile.bondingNetworks)
        linkBytes = emptyMap()
        try {
            syncEncoders()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoders", e)
            stopLive()
            _ui.update { it.copy(error = e.message ?: text(R.string.error_encoder)) }
            return
        }
        videoEncoder?.setBitrate(targetBitrate)
        updateService()
        newSession.start()
        val extras = extraOutputs
        extras.forEach { it.session.start() }
        macros.onEvent(MacroEvent(MacroEventType.GO_LIVE))
        liveStartedAt = System.nanoTime()
        lowBitrate = LowBitrateWatch()
        _ui.update { it.copy(error = null) }
        liveJob = scope.launch {
            launch {
                var wasLive = false
                var previous = LiveState.CONNECTING
                newSession.state.collect { s ->
                    _ui.update { it.copy(live = s) }
                    if (s == LiveState.LIVE && previous != LiveState.LIVE) {
                        showAlert(AlertLevel.INFO, text(if (wasLive) R.string.alert_reconnected else R.string.alert_live))
                        wasLive = true
                    } else if (s == LiveState.RECONNECTING && previous == LiveState.LIVE) {
                        showAlert(AlertLevel.ERROR, text(R.string.alert_disconnected), newSession.lastError.value ?: text(R.string.reconnecting))
                    } else if (s == LiveState.RECONNECTING && previous == LiveState.CONNECTING) {
                        showAlert(AlertLevel.ERROR, text(R.string.alert_connect_failed), newSession.lastError.value ?: text(R.string.retrying))
                    }
                    previous = s
                }
            }
            launch { newSession.lastError.collect { e -> if (e != null) _ui.update { it.copy(error = e) } } }
            for (output in extras) launch { watchDestination(output) }
            if (profile.adaptiveBitrate) launch { runAdaptiveBitrate(newSession, profile) }
        }
        startTicker()
        if (profile.recording.startWithStream) startRecording()
        val wanted = settings.settings.value.selectedProfile.obs
        val remote = connectedObs(wanted)
        if (wanted.startStream && remote?.stream == ObsOutput.STOPPED) obs.startStream()
        if (wanted.startRecording && remote?.record == ObsOutput.STOPPED) obs.startRecord()
    }

    private fun connectedObs(wanted: ObsSettings): ObsState? =
        obs.state.value.takeIf { it.connected && wanted.enabled && obs.isFor(obsUrl(wanted.url), wanted.password) }

    @Synchronized
    fun syncWidgetSources() {
        val running = previewActive || busy
        widgetRenderer.active = running
        widgets.setActive(running)
        nowPlaying.setEnabled(running && settings.settings.value.widgets.any { it.type == WidgetType.MUSIC })
        location.setEnabled(running && Need.Location in widgetNeeds)
        syncFeed(true)
        syncFaces()
    }

    private fun faceCamera(): Need.FaceOf? = widgetNeeds.firstNotNullOfOrNull { it as? Need.FaceOf }

    private fun syncFaces() {
        val target = compositor
        val need = if (target != null && previewActive) faceCamera() else null
        val wanted = need?.let { target!! to it.camera.takeUnless { key -> key == _ui.value.activeCamera?.key || key == _ui.value.camera?.key } }
        if (wanted == faceTap) return
        faceTap?.first?.setFrameTap(null)
        faceTap = wanted
        if (wanted == null) {
            faceTracker.stop()
            return
        }
        val camera = need.camera
        lateinit var tap: FrameTap
        tap = FrameTap(wanted.second?.let { "camera:$it" }, 100_000_000L) { frame ->
            faceTracker.process(camera, frame) { tap.busy.set(false) }
        }
        wanted.first.setFrameTap(tap)
    }

    private fun syncObs() {
        val wanted = settings.settings.value.selectedProfile.obs
        val url = if (wanted.enabled && (previewActive || busy)) obsUrl(wanted.url) else null
        if (url == null) obs.stop() else obs.start(url, wanted.password)
    }

    @Synchronized
    fun stopLive() {
        val profile = liveProfile
        val wasLive = session != null
        liveJob?.cancel()
        liveJob = null
        session?.stop()
        session = null
        mainOutput = null
        extraOutputs.forEach { it.session.stop() }
        extraOutputs = emptyList()
        _destinations.value = emptyMap()
        links.stop()
        moblink.stopTunnels()
        _ui.update { it.copy(live = LiveState.OFFLINE, error = null) }
        _stats.value = LiveStats()
        if (wasLive) {
            macros.onEvent(MacroEvent(MacroEventType.END))
            val wanted = settings.settings.value.selectedProfile.obs
            val remote = connectedObs(wanted)
            if (wanted.stopStream && remote?.stream == ObsOutput.STARTED) obs.stopStream()
            if (wanted.stopRecording && remote?.record == ObsOutput.STARTED) obs.stopRecord()
        }
        if (wasLive && profile?.recording?.stopWithStream == true) stopRecording()
        if (profile != null) {
            targetBitrate = profile.bitrateKbps * 1000
            videoEncoder?.setBitrate(targetBitrate)
        }
        syncEncoders()
        end()
    }

    @Synchronized
    fun startRecording() {
        if (activeRecording != null || !hasLivePermissions()) return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        if (recordings.freeBytes() < MIN_FREE_BYTES) {
            showAlert(AlertLevel.WARNING, text(R.string.alert_storage_full), text(R.string.alert_recording_not_started))
            return
        }
        val file = try {
            recordings.create()
        } catch (e: Exception) {
            Log.e(TAG, "Could not create a recording", e)
            showAlert(AlertLevel.ERROR, text(R.string.alert_recording_start_failed), e.message)
            return
        }
        if (!busy && !begin(profile)) {
            file.close()
            recordings.delete(file.uri)
            return
        }
        lateinit var active: ActiveRecording
        val recorder = Mp4Recorder(file.input, file.output) { message ->
            scope.launch { synchronized(this@StreamEngine) { if (activeRecording === active) stopRecording(message) } }
        }
        active = ActiveRecording(file, recorder, profile.recording, System.nanoTime())
        activeRecording = active
        recorder.start()
        tee.setRecorder(recorder, video = !profile.recording.separate)
        try {
            syncEncoders()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start the recording encoders", e)
            stopRecording(e.message ?: text(R.string.error_encoder))
            return
        }
        videoEncoder?.requestKeyFrame()
        _recording.value = RecordingStats()
        startTicker()
        updateService()
        macros.onEvent(MacroEvent(MacroEventType.START_RECORDING))
        showAlert(AlertLevel.INFO, text(R.string.alert_recording_started))
    }

    @Synchronized
    fun stopRecording(error: String? = null) {
        val active = activeRecording ?: return
        activeRecording = null
        tee.setRecorder(null, video = false)
        _recording.value = null
        macros.onEvent(MacroEvent(MacroEventType.STOP_RECORDING))
        finishRecording(active, error)
        syncEncoders()
        end()
    }

    private fun finishRecording(active: ActiveRecording, error: String?) {
        ioScope.launch {
            val summary = try {
                active.recorder.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Recording not finished", e)
                active.file.close()
                showAlert(AlertLevel.ERROR, text(R.string.alert_recording_not_finished), text(R.string.alert_recording_recover_later))
                return@launch
            }
            active.file.close()
            if (summary == null) {
                recordings.delete(active.file.uri)
                showAlert(AlertLevel.ERROR, text(R.string.alert_recording_failed), error ?: text(R.string.nothing_recorded))
                return@launch
            }
            recordings.publish(active.file.uri)
            if (error == null) {
                showAlert(AlertLevel.INFO, text(R.string.alert_recording_saved), describe(summary))
            } else {
                showAlert(AlertLevel.ERROR, text(R.string.alert_recording_stopped), error)
            }
        }
    }

    @Synchronized
    fun instantReplay() = if (_replay.value == ReplayState.PLAYING) stopReplay() else startReplay(play = true)

    fun saveReplay() = startReplay(play = false)

    @Synchronized
    fun playReplay(uri: Uri, startMs: Long) {
        if (_replay.value != ReplayState.IDLE || !previewActive) return
        replayPlaying = SceneVideo(uri.toString(), "Replay")
        replayStartMs = startMs
        _replay.value = ReplayState.PLAYING
        openCamera()
    }

    @Synchronized
    private fun startReplay(play: Boolean) {
        val buffer = replayBuffer
        val profile = liveProfile
        if (_replay.value != ReplayState.IDLE) return
        if (buffer == null || profile == null) {
            showAlert(AlertLevel.WARNING, text(R.string.alert_replay_unavailable), text(R.string.alert_replay_unavailable_detail))
            return
        }
        _replay.value = ReplayState.PENDING
        if (profile.replay.delaySeconds > 0) {
            showAlert(AlertLevel.INFO, text(R.string.alert_replay_in, profile.replay.delaySeconds))
        }
        replayJob = ioScope.launch {
            delay(profile.replay.delaySeconds * 1000L)
            val clip = buffer.clip(profile.replay.seconds * 1_000_000L)
            val saved = clip?.let {
                runCatching { writeReplay(it) }.onFailure { Log.e(TAG, "Replay not saved", it) }.getOrNull()
            }
            synchronized(this@StreamEngine) {
                if (replayJob?.isActive != true) {
                    return@launch
                }
                if (saved == null) {
                    _replay.value = ReplayState.IDLE
                    showAlert(AlertLevel.ERROR, text(R.string.alert_replay_failed))
                } else if (play && previewActive) {
                    replayPlaying = SceneVideo(saved.toString(), "Replay")
                    replayStartMs = 0
                    _replay.value = ReplayState.PLAYING
                    openCamera()
                } else {
                    _replay.value = ReplayState.IDLE
                    showAlert(AlertLevel.INFO, text(R.string.alert_replay_saved))
                }
            }
        }
    }

    private fun writeReplay(clip: ReplayClip): Uri? {
        val file = recordings.create(replay = true)
        val summary = try {
            val recorder = Mp4Recorder(file.input, file.output, Int.MAX_VALUE) { Log.w(TAG, "Replay: $it") }
            recorder.start()
            clip.writeTo(recorder)
            recorder.stop()
        } catch (e: Exception) {
            file.close()
            recordings.delete(file.uri)
            throw e
        }
        file.close()
        if (summary == null) {
            recordings.delete(file.uri)
            return null
        }
        recordings.publish(file.uri)
        return file.uri
    }

    @Synchronized
    fun stopReplay() {
        replayJob?.cancel()
        replayJob = null
        _replay.value = ReplayState.IDLE
        if (replayPlaying == null) return
        replayPlaying = null
        if (previewActive) openCamera()
    }

    @Synchronized
    fun toggleMonitor() {
        if (monitorOutput != null) {
            stopMonitor()
            return
        }
        if (!hasLivePermissions()) return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val wanted = profile.previewStream
        if (transportOf(wanted.url) != Transport.WHIP || CodecCatalog.encoders[VideoCodec.H264] == null) {
            showAlert(AlertLevel.WARNING, text(R.string.alert_preview_stream_not_set))
            return
        }
        if (!busy && !begin(profile)) return
        val requestKeyFrame: () -> Unit = { monitorEncoder?.requestKeyFrame() }
        val session = StreamSession(
            ioScope,
            { listener -> createPublisher(wanted.url.trim(), wanted.token, false, false, requestKeyFrame, listener) },
            requestKeyFrame,
        )
        monitorOutput = LiveOutput("monitor", text(R.string.preview_stream), session, true)
        try {
            syncEncoders()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start the preview stream encoder", e)
            stopMonitor()
            _ui.update { it.copy(error = e.message ?: text(R.string.error_encoder)) }
            return
        }
        _monitoring.value = true
        updateService()
        session.start()
        monitorJob = scope.launch {
            var previous = LiveState.CONNECTING
            session.state.collect { state ->
                if (state == LiveState.RECONNECTING && previous != LiveState.RECONNECTING) {
                    showAlert(
                        AlertLevel.ERROR,
                        text(R.string.alert_destination_failed, text(R.string.preview_stream)),
                        session.lastError.value ?: text(R.string.retrying),
                    )
                }
                previous = state
            }
        }
    }

    private fun clearMonitor(): Boolean {
        val output = monitorOutput ?: return false
        monitorJob?.cancel()
        monitorJob = null
        output.session.stop()
        monitorOutput = null
        _monitoring.value = false
        return true
    }

    @Synchronized
    private fun stopMonitor() {
        if (!clearMonitor()) return
        runCatching { syncEncoders() }
        end()
    }

    fun takeSnapshot() {
        val compositor = synchronized(this) { compositor } ?: return
        compositor.takeSnapshot { bitmap ->
            ioScope.launch {
                if (bitmap != null) SnapshotWidget.add(widgets, bitmap)
                val saved = bitmap != null && recordings.saveSnapshot(bitmap)
                bitmap?.recycle()
                if (saved) {
                    showAlert(AlertLevel.INFO, text(R.string.alert_snapshot_saved))
                } else {
                    showAlert(AlertLevel.ERROR, text(R.string.alert_snapshot_failed))
                }
            }
        }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    @Volatile
    private var liveMaxBitrate = 0L
    private var previewEnabled = true

    fun setMaxBitrate(kbps: Int) {
        val profile = settings.settings.value.selectedProfile
        settings.updateProfile(profile.copy(bitrateKbps = kbps))
        synchronized(this) {
            val live = liveProfile ?: return
            liveMaxBitrate = kbps * 1000L
            if (session == null || !live.adaptiveBitrate) {
                targetBitrate = kbps * 1000
                videoEncoder?.setBitrate(targetBitrate)
            }
        }
    }

    @Synchronized
    fun setPreviewEnabled(enabled: Boolean) {
        previewEnabled = enabled
        compositor?.setPreview(if (enabled) previewSurface else null)
    }

    private fun createPublisher(
        url: String,
        key: String,
        bonded: Boolean,
        moblinked: Boolean,
        requestKeyFrame: () -> Unit,
        listener: PublisherListener,
    ): Publisher = when (transportOf(url)) {
        Transport.RTMP -> RtmpPublisher(url, key.trim(), listener)
        Transport.SRTLA -> if (moblinked) SrtPublisher(url, listener, streamLinks, onServer = moblink::startTunnels) else SrtPublisher(url, listener, links)
        Transport.SRT -> SrtPublisher(url, listener)
        Transport.RIST -> RistPublisher(url, listener, links.takeIf { bonded })
        Transport.WHIP -> WhipPublisher(
            url,
            if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${key.trim()}"),
            listener,
            http,
            requestKeyFrame,
        )
        else -> throw IllegalArgumentException("Unsupported URL: $url")
    }

    private suspend fun watchDestination(output: LiveOutput) {
        var previous = LiveState.CONNECTING
        output.session.state.collect { state ->
            _destinations.update { it + (output.id to state) }
            if (state == LiveState.LIVE && previous != LiveState.LIVE) {
                showAlert(AlertLevel.INFO, text(R.string.alert_live_at, output.name))
            } else if (state == LiveState.RECONNECTING && previous != LiveState.RECONNECTING) {
                showAlert(
                    AlertLevel.ERROR,
                    text(R.string.alert_destination_failed, output.name),
                    output.session.lastError.value ?: text(R.string.retrying),
                )
            }
            previous = state
        }
    }

    private fun syncSinks() {
        val outputs = listOfNotNull(mainOutput) + extraOutputs
        tee.setStreams(outputs.map { it.sink } + listOfNotNull(replayBuffer))
        opusTee.setStreams((outputs + listOfNotNull(monitorOutput)).mapNotNull { it.opusSink })
    }

    private fun syncEncoders() {
        syncSinks()
        val profile = liveProfile ?: return
        val (w, h) = outputSize(profile)
        val recording = activeRecording
        if (busy && !audioLive) {
            audioEncoder?.release()
            audioEncoder = null
            audioEncoder = AudioEncoder(
                profile.audioBitrateKbps * 1000,
                tee,
                openMic(),
                audioMix,
                { _audioLevel.value = it },
                ::onFatal,
            ).also { it.muted = _ui.value.muted }
            audioLive = true
        } else if (!busy && audioLive) {
            audioEncoder?.release()
            audioEncoder = null
            audioLive = false
            tee.forgetAudioFormat()
            opusTee.forgetAudioFormat()
        }
        if (audioLive) audioEncoder?.opusSink = opusTee.takeIf { mainOutput?.opusSink != null || monitorOutput != null || extraOutputs.any { it.opusSink != null } }
        if (session == null && recording?.settings?.separate != false) {
            videoEncoder?.let {
                compositor?.setEncoder(null)
                it.release()
                videoEncoder = null
                tee.forgetVideoFormat()
            }
        } else if (videoEncoder == null) {
            val encoder = VideoEncoder(
                codec = liveCodec!!,
                width = w,
                height = h,
                fps = encoderFps(profile),
                bitrateBps = targetBitrate,
                keyFrameIntervalSeconds = profile.keyFrameIntervalSeconds,
                sink = tee,
                onError = ::onEncoderError,
            )
            videoEncoder = encoder
            compositor!!.setEncoder(encoder.inputSurface)
        }
        if (recording?.settings?.separate != true) {
            recordEncoder?.let {
                compositor?.setRecorder(null)
                it.release()
                recordEncoder = null
            }
        } else if (recordEncoder == null) {
            val encoder = VideoEncoder(
                codec = CodecCatalog.choose(recording.settings.codec, RECORDING_CODECS, w, h, profile.fps),
                width = w,
                height = h,
                fps = encoderFps(profile),
                bitrateBps = recording.settings.bitrateKbps * 1000,
                keyFrameIntervalSeconds = profile.keyFrameIntervalSeconds,
                sink = VideoOnlySink(recording.recorder),
                onError = ::onEncoderError,
            )
            recordEncoder = encoder
            compositor!!.setRecorder(encoder.inputSurface)
        }
        val monitor = monitorOutput
        if (monitor == null) {
            monitorEncoder?.let {
                compositor?.setMonitor(null)
                it.release()
                monitorEncoder = null
            }
        } else if (monitorEncoder == null) {
            val settings = profile.previewStream
            val encoder = VideoEncoder(
                codec = VideoCodec.H264,
                width = if (profile.portrait) settings.resolution.height else settings.resolution.width,
                height = if (profile.portrait) settings.resolution.width else settings.resolution.height,
                fps = encoderFps(profile),
                bitrateBps = settings.bitrateKbps * 1000,
                keyFrameIntervalSeconds = 2,
                sink = monitor.sink,
                onError = ::onEncoderError,
            )
            monitorEncoder = encoder
            compositor!!.setMonitor(encoder.inputSurface)
        }
    }

    @Synchronized
    private fun restartVideoEncoders(reason: String) {
        if (!busy) return
        Log.w(TAG, "Restarting video encoders: $reason")
        compositor?.setEncoder(null)
        compositor?.setRecorder(null)
        compositor?.setMonitor(null)
        val released = (videoEncoder?.release(timeoutMs = 5000) ?: true) and (recordEncoder?.release(timeoutMs = 5000) ?: true) and
            (monitorEncoder?.release(timeoutMs = 5000) ?: true)
        videoEncoder = null
        recordEncoder = null
        monitorEncoder = null
        if (!released) {
            fail(text(R.string.error_encoder_stalled))
            return
        }
        runCatching { syncEncoders() }.onFailure { fail(text(R.string.error_video_encoder, it.message.orEmpty())) }
    }

    private fun onEncoderError(message: String) {
        scope.launch {
            delay(200)
            restartVideoEncoders(message)
        }
    }

    @Synchronized
    private fun fail(message: String) {
        val wasLive = session != null
        val active = activeRecording
        activeRecording = null
        tee.setRecorder(null, video = false)
        _recording.value = null
        active?.let { finishRecording(it, message) }
        clearMonitor()
        stopLive()
        if (wasLive) showAlert(AlertLevel.ERROR, text(R.string.alert_stream_stopped), message)
        _ui.update { it.copy(error = message) }
    }

    private fun onFatal(message: String) {
        scope.launch { fail(message) }
    }

    private fun tick() {
        val now = System.nanoTime()
        val stalled = { encoder: VideoEncoder? -> encoder != null && now - encoder.lastOutputNanos > 5_000_000_000L }
        if (_ui.value.cameraState == CameraState.Streaming && (stalled(videoEncoder) || stalled(recordEncoder) || stalled(monitorEncoder))) {
            restartVideoEncoders("no output for 5 s")
        }
        session?.let(::tickLive)
        synchronized(this) {
            val active = activeRecording ?: return
            _recording.value = RecordingStats((now - active.startedAt) / 1_000_000_000L, active.recorder.bytesWritten)
            if (recordings.freeBytes() < MIN_FREE_BYTES) stopRecording(text(R.string.alert_storage_full))
        }
    }

    private fun tickLive(session: StreamSession) {
        val stats = session.stats()
        if (session.state.value == LiveState.LIVE) {
            transportMeter.update(stats.bytesSent, System.nanoTime())
        } else {
            transportMeter.pause()
        }
        if (session.state.value == LiveState.LIVE && System.nanoTime() - liveStartedAt > 15_000_000_000L &&
            lowBitrate.update(stats.sendBitrateBps)
        ) {
            showAlert(AlertLevel.WARNING, text(R.string.alert_low_bitrate), formatKbps(stats.sendBitrateBps))
        }
        val linkStats = if (liveProfile?.bonded == true) (session.publisher as? BondedPublisher)?.linkUsage().orEmpty() else emptyList()
        val shares = linkShares(linkBytes, linkStats)
        linkBytes = linkStats.associate { it.id to it.bytesSent }
        val live = LiveStats(
            bitrateBps = stats.sendBitrateBps,
            targetBitrateBps = targetBitrate,
            fps = compositor?.measuredFps ?: 0f,
            rttMs = stats.rttMs,
            queuedBytes = stats.queuedBytes,
            droppedFrames = stats.droppedVideoFrames,
            uptimeSeconds = (System.nanoTime() - liveStartedAt) / 1_000_000_000L,
            bytesSent = stats.bytesSent,
            codec = liveCodec,
            links = shares,
            totalBytes = stats.bytesSent,
        )
        _stats.value = live
        if (live.uptimeSeconds % 10 == 0L) {
            Log.i(
                TAG,
                "live state=${session.state.value} uptime=${live.uptimeSeconds} fps=${live.fps.toInt()} " +
                    "sent=${live.bitrateBps / 1000} target=${live.targetBitrateBps / 1000} rtt=${live.rttMs.toInt()} " +
                    "queued=${live.queuedBytes} dropped=${live.droppedFrames} thermal=${_ui.value.thermal} " +
                    "temp=${_ui.value.temperature}",
            )
        }
    }

    @Volatile
    private var transportMeter = TransportBitrateMeter(ADAPTIVE_BITRATE_START_BPS)
    private var serviceStarted = false

    private suspend fun runAdaptiveBitrate(session: StreamSession, profile: StreamProfile) {
        var max = 0L
        var abr: AdaptiveBitrate? = null
        var owner: Any? = null
        while (true) {
            delay(ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS)
            val publisher = session.publisher
            if (publisher == null || session.state.value != LiveState.LIVE) continue
            val cap = liveMaxBitrate
            val min = minOf(profile.minBitrateKbps * 1000L, cap)
            if (owner !== publisher) {
                owner = publisher
                abr = when (publisher) {
                    is SrtPublisher -> BelaboxAdaptiveBitrate(cap, min)
                    is RistPublisher -> RistAdaptiveBitrate(cap, min)
                    is WhipPublisher -> LossAdaptiveBitrate(cap, min)
                    else -> QueueAdaptiveBitrate(cap, min)
                }
                max = cap
            } else if (cap != max) {
                max = cap
                abr!!.setMinimumBitrate(min)
                abr.setTargetBitrate(cap)
            }
            val stats = publisher.stats().toStreamStats(
                latencyMs = (publisher as? SrtPublisher)?.latencyMs,
                transportBitrate = transportMeter.bitrate,
            )
            val next = abr!!.update(stats).toInt()
            if (next != targetBitrate) {
                targetBitrate = next
                videoEncoder?.setBitrate(next)
            }
        }
    }

    private fun ensureCompositor() {
        if (compositor != null) return
        val profile = settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        compositor = VideoCompositor(w, h).also {
            it.setFrameRate(thermalGuard.fps(profile.fps))
            it.setPreview(if (previewEnabled) previewSurface else null)
            it.setEffects(effectSettings)
        }
        drawBoard.setSize(w, h)
        applyOverlays()
        syncBrowsers()
        syncVTubers()
    }

    private fun syncBrowsers() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::syncBrowsers)
            return
        }
        val target: VideoCompositor?
        val pages: Map<String, BrowserPage>
        synchronized(this) {
            target = compositor
            pages = overlays.mapNotNull { overlay -> (overlay.source as? BrowserPage)?.let { overlay.surface to it } }.toMap()
        }
        browsers.sync(pages, target)
    }

    private fun syncVTubers() {
        vtubers.sync(overlays.mapNotNull { overlay -> (overlay.source as? VTuberSource)?.let { overlay.surface to it } }.toMap(), compositor)
    }

    private fun videoKey(scene: Scene) = if (scene.playsVideo) scene.id to scene.videos else null

    private fun closeSources() {
        cameraGeneration++
        _videoPlayer.value?.let { player ->
            _videoPlayer.value = null
            audioMix.source = null
            if (!player.release()) {
                val generation = cameraGeneration
                ioScope.launch {
                    player.awaitStopped()
                    synchronized(this@StreamEngine) {
                        if (generation == cameraGeneration && previewActive) {
                            playingVideo = null
                            openCamera()
                        }
                    }
                }
            }
        }
        videoInput = null
        playingVideo = null
        camera.close()
        uvcCamera?.let { uvc ->
            uvcCamera = null
            uvc.onError = null
            uvc.close()
            _ui.update { it.copy(cameraState = CameraState.Closed) }
        }
        uvcSurface?.release()
        uvcSurface = null
    }

    private fun openReplay(clip: SceneVideo, compositor: VideoCompositor) {
        openPlayer(listOf(clip), compositor, 1f, true, false, settings.settings.value.replaySpeedPercent / 100f, replayStartMs) {
            scope.launch { synchronized(this@StreamEngine) { if (replayPlaying === clip) stopReplay() } }
        }
    }

    private fun openVideo(scene: Scene, compositor: VideoCompositor) =
        openPlayer(scene.videos, compositor, scene.videoVolume / 100f, scene.micWithVideo, true, 1f, 0) {}

    private fun openPlayer(
        videos: List<SceneVideo>,
        compositor: VideoCompositor,
        volume: Float,
        micWhilePlaying: Boolean,
        loop: Boolean,
        speed: Float,
        startMs: Long,
        onFinished: () -> Unit,
    ) {
        val generation = cameraGeneration
        cameraFps = 0
        compositor.setFrameRate(thermalGuard.fps((liveProfile ?: settings.settings.value.selectedProfile).fps))
        val player = VideoPlayer(
            context,
            videos.map { VideoFile(Uri.parse(it.uri), it.name) },
            compositor.newInputSurface(),
            { width, height, rotation ->
                scope.launch {
                    synchronized(this@StreamEngine) {
                        if (generation == cameraGeneration) {
                            videoInput = Triple(width, height, rotation)
                            applyInputTransform()
                        }
                    }
                }
            },
            compositor::repeatFrame,
            loop,
            speed,
            startMs,
            onFinished,
        ) { message -> _ui.update { it.copy(error = message) } }
        player.volume = volume
        player.micWhilePlaying = micWhilePlaying
        audioMix.source = player
        _videoPlayer.value = player
    }

    private fun openCamera() {
        syncFeed(false)
        openSource()
        syncFeed(true)
        syncFaces()
    }

    private fun feedCamera(): Pair<String, CameraDescriptor>? {
        val wanted = overlays.firstNotNullOfOrNull { it.source as? CameraFeed }?.camera ?: faceCamera()?.camera ?: return null
        val cameras = _ui.value.cameras
        val descriptor = if (wanted == USB_CAMERA) {
            cameras.firstOrNull { it.facing == Facing.EXTERNAL }
        } else {
            cameras.firstOrNull { it.key == wanted }
        } ?: return null
        val main = _ui.value.activeCamera
        if (main != null && settings.settings.value.selectedScene?.playsVideo != true) {
            if (main.id == descriptor.id) return null
            val both = !main.id.startsWith(UVC_PREFIX) && !descriptor.id.startsWith(UVC_PREFIX)
            if (both && !camerasRunTogether(main.id, descriptor.id)) return null
        }
        return "camera:$wanted" to descriptor
    }

    private fun camerasRunTogether(first: String, second: String): Boolean = Build.VERSION.SDK_INT >= 30 && runCatching {
        context.getSystemService(CameraManager::class.java).concurrentCameraIds.any { first in it && second in it }
    }.getOrDefault(false)

    private fun syncFeed(open: Boolean) {
        val target = compositor
        val wanted = if (target != null && previewActive) feedCamera() else null
        val current = feed
        if (current != null && (wanted == null || current.key != wanted.first || current.camera.key != wanted.second.key || current.compositor !== target)) {
            closeFeed()
        }
        if (open && feed == null && wanted != null && target != null) openFeed(wanted.first, wanted.second, target)
    }

    private fun closeFeed() {
        val current = feed ?: return
        feed = null
        feedCamera.close()
        current.uvc?.let { uvc ->
            uvc.onError = null
            uvc.close()
        }
        current.compositor.closeSurfaceOverlay(current.key)
    }

    private fun feedInput(current: Feed) = FeedInput(
        current.width,
        current.height,
        current.camera.sensorOrientation,
        if (current.camera.facing == Facing.EXTERNAL) usbRotation() else displayRotation,
        current.camera.facing == Facing.FRONT,
    )

    private fun openFeed(key: String, descriptor: CameraDescriptor, compositor: VideoCompositor) {
        val current = Feed(key, descriptor, compositor)
        feed = current
        if (!descriptor.id.startsWith(UVC_PREFIX)) {
            val size = descriptor.chooseSize(1280, 720, 30) ?: descriptor.sizes.firstOrNull()
            val surface = size?.let { compositor.openSurfaceOverlay(key, it.width, it.height) }
            if (size == null || surface == null) {
                feed = null
                return
            }
            current.width = size.width
            current.height = size.height
            compositor.setFeedInput(key, feedInput(current))
            feedCamera.open(descriptor, surface, size, minOf(30, descriptor.maxFps(size)), Stabilization.OFF)
            return
        }
        val device = uvcDevices.list().firstOrNull { UVC_PREFIX + it.deviceName == descriptor.id }
        if (device == null) {
            feed = null
            return
        }
        uvcDevices.requestPermission(device) { granted ->
            if (!granted) return@requestPermission
            ioScope.launch {
                val uvc = try {
                    UvcCamera.open(context, device)
                } catch (e: Exception) {
                    Log.e(TAG, "USB camera feed open failed", e)
                    return@launch
                }
                val format = uvc.formats.closest(1280, 720, 30) ?: uvc.formats.closest(1920, 1080, 30)
                    ?: uvc.formats.minByOrNull { abs(it.width * it.height - 1280 * 720) }
                val surface = synchronized(this@StreamEngine) {
                    val surface = if (feed === current && format != null) compositor.openSurfaceOverlay(key, format.width, format.height) else null
                    if (surface == null || format == null) {
                        uvc.close()
                        return@launch
                    }
                    current.uvc = uvc
                    current.width = format.width
                    current.height = format.height
                    compositor.setFeedInput(key, feedInput(current))
                    uvc.onError = { message ->
                        Log.w(TAG, "USB camera feed: $message")
                        synchronized(this@StreamEngine) { if (feed === current) closeFeed() }
                    }
                    surface
                }
                try {
                    uvc.start(format!!, surface)
                } catch (e: Exception) {
                    Log.e(TAG, "USB camera feed start failed", e)
                    synchronized(this@StreamEngine) { if (feed === current) closeFeed() }
                }
            }
        }
    }

    private fun openSource() {
        val compositor = compositor ?: return
        val replay = replayPlaying
        if (replay != null) {
            val key = REPLAY_KEY to listOf(replay)
            if (key == playingVideo) return
            closeSources()
            openReplay(replay, compositor)
            playingVideo = key
            return
        }
        val scene = settings.settings.value.selectedScene
        val video = scene?.let(::videoKey)
        if (scene != null && video != null) {
            if (video == playingVideo) return
            closeSources()
            openVideo(scene, compositor)
            playingVideo = video
            return
        }
        val descriptor = _ui.value.activeCamera ?: return
        closeSources()
        if (descriptor.id.startsWith(UVC_PREFIX)) {
            openUvc(descriptor, compositor)
            return
        }
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val wanted = thermalGuard.fps(profile.fps)
        val size = descriptor.chooseSize(long, short, wanted) ?: descriptor.sizes.firstOrNull() ?: return
        val fps = minOf(wanted, descriptor.maxFps(size))
        cameraFps = fps
        compositor.setFrameRate(encoderFps(profile))
        currentInputSize = size
        applyInputTransform()
        camera.open(descriptor, compositor.inputSurface, size, fps, stabilization())
    }

    private var currentInputSize = Size(1920, 1080)
    private var cameraFps = 0

    private fun encoderFps(profile: StreamProfile): Int {
        val wanted = thermalGuard.fps(profile.fps)
        return if (cameraFps > 0) minOf(wanted, cameraFps) else wanted
    }

    private fun openUvc(descriptor: CameraDescriptor, compositor: VideoCompositor) {
        val generation = cameraGeneration
        val device = uvcDevices.list().firstOrNull { UVC_PREFIX + it.deviceName == descriptor.id } ?: return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        uvcDevices.requestPermission(device) { granted ->
            if (!granted) {
                _ui.update { it.copy(error = text(R.string.error_usb_denied)) }
                return@requestPermission
            }
            ioScope.launch {
                val uvc = try {
                    UvcCamera.open(context, device)
                } catch (e: Exception) {
                    Log.e(TAG, "USB camera open failed", e)
                    onUvcError(generation, e.message ?: "open failed")
                    return@launch
                }
                val format = uvc.formats.closest(maxOf(w, h), minOf(w, h), thermalGuard.fps(profile.fps))
                val surface = synchronized(this@StreamEngine) {
                    if (generation != cameraGeneration || format == null) {
                        uvc.close()
                        if (format == null) _ui.update { it.copy(error = text(R.string.error_usb_format)) }
                        return@launch
                    }
                    uvcCamera = uvc
                    cameraFps = format.fps
                    compositor.setFrameRate(encoderFps(profile))
                    currentInputSize = Size(format.width, format.height)
                    applyInputTransform()
                    uvc.onError = { message -> onUvcError(generation, message) }
                    compositor.newInputSurface().also { uvcSurface = it }
                }
                try {
                    uvc.start(format!!, surface)
                } catch (e: Exception) {
                    Log.e(TAG, "USB camera start failed", e)
                    onUvcError(generation, e.message ?: "start failed")
                    return@launch
                }
                synchronized(this@StreamEngine) {
                    if (generation != cameraGeneration) return@launch
                    uvcFailures = 0
                    _ui.update { it.copy(cameraState = CameraState.Streaming) }
                }
            }
        }
    }

    private fun onUvcError(generation: Int, message: String) {
        scope.launch {
            val key: String?
            val closedGeneration: Int
            synchronized(this@StreamEngine) {
                if (generation != cameraGeneration) return@launch
                _ui.update { it.copy(error = text(R.string.error_usb, message)) }
                closeSources()
                key = _ui.value.camera?.key
                closedGeneration = cameraGeneration
                uvcFailures++
            }
            delay(minOf(1000L shl minOf(uvcFailures - 1, 3), 5000L))
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                if (!previewActive || closedGeneration != cameraGeneration || _ui.value.camera?.key != key) return@launch
                if (cameras.any { it.key == key }) openCamera() else onUsbChanged(null)
            }
        }
    }

    private fun applyInputTransform() {
        feed?.let { current -> if (current.width > 0) current.compositor.setFeedInput(current.key, feedInput(current)) }
        if (_videoPlayer.value != null) {
            videoInput?.let { (width, height, rotation) -> compositor?.setInput(width, height, rotation, 0, false, false, false) }
            return
        }
        val descriptor = _ui.value.activeCamera ?: return
        val size = currentInputSize
        compositor?.setInput(
            size.width,
            size.height,
            descriptor.sensorOrientation,
            if (descriptor.facing == Facing.EXTERNAL) usbRotation() else displayRotation,
            descriptor.facing == Facing.FRONT,
            settings.settings.value.mirrorFrontCameraOnStream,
            boottimeTimestamps = uvcCamera == null && descriptor.realtimeTimestamps,
        )
    }

    private fun usbRotation() = (360 - settings.settings.value.usbCameraRotation) % 360

    private fun stabilization(): Stabilization {
        val modes = _ui.value.activeCamera?.stabilizationModes.orEmpty()
        val wanted = when (settings.settings.value.stabilization) {
            StabilizationSetting.OFF -> Stabilization.OFF
            StabilizationSetting.STANDARD -> Stabilization.VIDEO
            StabilizationSetting.PREVIEW -> Stabilization.PREVIEW
        }
        return if (wanted in modes) wanted else Stabilization.OFF
    }

    private companion object {
        const val TAG = "StreamEngine"
        const val UVC_PREFIX = "uvc:"
        const val ADAPTIVE_BITRATE_START_BPS = 1_000_000L
        const val MIN_FREE_BYTES = 300L shl 20
        const val REPLAY_MAX_BYTES = 96L shl 20
        const val REPLAY_KEY = "replay"
        val LIVE_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

val CameraDescriptor.key: String get() = "$id:${physicalId.orEmpty()}"

private fun formatKbps(bps: Long) = "${bps / 1000} kbps"

private fun describe(summary: Mp4Summary): String {
    val seconds = summary.durationMs / 1000
    return "%d:%02d:%02d, %s".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60, formatBytes(summary.bytes))
}

fun formatBytes(bytes: Long): String =
    if (bytes >= 1 shl 30) "%.2f GB".format(Locale.ROOT, bytes / 1073741824.0) else "${bytes shr 20} MB"

fun outputSize(profile: StreamProfile): Pair<Int, Int> = if (profile.portrait) {
    profile.resolution.height to profile.resolution.width
} else {
    profile.resolution.width to profile.resolution.height
}
