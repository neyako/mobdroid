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
import app.mobdroid.camera.chooseSize
import app.mobdroid.engine.RtmpPublisher
import app.mobdroid.engine.SrtPublisher
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
        powerManager.addThermalStatusListener(context.mainExecutor) { status ->
            _ui.update { it.copy(thermal = status) }
        }
    }

    val isLive: Boolean get() = session != null

    @Synchronized
    fun startPreview() {
        if (previewActive) return
        previewActive = true
        val cameras = runCatching { CameraCatalog.list(context) }.getOrDefault(emptyList())
        val key = settings.settings.value.cameraKey
        val selected = cameras.firstOrNull { it.key == key } ?: cameras.firstOrNull { it.facing == Facing.BACK }
            ?: cameras.firstOrNull()
        _ui.update { it.copy(cameras = cameras, camera = selected) }
        ensureCompositor()
        openCamera()
    }

    @Synchronized
    fun stopPreviewIfIdle() {
        if (!previewActive || isLive) return
        previewActive = false
        camera.close()
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
        val codec = CodecCatalog.choose(profile.codec, profile.platform.codecs, w, h, profile.fps)
        if (CodecCatalog.encoders[codec] == null) {
            _ui.update { it.copy(error = "No hardware video encoder") }
            return
        }
        liveProfile = profile
        liveCodec = codec
        targetBitrate = profile.bitrateKbps * 1000
        context.startForegroundService(Intent(context, StreamService::class.java))
        ensureCompositor()
        if (!previewActive) startPreview()
        compositor!!.setOutputSize(w, h)
        compositor!!.setFrameRate(profile.fps)
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
        _ui.update { it.copy(live = LiveState.OFFLINE) }
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
            fps = profile.fps,
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
        if (profile.adaptiveBitrate && session.state.value == LiveState.LIVE) {
            val next = adaptBitrate(stats, profile)
            if (next != targetBitrate) {
                targetBitrate = next
                videoEncoder?.setBitrate(next)
            }
        }
        _stats.value = LiveStats(
            bitrateBps = stats.sendBitrateBps,
            targetBitrateBps = targetBitrate,
            fps = compositor?.measuredFps ?: 0f,
            rttMs = stats.rttMs,
            queuedBytes = stats.queuedBytes,
            droppedFrames = stats.droppedVideoFrames,
            uptimeSeconds = (System.nanoTime() - liveStartedAt) / 1_000_000_000L,
            codec = liveCodec,
            links = links.links.value.size,
            audioLevel = audioEncoder?.level ?: 0f,
        )
        _ui.update { it.copy(battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) }
    }

    private fun adaptBitrate(stats: app.mobdroid.engine.net.PublisherStats, profile: StreamProfile): Int {
        val max = profile.bitrateKbps * 1000
        val min = profile.minBitrateKbps * 1000
        val queuedMs = if (targetBitrate > 0) stats.queuedBytes * 8000 / targetBitrate else 0
        val next = when {
            queuedMs > 1000 -> (targetBitrate * 0.7).toInt()
            queuedMs > 300 -> (targetBitrate * 0.9).toInt()
            else -> targetBitrate + max / 20
        }
        return next.coerceIn(min, max)
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

    private fun openCamera() {
        val descriptor = _ui.value.camera ?: return
        val compositor = compositor ?: return
        val profile = liveProfile ?: settings.settings.value.selectedProfile
        val (w, h) = outputSize(profile)
        val long = maxOf(w, h)
        val short = minOf(w, h)
        val size = chooseSize(descriptor.sizes, long, short)
        currentInputSize = size
        applyInputTransform()
        val fps = if (profile.fps > 30 && !descriptor.supports60Fps) 30 else profile.fps
        camera.open(descriptor, compositor.inputSurface, size, fps, stabilization())
    }

    private var currentInputSize = Size(1920, 1080)

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
    }
}

val CameraDescriptor.key: String get() = "$id:${physicalId.orEmpty()}"

fun outputSize(profile: StreamProfile): Pair<Int, Int> {
    val r = profile.resolution
    return if (profile.orientation == Orientation.LANDSCAPE) r.width to r.height else r.height to r.width
}
