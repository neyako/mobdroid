package com.opirl.app.stream

import java.util.Locale
import com.opirl.app.R
import androidx.annotation.StringRes
import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import com.opirl.app.engine.abr.QueueAdaptiveBitrate
import com.opirl.app.engine.abr.TransportBitrateMeter
import com.opirl.app.engine.abr.toStreamStats
import com.opirl.app.engine.rtmp.RtmpPublisher
import com.opirl.app.engine.srt.SrtPublisher
import com.opirl.app.settings.allowedCodecs
import com.opirl.app.engine.media.VideoCodec
import com.opirl.app.engine.mp4.Mp4Recorder
import com.opirl.app.engine.mp4.Mp4Summary
import com.opirl.app.engine.net.Publisher
import com.opirl.app.engine.net.PublisherListener
import com.opirl.app.media.AUDIO_LEVEL_FLOOR_DB
import com.opirl.app.media.AudioEncoder
import com.opirl.app.media.AudioMix
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
import com.opirl.app.settings.MicSource
import com.opirl.app.settings.Scene
import com.opirl.app.settings.SceneVideo
import com.opirl.app.settings.ObsSettings
import com.opirl.app.settings.RECORDING_CODECS
import com.opirl.app.settings.RecordingSettings
import com.opirl.app.recording.RecordingFile
import com.opirl.app.recording.RecordingStore
import java.io.File
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.streamUrl
import com.opirl.app.settings.StabilizationSetting
import com.opirl.app.settings.StreamProfile
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.NowPlaying
import com.opirl.app.uvc.UvcCamera
import com.opirl.app.uvc.UvcDevices
import com.opirl.app.uvc.closest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
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
    val codec: VideoCodec? = null,
    val links: List<LinkShare> = emptyList(),
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
) {
    val activeCamera: CameraDescriptor? get() = lens?.camera ?: camera
}

data class RecordingStats(val seconds: Long = 0, val bytes: Long = 0)

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

    internal fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)
    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var alertJob: Job? = null
    private var alertId = 0L
    private var lowBitrate = LowBitrateWatch()
    private val _audioLevel = MutableStateFlow(AUDIO_LEVEL_FLOOR_DB)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var linkBytes = emptyMap<String, Long>()

    private var compositor: VideoCompositor? = null
    private var overlays = emptyList<Overlay>()
    private var widgetRenderer: WidgetRenderer? = null
    private val browsers = BrowserWidgets(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewSurface: Surface? = null
    private var displayRotation = 0
    private var videoEncoder: VideoEncoder? = null
    private var recordEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var audioLive = false
    private val tee = MediaTee()
    private var session: StreamSession? = null
    private var activeRecording: ActiveRecording? = null
    private var liveProfile: StreamProfile? = null
    private var liveCodec: VideoCodec? = null
    private var liveJob: Job? = null
    private var ticker: Job? = null
    val recordings = RecordingStore(context)
    private val audioMix = AudioMix()
    val nowPlaying = NowPlaying(context)
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
                        openCamera()
                    }
                }
        }
        scope.launch {
            settings.settings.map { it.scenes.isEmpty() }.distinctUntilChanged().drop(1).collect { empty ->
                if (empty) ensureScenes(_ui.value.cameras)
            }
        }
        scope.launch {
            var playing = settings.settings.value.selectedScene?.let(::videoKey)
            settings.settings
                .map { it.selectedScene?.cameraKey to it.selectedScene?.let(::videoKey) }
                .distinctUntilChanged()
                .drop(1)
                .collect { (_, video) ->
                    synchronized(this@StreamEngine) {
                        val next = sceneCamera(_ui.value.cameras)
                        val changed = next != null && next.key != _ui.value.camera?.key
                        if (changed) _ui.update { it.copy(camera = next, lens = defaultLens(next!!), zoom = 1f, torch = false) }
                        if (previewActive && (changed || video != playing)) openCamera()
                        playing = video
                    }
                }
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
                synchronized(this@StreamEngine) { syncNowPlaying() }
            }
        }
        scope.launch {
            logins.logins.map { it.twitch to it.kick }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { if (previewActive || isLive) startChat() }
            }
        }
        scope.launch {
            settings.settings.map { it.mic }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { if (!busy && previewActive) startAudioMeter() }
            }
        }
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
                    _ui.update { it.copy(battery = battery, temperature = temperature) }
                    val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
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
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                val current = _ui.value.camera
                val next = cameras.firstOrNull { it.id == attachedId } ?: sceneCamera(cameras)
                _ui.update { it.copy(cameras = cameras) }
                if (next != null && next.key != current?.key) {
                    _ui.update { it.copy(camera = next, lens = defaultLens(next), zoom = 1f, torch = false) }
                    if (previewActive) openCamera()
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
        { it.facing == Facing.EXTERNAL },
    ) ?: defaultCamera(cameras)

    fun selectScene(id: String) = settings.update { it.copy(selectedSceneId = id) }

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

    private val busy: Boolean get() = session != null || activeRecording != null

    fun startPreview() {
        synchronized(this) {
            if (previewActive) return
            previewActive = true
        }
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                if (!previewActive) return@launch
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
                syncNowPlaying()
            }
        }
    }

    private fun micDevice(): AudioDeviceInfo? {
        val types = when (settings.settings.value.mic) {
            MicSource.AUTO -> return null
            MicSource.BUILT_IN -> listOf(AudioDeviceInfo.TYPE_BUILTIN_MIC)
            MicSource.USB -> listOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET)
            MicSource.WIRED -> listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)
        }
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in types }
    }

    private fun startAudioMeter() {
        audioEncoder?.release()
        audioEncoder = null
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        audioEncoder = runCatching {
            AudioEncoder(0, null, micDevice(), audioMix, { _audioLevel.value = it }) { Log.w(TAG, "Audio meter: $it") }
        }.onFailure { Log.w(TAG, "Audio meter failed", it) }.getOrNull()?.also { it.muted = _ui.value.muted }
    }

    @Volatile
    private var cachedCameras: List<CameraDescriptor>? = null

    @Synchronized
    fun stopPreviewIfIdle() {
        if (!previewActive || busy) return
        previewActive = false
        stopChat()
        obs.stop()
        nowPlaying.setEnabled(false)
        audioEncoder?.release()
        audioEncoder = null
        closeSources()
        val closing = compositor
        compositor = null
        syncBrowsers()
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
        compositor!!.setFrameRate(encoderFps(profile))
        return true
    }

    private fun end() {
        updateService()
        if (busy) return
        ticker?.cancel()
        ticker = null
        liveProfile = null
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
        val title = when {
            session != null && activeRecording != null -> text(R.string.notification_live_recording)
            session != null -> text(R.string.notification_live)
            activeRecording != null -> text(R.string.recording_now)
            else -> null
        }
        val service = Intent(context, StreamService::class.java)
        if (title != null) {
            service.putExtra(StreamService.EXTRA_USB, uvcCamera != null).putExtra(StreamService.EXTRA_TITLE, title)
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
        val newSession = StreamSession(ioScope, { listener -> createPublisher(profile, listener) }) {
            videoEncoder?.requestKeyFrame()
        }
        session = newSession
        tee.setStream(newSession)
        if (profile.url.startsWith("srtla://")) links.start(profile.bondingNetworks)
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

    private fun syncNowPlaying() {
        nowPlaying.setEnabled((previewActive || busy) && settings.settings.value.widgets.any { it.type == WidgetType.MUSIC })
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
        tee.setStream(null)
        links.stop()
        _ui.update { it.copy(live = LiveState.OFFLINE, error = null) }
        _stats.value = LiveStats()
        if (wasLive) {
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
        showAlert(AlertLevel.INFO, text(R.string.alert_recording_started))
    }

    @Synchronized
    fun stopRecording(error: String? = null) {
        val active = activeRecording ?: return
        activeRecording = null
        tee.setRecorder(null, video = false)
        _recording.value = null
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

    fun takeSnapshot() {
        val compositor = synchronized(this) { compositor } ?: return
        compositor.takeSnapshot { bitmap ->
            ioScope.launch {
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

    private fun createPublisher(profile: StreamProfile, listener: PublisherListener): Publisher {
        val url = streamUrl(profile)
        return when {
            url.startsWith("rtmp://") || url.startsWith("rtmps://") -> RtmpPublisher(url, profile.streamKey.trim(), listener)
            url.startsWith("srtla://") -> SrtPublisher(url, listener, links)
            url.startsWith("srt://") -> SrtPublisher(url, listener)
            else -> throw IllegalArgumentException("Unsupported URL: $url")
        }
    }

    private fun syncEncoders() {
        val profile = liveProfile ?: return
        val (w, h) = outputSize(profile)
        val recording = activeRecording
        if (busy && !audioLive) {
            audioEncoder?.release()
            audioEncoder = null
            audioEncoder = AudioEncoder(
                profile.audioBitrateKbps * 1000,
                tee,
                micDevice(),
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
        }
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
    }

    @Synchronized
    private fun restartVideoEncoders(reason: String) {
        if (!busy) return
        Log.w(TAG, "Restarting video encoders: $reason")
        compositor?.setEncoder(null)
        compositor?.setRecorder(null)
        val released = (videoEncoder?.release(timeoutMs = 5000) ?: true) and (recordEncoder?.release(timeoutMs = 5000) ?: true)
        videoEncoder = null
        recordEncoder = null
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
        if (_ui.value.cameraState == CameraState.Streaming && (stalled(videoEncoder) || stalled(recordEncoder))) {
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
        val linkStats = (session.publisher as? SrtPublisher)?.linkStats().orEmpty()
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
            codec = liveCodec,
            links = shares,
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
                abr = if (publisher is SrtPublisher) {
                    BelaboxAdaptiveBitrate(cap, min)
                } else {
                    QueueAdaptiveBitrate(cap, min)
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
            it.setOverlays(overlays)
        }
        syncBrowsers()
        if (widgetRenderer == null) {
            widgetRenderer = WidgetRenderer(File(context.filesDir, "widgets"), scope, settings.settings, chat.lines, nowPlaying.track, emoteImages) { next ->
                synchronized(this) {
                    overlays = next
                    compositor?.setOverlays(next)
                    syncBrowsers()
                }
            }
        }
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
            pages = overlays.mapNotNull { overlay -> overlay.browser?.let { overlay.id to it } }.toMap()
        }
        browsers.sync(pages, target)
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

    private fun openVideo(scene: Scene, compositor: VideoCompositor) {
        val generation = cameraGeneration
        cameraFps = 0
        compositor.setFrameRate(thermalGuard.fps((liveProfile ?: settings.settings.value.selectedProfile).fps))
        val player = VideoPlayer(
            context,
            scene.videos.map { VideoFile(Uri.parse(it.uri), it.name) },
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
        ) { message -> _ui.update { it.copy(error = message) } }
        player.volume = scene.videoVolume / 100f
        player.micWhilePlaying = scene.micWithVideo
        audioMix.source = player
        _videoPlayer.value = player
    }

    private fun openCamera() {
        val compositor = compositor ?: return
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
