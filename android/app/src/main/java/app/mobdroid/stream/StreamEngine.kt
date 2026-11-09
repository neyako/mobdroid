package app.mobdroid.stream

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import app.mobdroid.camera.CameraCatalog
import app.mobdroid.camera.CameraController
import app.mobdroid.camera.CameraDescriptor
import app.mobdroid.camera.CameraState
import app.mobdroid.camera.Facing
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
import app.mobdroid.media.AudioEncoder
import app.mobdroid.media.CodecCatalog
import app.mobdroid.media.VideoEncoder
import app.mobdroid.media.gl.VideoCompositor
import app.mobdroid.settings.Orientation
import app.mobdroid.settings.SettingsStore
import app.mobdroid.settings.StabilizationSetting
import app.mobdroid.settings.StreamProfile
import app.mobdroid.uvc.UvcCamera
import app.mobdroid.uvc.UvcDevices
import app.mobdroid.uvc.closest
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Range
import androidx.core.content.ContextCompat
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
    val links: Int = 0,
    val audioLevel: Float = 0f,
)

data class UiState(
    val cameras: List<CameraDescriptor> = emptyList(),
    val camera: CameraDescriptor? = null,
    val cameraState: CameraState = CameraState.Closed,
    val zoom: Float = 1f,
    val torch: Boolean = false,
    val muted: Boolean = false,
    val live: LiveState = LiveState.OFFLINE,
    val error: String? = null,
    val thermal: Int = PowerManager.THERMAL_STATUS_NONE,
    val battery: Int = 100,
)

class StreamEngine(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private val camera = CameraController(context)
    private val links = AndroidNetworkLinks(context)
    private val uvcDevices = UvcDevices(context)
    private var uvcCamera: UvcCamera? = null
    private var uvcSurface: Surface? = null
    private var cameraGeneration = 0
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _stats = MutableStateFlow(LiveStats())
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

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
        scope.launch { camera.state.collect { s -> _ui.update { it.copy(cameraState = s) } } }
        scope.launch {
            settings.settings
                .map { s -> listOf(s.selectedProfile.resolution, s.selectedProfile.orientation, s.selectedProfile.fps, s.stabilization) }
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
        powerManager.addThermalStatusListener(context.mainExecutor) { status ->
            _ui.update { it.copy(thermal = status) }
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    onUsbChanged(intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED)
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

    private fun onUsbChanged(attached: Boolean) {
        scope.launch {
            val cameras = listCameras()
            synchronized(this@StreamEngine) {
                val current = _ui.value.camera
                val newUvc = cameras.firstOrNull { it.id.startsWith(UVC_PREFIX) && it.key != current?.key }
                val next = when {
                    attached && newUvc != null -> newUvc
                    current != null && cameras.none { it.key == current.key } -> defaultCamera(cameras)
                    else -> current
                }
                _ui.update { it.copy(cameras = cameras) }
                if (next != null && next.key != current?.key) {
                    _ui.update { it.copy(camera = next, zoom = 1f, torch = false) }
                    if (previewActive) openCamera()
                }
            }
        }
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
                _ui.update { it.copy(cameras = cameras, camera = selected) }
                ensureCompositor()
                openCamera()
            }
        }
    }

    @Volatile
    private var cachedCameras: List<CameraDescriptor>? = null

    @Synchronized
    fun stopPreviewIfIdle() {
        if (!previewActive || isLive) return
        previewActive = false
        closeSources()
        compositor?.release()
        compositor = null
    }

    @Synchronized
    fun setPreviewSurface(surface: Surface?) {
        previewSurface = surface
        compositor?.setPreview(surface)
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
        _ui.update { it.copy(camera = descriptor, zoom = 1f, torch = false) }
        if (previewActive) openCamera()
    }

    fun setZoom(ratio: Float) {
        val range = _ui.value.camera?.zoomRatioRange ?: return
        val clamped = ratio.coerceIn(range.start, range.endInclusive)
        camera.setZoom(clamped)
        _ui.update { it.copy(zoom = clamped) }
    }

    fun setTorch(on: Boolean) {
        camera.setTorch(on)
        _ui.update { it.copy(torch = on) }
    }

    fun setMuted(muted: Boolean) {
        audioEncoder?.muted = muted
        _ui.update { it.copy(muted = muted) }
    }

    fun tapToFocus(x: Float, y: Float) = camera.tapToFocus(x, y)

    fun setExposureCompensation(ev: Float) = camera.setExposureCompensation(ev)

    @Synchronized
    fun goLive() {
        if (isLive) return
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
        context.startForegroundService(
            Intent(context, StreamService::class.java).putExtra(StreamService.EXTRA_USB, uvcCamera != null),
        )
        ensureCompositor()
        if (!previewActive) startPreview()
        compositor!!.setOutputSize(w, h)
        compositor!!.setFrameRate(encoderFps(profile))
        val newSession = StreamSession(ioScope, { listener -> createPublisher(profile, listener) }) {
            videoEncoder?.requestKeyFrame()
        }
        session = newSession
        if (profile.url.startsWith("srtla://")) links.start()
        try {
            startVideoEncoder()
            audioEncoder = AudioEncoder(profile.audioBitrateKbps * 1000, newSession, ::onFatal).also {
                it.muted = _ui.value.muted
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start encoders", e)
            stopLive()
            _ui.update { it.copy(error = e.message ?: "Encoder failed") }
            return
        }
        newSession.start()
        liveStartedAt = System.nanoTime()
        _ui.update { it.copy(error = null) }
        monitor = scope.launch {
            launch { newSession.state.collect { s -> _ui.update { it.copy(live = s) } } }
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
        context.stopService(Intent(context, StreamService::class.java))
        if (previewSurface == null) stopPreviewIfIdle()
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun createPublisher(profile: StreamProfile, listener: PublisherListener): Publisher {
        val url = profile.url.trim()
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
        videoEncoder?.release()
        videoEncoder = null
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
        transportMeter.update(stats.bytesSent, System.nanoTime())
        _stats.value = LiveStats(
            bitrateBps = stats.sendBitrateBps,
            targetBitrateBps = targetBitrate,
            fps = compositor?.measuredFps ?: 0f,
            rttMs = stats.rttMs,
            queuedBytes = stats.queuedBytes,
            droppedFrames = stats.droppedVideoFrames,
            uptimeSeconds = (System.nanoTime() - liveStartedAt) / 1_000_000_000L,
            codec = liveCodec,
            links = (session.publisher as? SrtPublisher)?.linkStats()?.count { it.bytesSent > 0 } ?: 0,
            audioLevel = audioEncoder?.level ?: 0f,
        )
        _ui.update { it.copy(battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) }
    }

    private val transportMeter = TransportBitrateMeter(ADAPTIVE_BITRATE_START_BPS)

    private suspend fun runAdaptiveBitrate(session: StreamSession, profile: StreamProfile) {
        val max = profile.bitrateKbps * 1000L
        val min = profile.minBitrateKbps * 1000L
        var abr: AdaptiveBitrate? = null
        var owner: Any? = null
        while (true) {
            delay(ADAPTIVE_BITRATE_UPDATE_INTERVAL_MS)
            val publisher = session.publisher
            if (publisher == null || session.state.value != LiveState.LIVE) continue
            if (owner !== publisher) {
                owner = publisher
                abr = if (publisher is SrtPublisher) {
                    BelaboxAdaptiveBitrate(max, min)
                } else {
                    QueueAdaptiveBitrate(max, min)
                }
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
            it.setPreview(previewSurface)
        }
    }

    private fun closeSources() {
        cameraGeneration++
        camera.close()
        uvcCamera?.let { uvc ->
            uvcCamera = null
            uvc.onError = null
            uvc.close()
        }
        uvcSurface?.release()
        uvcSurface = null
    }

    private fun openCamera() {
        val descriptor = _ui.value.camera ?: return
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
                    _ui.update { it.copy(error = "USB camera: ${e.message}") }
                    return@launch
                }
                val format = uvc.formats.closest(maxOf(w, h), minOf(w, h), profile.fps)
                synchronized(this@StreamEngine) {
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
                    val surface = compositor.newInputSurface().also { uvcSurface = it }
                    uvc.onError = { message -> onUvcError(generation, message) }
                    try {
                        uvc.start(format, surface)
                        _ui.update { it.copy(cameraState = CameraState.Streaming) }
                    } catch (e: Exception) {
                        Log.e(TAG, "USB camera start failed", e)
                        onUvcError(generation, e.message ?: "start failed")
                    }
                }
            }
        }
    }

    private fun onUvcError(generation: Int, message: String) {
        scope.launch {
            synchronized(this@StreamEngine) {
                if (generation != cameraGeneration) return@launch
                _ui.update { it.copy(error = "USB camera: $message") }
                closeSources()
            }
            delay(1000)
            onUsbChanged(attached = false)
        }
    }

    private fun applyInputTransform() {
        val descriptor = _ui.value.camera ?: return
        val size = currentInputSize
        val rotation = when (descriptor.facing) {
            Facing.FRONT -> (descriptor.sensorOrientation + displayRotation) % 360
            Facing.BACK -> (descriptor.sensorOrientation - displayRotation + 360) % 360
            Facing.EXTERNAL -> 0
        }
        compositor?.setInput(size.width, size.height, rotation, descriptor.facing == Facing.FRONT)
    }

    private fun stabilization(): Stabilization {
        val modes = _ui.value.camera?.stabilizationModes.orEmpty()
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
    }
}

val CameraDescriptor.key: String get() = "$id:${physicalId.orEmpty()}"

fun outputSize(profile: StreamProfile): Pair<Int, Int> {
    val r = profile.resolution
    return if (profile.orientation == Orientation.LANDSCAPE) r.width to r.height else r.height to r.width
}
