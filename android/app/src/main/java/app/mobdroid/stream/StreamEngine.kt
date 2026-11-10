package app.mobdroid.stream

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.util.Size
import android.view.Surface
import app.mobdroid.camera.CameraCatalog
import app.mobdroid.chat.ChatController
import app.mobdroid.chat.ChatSpeech
import app.mobdroid.chat.createChatClients
import okhttp3.OkHttpClient
import app.mobdroid.camera.CameraController
import app.mobdroid.camera.CameraDescriptor
import app.mobdroid.camera.CameraState
import app.mobdroid.camera.Facing
import app.mobdroid.camera.LensCamera
import app.mobdroid.camera.Stabilization
import app.mobdroid.engine.abr.ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS
import app.mobdroid.engine.abr.AdaptiveBitrate
import app.mobdroid.engine.abr.BelaboxAdaptiveBitrate
import app.mobdroid.engine.abr.QueueAdaptiveBitrate
import app.mobdroid.engine.abr.TransportBitrateMeter
import app.mobdroid.engine.abr.toStreamStats
import app.mobdroid.engine.rtmp.RtmpPublisher
import app.mobdroid.engine.srt.SrtPublisher
import app.mobdroid.settings.allowedCodecs
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.net.Publisher
import app.mobdroid.engine.net.PublisherListener
import app.mobdroid.media.AUDIO_LEVEL_FLOOR_DB
import app.mobdroid.media.AudioEncoder
import app.mobdroid.media.CodecCatalog
import app.mobdroid.media.VideoEncoder
import app.mobdroid.media.gl.VideoCompositor
import app.mobdroid.settings.LoginStore
import app.mobdroid.settings.MicSource
import java.io.File
import app.mobdroid.settings.SettingsStore
import app.mobdroid.settings.streamUrl
import app.mobdroid.settings.StabilizationSetting
import app.mobdroid.settings.StreamProfile
import app.mobdroid.uvc.UvcCamera
import app.mobdroid.uvc.UvcDevices
import app.mobdroid.uvc.closest
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
    private val speech = ChatSpeech(context)
    val chat = ChatController(
        scope,
        { listener -> createChatClients(settings.settings.value.selectedProfile, logins.logins.value, http, listener) },
        { message ->
            val current = settings.settings.value
            if (current.chatSpeech) speech.say(message, current.chatSpeechNames)
        },
    )
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
    private var previewSurface: Surface? = null
    private var displayRotation = 0
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var session: StreamSession? = null
    private var liveProfile: StreamProfile? = null
    private var liveCodec: VideoCodec? = null
    private var monitor: Job? = null
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
                .map { s -> listOf(s.selectedProfile.resolution, s.selectedProfile.fps, s.stabilization) }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    synchronized(this@StreamEngine) {
                        if (isLive || !previewActive) return@collect
                        val (w, h) = outputSize(settings.settings.value.selectedProfile)
                        compositor?.setOutputSize(w, h)
                        openCamera()
                    }
                }
        }
        scope.launch {
            settings.settings.map { it.mirrorFrontCameraOnStream }.distinctUntilChanged().drop(1).collect {
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
                .collect { synchronized(this@StreamEngine) { if (previewActive || isLive) chat.start() } }
        }
        scope.launch {
            logins.logins.drop(1).collect { synchronized(this@StreamEngine) { if (previewActive || isLive) chat.start() } }
        }
        scope.launch {
            settings.settings.map { it.mic }.distinctUntilChanged().drop(1).collect {
                synchronized(this@StreamEngine) { if (!isLive && previewActive) startAudioMeter() }
            }
        }
        powerManager.addThermalStatusListener(context.mainExecutor) { status ->
            val previous = _ui.value.thermal
            _ui.update { it.copy(thermal = status) }
            if (status >= PowerManager.THERMAL_STATUS_CRITICAL && previous < PowerManager.THERMAL_STATUS_CRITICAL) {
                showAlert(AlertLevel.ERROR, "Phone is overheating", "The stream may stop")
            } else if (status == PowerManager.THERMAL_STATUS_SEVERE && previous < PowerManager.THERMAL_STATUS_SEVERE) {
                showAlert(AlertLevel.WARNING, "Phone is hot", "Lower the bitrate or the frame rate")
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
                        showAlert(AlertLevel.WARNING, "Battery low", "$battery%")
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
        label = device.productName?.trim()?.takeIf { it.isNotEmpty() } ?: "USB camera",
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
                val attached = cameras.firstOrNull { it.id == attachedId }
                val next = when {
                    attached != null -> attached
                    current != null && cameras.none { it.key == current.key } -> defaultCamera(cameras)
                    else -> current
                }
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

    private fun defaultCamera(cameras: List<CameraDescriptor>) =
        cameras.firstOrNull { it.facing == Facing.BACK } ?: cameras.firstOrNull()

    val isLive: Boolean get() = session != null

    fun startPreview() {
        synchronized(this) {
            if (previewActive) return
            previewActive = true
        }
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                if (!previewActive) return@launch
                val key = settings.settings.value.cameraKey
                val selected = cameras.firstOrNull { it.key == key } ?: defaultCamera(cameras)
                _ui.update { it.copy(cameras = cameras, camera = selected, lens = selected?.let(::defaultLens)) }
                ensureCompositor()
                openCamera()
                if (!isLive) {
                    startAudioMeter()
                    chat.start()
                }
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
            AudioEncoder(0, null, micDevice(), { _audioLevel.value = it }) { Log.w(TAG, "Audio meter: $it") }
        }.onFailure { Log.w(TAG, "Audio meter failed", it) }.getOrNull()?.also { it.muted = _ui.value.muted }
    }

    @Volatile
    private var cachedCameras: List<CameraDescriptor>? = null

    @Synchronized
    fun stopPreviewIfIdle() {
        if (!previewActive || isLive) return
        previewActive = false
        chat.stop()
        audioEncoder?.release()
        audioEncoder = null
        closeSources()
        compositor?.release()
        compositor = null
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
    fun selectCamera(descriptor: CameraDescriptor) {
        settings.update { it.copy(cameraKey = descriptor.key) }
        _ui.update { it.copy(camera = descriptor, lens = defaultLens(descriptor), zoom = 1f, torch = false) }
        if (previewActive) openCamera()
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

    @Synchronized
    fun goLive() {
        if (isLive) return
        if (LIVE_PERMISSIONS.any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            _ui.update { it.copy(error = "Allow camera and microphone access to go live") }
            return
        }
        val profile = settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        val codec = CodecCatalog.choose(profile.codec, profile.allowedCodecs, w, h, profile.fps)
        if (CodecCatalog.encoders[codec] == null) {
            _ui.update { it.copy(error = "No hardware video encoder") }
            return
        }
        liveProfile = profile
        liveCodec = codec
        targetBitrate = profile.bitrateKbps * 1000
        liveMaxBitrate = targetBitrate.toLong()
        transportMeter = TransportBitrateMeter(ADAPTIVE_BITRATE_START_BPS)
        ensureCompositor()
        if (!previewActive) startPreview()
        compositor!!.setOutputSize(w, h)
        compositor!!.setFrameRate(encoderFps(profile))
        val newSession = StreamSession(ioScope, { listener -> createPublisher(profile, listener) }) {
            videoEncoder?.requestKeyFrame()
        }
        session = newSession
        if (profile.url.startsWith("srtla://")) links.start(profile.bondingNetworks)
        linkBytes = emptyMap()
        try {
            startVideoEncoder()
            audioEncoder?.release()
            audioEncoder = null
            audioEncoder = AudioEncoder(
                profile.audioBitrateKbps * 1000,
                newSession,
                micDevice(),
                { _audioLevel.value = it },
                ::onFatal,
            ).also { it.muted = _ui.value.muted }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoders", e)
            stopLive()
            _ui.update { it.copy(error = e.message ?: "Encoder failed") }
            return
        }
        context.startForegroundService(
            Intent(context, StreamService::class.java).putExtra(StreamService.EXTRA_USB, uvcCamera != null),
        )
        serviceStarted = true
        newSession.start()
        liveStartedAt = System.nanoTime()
        lowBitrate = LowBitrateWatch()
        _ui.update { it.copy(error = null) }
        monitor = scope.launch {
            launch {
                var wasLive = false
                newSession.state.collect { s ->
                    val previous = _ui.value.live
                    _ui.update { it.copy(live = s) }
                    if (s == LiveState.LIVE && previous != LiveState.LIVE) {
                        showAlert(AlertLevel.INFO, if (wasLive) "Stream reconnected" else "You are live")
                        wasLive = true
                    } else if (s == LiveState.RECONNECTING && previous == LiveState.LIVE) {
                        showAlert(AlertLevel.ERROR, "Stream disconnected", newSession.lastError.value ?: "Reconnecting")
                    } else if (s == LiveState.RECONNECTING && previous == LiveState.CONNECTING) {
                        showAlert(AlertLevel.ERROR, "Could not connect", newSession.lastError.value ?: "Retrying")
                    }
                }
            }
            launch { newSession.lastError.collect { e -> if (e != null) _ui.update { it.copy(error = e) } } }
            if (profile.adaptiveBitrate) launch { runAdaptiveBitrate(newSession, profile) }
            while (isActive) {
                delay(1000)
                tick(newSession)
            }
        }
    }

    @Synchronized
    fun stopLive() {
        monitor?.cancel()
        monitor = null
        session?.stop()
        session = null
        compositor?.setEncoder(null)
        videoEncoder?.release()
        videoEncoder = null
        audioEncoder?.release()
        audioEncoder = null
        links.stop()
        liveProfile = null
        _ui.update { it.copy(live = LiveState.OFFLINE, error = null) }
        _stats.value = LiveStats()
        if (serviceStarted) {
            serviceStarted = false
            val service = Intent(context, StreamService::class.java)
            try {
                context.startService(service.setAction(StreamService.ACTION_STOP))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Stopping the stream service directly", e)
                context.stopService(service)
            }
        }
        if (previewSurface == null) stopPreviewIfIdle() else if (previewActive) startAudioMeter()
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
            if (!live.adaptiveBitrate) {
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

    private fun startVideoEncoder() {
        val profile = liveProfile ?: return
        val session = session ?: return
        val (w, h) = outputSize(profile)
        val encoder = VideoEncoder(
            codec = liveCodec!!,
            width = w,
            height = h,
            fps = encoderFps(profile),
            bitrateBps = targetBitrate,
            keyFrameIntervalSeconds = profile.keyFrameIntervalSeconds,
            sink = session,
            onError = ::onEncoderError,
        )
        videoEncoder = encoder
        compositor!!.setEncoder(encoder.inputSurface)
    }

    @Synchronized
    private fun restartVideoEncoder(reason: String) {
        if (!isLive) return
        Log.w(TAG, "Restarting video encoder: $reason")
        compositor?.setEncoder(null)
        val released = videoEncoder?.release(timeoutMs = 5000) ?: true
        videoEncoder = null
        if (!released) {
            stopLive()
            _ui.update { it.copy(error = "Video encoder stopped responding") }
            return
        }
        runCatching { startVideoEncoder() }.onFailure { onFatal("Video encoder: ${it.message}") }
    }

    private fun onEncoderError(message: String) {
        scope.launch {
            delay(200)
            restartVideoEncoder(message)
        }
    }

    private fun onFatal(message: String) {
        scope.launch {
            stopLive()
            showAlert(AlertLevel.ERROR, "Stream stopped", message)
            _ui.update { it.copy(error = message) }
        }
    }

    private fun tick(session: StreamSession) {
        val profile = liveProfile ?: return
        val stats = session.stats()
        val encoder = videoEncoder
        if (encoder != null && _ui.value.cameraState == CameraState.Streaming &&
            System.nanoTime() - encoder.lastOutputNanos > 5_000_000_000L
        ) {
            restartVideoEncoder("no output for 5 s")
        }
        if (session.state.value == LiveState.LIVE) {
            transportMeter.update(stats.bytesSent, System.nanoTime())
        } else {
            transportMeter.pause()
        }
        if (session.state.value == LiveState.LIVE && System.nanoTime() - liveStartedAt > 15_000_000_000L &&
            lowBitrate.update(stats.sendBitrateBps)
        ) {
            showAlert(AlertLevel.WARNING, "Low bitrate", formatKbps(stats.sendBitrateBps))
        }
        val linkStats = (session.publisher as? SrtPublisher)?.linkStats().orEmpty()
        val shares = linkShares(linkBytes, linkStats)
        linkBytes = linkStats.associate { it.id to it.bytesSent }
        _stats.value = LiveStats(
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
            it.setFrameRate(profile.fps)
            it.setPreview(if (previewEnabled) previewSurface else null)
        }
    }

    private fun closeSources() {
        cameraGeneration++
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

    private fun openCamera() {
        val descriptor = _ui.value.activeCamera ?: return
        val compositor = compositor ?: return
        closeSources()
        if (descriptor.id.startsWith(UVC_PREFIX)) {
            openUvc(descriptor, compositor)
            return
        }
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val size = descriptor.chooseSize(long, short, profile.fps) ?: descriptor.sizes.firstOrNull() ?: return
        val fps = minOf(profile.fps, descriptor.maxFps(size))
        cameraFps = fps
        compositor.setFrameRate(encoderFps(profile))
        currentInputSize = size
        applyInputTransform()
        camera.open(descriptor, compositor.inputSurface, size, fps, stabilization())
    }

    private var currentInputSize = Size(1920, 1080)
    private var cameraFps = 0

    private fun encoderFps(profile: StreamProfile) = if (cameraFps > 0) minOf(profile.fps, cameraFps) else profile.fps

    private fun openUvc(descriptor: CameraDescriptor, compositor: VideoCompositor) {
        val generation = cameraGeneration
        val device = uvcDevices.list().firstOrNull { UVC_PREFIX + it.deviceName == descriptor.id } ?: return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        uvcDevices.requestPermission(device) { granted ->
            if (!granted) {
                _ui.update { it.copy(error = "USB camera permission denied") }
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
                val format = uvc.formats.closest(maxOf(w, h), minOf(w, h), profile.fps)
                val surface = synchronized(this@StreamEngine) {
                    if (generation != cameraGeneration || format == null) {
                        uvc.close()
                        if (format == null) _ui.update { it.copy(error = "USB camera has no usable format") }
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
                _ui.update { it.copy(error = "USB camera: $message") }
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
        val descriptor = _ui.value.activeCamera ?: return
        val size = currentInputSize
        compositor?.setInput(
            size.width,
            size.height,
            descriptor.sensorOrientation,
            if (descriptor.facing == Facing.EXTERNAL) 0 else displayRotation,
            descriptor.facing == Facing.FRONT,
            settings.settings.value.mirrorFrontCameraOnStream,
            boottimeTimestamps = uvcCamera == null && descriptor.realtimeTimestamps,
        )
    }

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
        val LIVE_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}

val CameraDescriptor.key: String get() = "$id:${physicalId.orEmpty()}"

private fun formatKbps(bps: Long) = "${bps / 1000} kbps"

fun outputSize(profile: StreamProfile): Pair<Int, Int> {
    return profile.resolution.width to profile.resolution.height
}
