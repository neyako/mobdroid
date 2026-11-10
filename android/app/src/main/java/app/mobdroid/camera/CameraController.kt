package app.mobdroid.camera

import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

private const val TAG = "CameraController"
private const val NONE = -1
private const val STALL_MS = 3_000L
private const val STARTUP_MS = 12_000L
private const val SETTLE_MS = 4_000L
private const val STABLE_MS = 10_000L
private const val MIN_REOPEN_GAP_MS = 1_000L
private const val TAP_HOLD_MS = 5_000L
private const val MAX_CONFIGURE_FAILURES = 5
private const val MAX_LEVEL = 2
private const val AF_REGION_FRACTION = 0.15f
private const val AE_REGION_FRACTION = 0.3f

private enum class Reason { BUSY, FAULT, CONFIGURE }

private class OpenRequest(
    val camera: CameraDescriptor,
    val surface: Surface,
    val size: Size,
    val fps: Int,
    val stabilization: Stabilization,
)

private class Tap(val af: MeteringRectangle, val ae: MeteringRectangle)

private class Controls {
    var zoom = 1f
    var torch = false
    var exposure = 0f
    var focusLocked = false
    var exposureLocked = false
    var whiteBalanceLocked = false
    var tap: Tap? = null

    fun reset() {
        zoom = 1f
        torch = false
        exposure = 0f
        focusLocked = false
        exposureLocked = false
        whiteBalanceLocked = false
        tap = null
    }
}

private val SAMSUNG_RECORDING_MAX_FPS =
    CaptureRequest.Key("samsung.android.control.recordingMaxFps", Int::class.javaObjectType)
private val SAMSUNG_RECORDING_MIN_FPS =
    CaptureRequest.Key("samsung.android.control.recordingMinFps", Int::class.javaObjectType)

private class Plan(val fpsRange: Range<Int>?, val vendorFps: Int, val stabilizationMode: Int, val ois: Boolean)

private class Hardware(chars: CameraCharacteristics) {
    private val focusModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    private val compensation = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)

    val activeArray: IRect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        ?.let { IRect(it.left, it.top, it.right, it.bottom) } ?: IRect(0, 0, 0, 0)
    val autoFocus = CameraMetadata.CONTROL_AF_MODE_AUTO in focusModes
    val continuousFocus: Int? = when {
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in focusModes ->
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in focusModes ->
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        else -> null
    }
    val maxFocusRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxExposureRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
    val exposureLock = chars.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
    val whiteBalanceLock = chars.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
    val compensationLower = compensation?.lower ?: 0
    val compensationUpper = compensation?.upper ?: 0
    val compensationStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f
    val zoomRatio = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
    val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
    val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val hasVideoStabilization =
        chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.isNotEmpty() == true
    val samsungRecordingFps = vendorTable(chars, SAMSUNG_VIDEO_CONFIGURATIONS).isNotEmpty()
    val videoRecordUseCase = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        chars.get(CameraCharacteristics.SCALER_AVAILABLE_STREAM_USE_CASES)
            ?.contains(CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()) == true
}

private fun IRect.toRect() = Rect(left, top, right, bottom)

private fun IRect.toMetering() = MeteringRectangle(left, top, width, height, MeteringRectangle.METERING_WEIGHT_MAX)

class CameraController(context: Context) {
    private val manager = requireNotNull(context.applicationContext.getSystemService(CameraManager::class.java))
    private val handler = CameraThread.handler
    private val executor = CameraThread.executor
    private val mutableState = MutableStateFlow<CameraState>(CameraState.Closed)

    val state: StateFlow<CameraState> = mutableState.asStateFlow()

    private val controls = Controls()
    private val closing = HashSet<CameraDevice>()
    private var request: OpenRequest? = null
    private var hardware: Hardware? = null
    private var plan = Plan(null, 0, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF, false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var builder: CaptureRequest.Builder? = null
    private var generation = 0
    private var pendingOpen = NONE
    private var wantOpen = false
    private var retryScheduled = false
    private var waitingForAvailability = false
    private var availabilityRegistered = false
    private var failures = 0
    private var configureFailures = 0
    private var level = 0
    private var streaming = false
    private var framesToLensCheck = 0

    @Volatile
    var onActiveLens: ((CameraDescriptor, String?) -> Unit)? = null
    private var streamingSince = 0L
    private var lastFrame = 0L
    private var lastOpenAttempt = 0L
    private var startupDeadline = 0L
    private var applyPosted = false
    private var pendingTrigger = NONE

    private val retryRunnable = Runnable {
        retryScheduled = false
        wantOpen = true
        guarded { proceed() }
    }
    private val settleRunnable = Runnable {
        pendingOpen = NONE
        closing.clear()
        guarded { proceed() }
    }
    private val watchdogRunnable = Runnable { guarded { checkWatchdog() } }
    private val applyRunnable = Runnable { guarded { applyControls() } }
    private val tapExpiryRunnable = Runnable { guarded { expireTap() } }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            guarded { handleAvailable(cameraId) }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            captured: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            if (session !== this@CameraController.session) return
            lastFrame = SystemClock.elapsedRealtime()
            if (!streaming) onFirstFrame()
            if (framesToLensCheck > 0 && --framesToLensCheck == 0) {
                request?.let { onActiveLens?.invoke(it.camera, result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)) }
            }
        }
    }

    fun open(camera: CameraDescriptor, surface: Surface, size: Size, fps: Int, stabilization: Stabilization) {
        post { startRequest(OpenRequest(camera, surface, size, fps, stabilization)) }
    }

    fun close() {
        post { stopRequest() }
    }

    fun setZoom(ratio: Float) {
        post {
            controls.zoom = ratio
            scheduleApply()
        }
    }

    fun setTorch(on: Boolean) {
        post {
            controls.torch = on
            scheduleApply()
        }
    }

    fun setExposureCompensation(ev: Float) {
        post {
            controls.exposure = ev
            scheduleApply()
        }
    }

    fun tapToFocus(x: Float, y: Float) {
        post { startTap(x, y) }
    }

    fun setAutoFocusLocked(locked: Boolean) {
        post {
            controls.focusLocked = locked
            if (!locked && controls.tap == null && hardware?.autoFocus == true) {
                pendingTrigger = CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            }
            scheduleApply()
        }
    }

    fun setAutoExposureLocked(locked: Boolean) {
        post {
            controls.exposureLocked = locked
            scheduleApply()
        }
    }

    fun setWhiteBalanceLocked(locked: Boolean) {
        post {
            controls.whiteBalanceLocked = locked
            scheduleApply()
        }
    }

    private inline fun guarded(reason: Reason = Reason.FAULT, block: () -> Unit) {
        try {
            block()
        } catch (e: CameraAccessException) {
            fail(describe(e.reason), true, if (isBusy(e.reason)) Reason.BUSY else reason)
        } catch (e: Exception) {
            fail("Unexpected camera error: ${e.message}", true, reason)
        }
    }

    private inline fun post(crossinline block: () -> Unit) {
        handler.post { guarded { block() } }
    }

    private fun publish(newState: CameraState) {
        mutableState.value = newState
    }

    private fun startRequest(newRequest: OpenRequest) {
        cancelTimers()
        teardown()
        request = newRequest
        controls.reset()
        failures = 0
        configureFailures = 0
        level = 0
        waitingForAvailability = false
        registerAvailability()
        publish(CameraState.Opening)
        wantOpen = true
        proceed()
    }

    private fun stopRequest() {
        request = null
        wantOpen = false
        cancelTimers()
        unregisterAvailability()
        teardown()
        hardware = null
        proceed()
    }

    private fun cancelTimers() {
        retryScheduled = false
        handler.removeCallbacks(retryRunnable)
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(tapExpiryRunnable)
    }

    private fun proceed() {
        val current = request
        val idle = pendingOpen == NONE && closing.isEmpty() && device == null
        if (idle) handler.removeCallbacks(settleRunnable)
        if (current == null) {
            if (idle) publish(CameraState.Closed)
            return
        }
        if (!wantOpen || retryScheduled || pendingOpen != NONE || closing.isNotEmpty()) return
        startOpen(current)
    }

    private fun startOpen(current: OpenRequest) {
        wantOpen = false
        val chars = try {
            manager.getCameraCharacteristics(current.camera.id)
        } catch (e: IllegalArgumentException) {
            fail("Camera ${current.camera.id} not found", true, Reason.FAULT)
            return
        } catch (e: CameraAccessException) {
            fail("Camera unavailable", true, Reason.BUSY)
            return
        }
        hardware = Hardware(chars)
        val now = SystemClock.elapsedRealtime()
        lastOpenAttempt = now
        startupDeadline = now + STARTUP_MS
        armWatchdog(STARTUP_MS)
        val attempt = ++generation
        pendingOpen = attempt
        try {
            manager.openCamera(current.camera.id, executor, DeviceCallback(attempt))
        } catch (e: SecurityException) {
            pendingOpen = NONE
            fail("Camera permission denied", false, Reason.FAULT)
        } catch (e: CameraAccessException) {
            pendingOpen = NONE
            fail(describe(e.reason), true, if (isBusy(e.reason)) Reason.BUSY else Reason.FAULT)
        } catch (e: IllegalArgumentException) {
            pendingOpen = NONE
            fail("Camera ${current.camera.id} cannot be opened", false, Reason.FAULT)
        }
    }

    private fun isBusy(reason: Int) = reason == CameraAccessException.CAMERA_IN_USE ||
        reason == CameraAccessException.MAX_CAMERAS_IN_USE ||
        reason == CameraAccessException.CAMERA_DISABLED ||
        reason == CameraAccessException.CAMERA_DISCONNECTED

    private fun describe(reason: Int) = when (reason) {
        CameraAccessException.CAMERA_IN_USE -> "Camera in use by another app"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "Too many cameras in use"
        CameraAccessException.CAMERA_DISABLED -> "Camera disabled by policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "Camera disconnected"
        else -> "Camera error"
    }

    private fun handleOpened(attempt: Int, opened: CameraDevice) {
        if (pendingOpen == attempt) pendingOpen = NONE
        if (attempt != generation || request == null) {
            discard(opened)
            proceed()
            return
        }
        device = opened
        guarded(Reason.CONFIGURE) { createSession(opened) }
    }

    private fun handleDeviceFailure(attempt: Int, failed: CameraDevice, message: String, reason: Reason) {
        if (pendingOpen == attempt) pendingOpen = NONE
        if (attempt != generation || request == null) {
            discard(failed)
            proceed()
            return
        }
        device = failed
        fail(message, true, reason)
    }

    private fun handleError(attempt: Int, failed: CameraDevice, error: Int) {
        when (error) {
            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE ->
                handleDeviceFailure(attempt, failed, "Camera in use by another app", Reason.BUSY)
            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE ->
                handleDeviceFailure(attempt, failed, "Too many cameras in use", Reason.BUSY)
            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED ->
                handleDeviceFailure(attempt, failed, "Camera disabled by policy", Reason.BUSY)
            CameraDevice.StateCallback.ERROR_CAMERA_DEVICE ->
                handleDeviceFailure(attempt, failed, "Camera device error", Reason.FAULT)
            CameraDevice.StateCallback.ERROR_CAMERA_SERVICE ->
                handleDeviceFailure(attempt, failed, "Camera service error", Reason.FAULT)
            else -> handleDeviceFailure(attempt, failed, "Camera error $error", Reason.FAULT)
        }
    }

    private fun handleClosed(closed: CameraDevice) {
        if (closed === device) {
            fail("Camera closed unexpectedly", true, Reason.FAULT)
            return
        }
        closing.remove(closed)
        if (closing.isEmpty()) proceed()
    }

    private fun createSession(opened: CameraDevice) {
        val current = request ?: return
        val hw = hardware ?: return
        if (!current.surface.isValid) {
            fail("Surface is no longer valid", false, Reason.FAULT)
            return
        }
        val stabilization = if (level >= MAX_LEVEL) {
            Stabilization.OFF
        } else {
            effectiveStabilization(current.stabilization, current.camera.stabilizationModes)
        }
        val ranges = current.camera.fpsRanges.map { Fps(it.lower, it.upper) }
        val vendorFps = if (level >= MAX_LEVEL) {
            0
        } else {
            vendorFps(ranges, current.fps, current.camera.vendorFpsBySize[current.size] ?: 0)
        }
        val fpsRange = if (vendorFps > 0) {
            Fps(vendorFps, vendorFps)
        } else {
            chooseFpsRange(ranges, current.fps, current.camera.maxFps(current.size))
        }
        plan = Plan(
            fpsRange = fpsRange?.let { Range(it.lower, it.upper) },
            vendorFps = vendorFps,
            stabilizationMode = when (stabilization) {
                Stabilization.OFF -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                Stabilization.VIDEO -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                Stabilization.PREVIEW -> CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION
            },
            ois = stabilization == Stabilization.OFF,
        )
        val captureRequest = opened.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        captureRequest.addTarget(current.surface)
        builder = captureRequest
        fill(captureRequest, current, hw)
        val output = OutputConfiguration(current.surface)
        current.camera.physicalId?.let { output.setPhysicalCameraId(it) }
        if (level == 0 && hw.videoRecordUseCase) {
            output.streamUseCase = CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        }
        val configuration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            listOf(output),
            executor,
            SessionCallback(generation),
        )
        if (level == 0) configuration.sessionParameters = captureRequest.build()
        opened.createCaptureSession(configuration)
    }

    private fun handleConfigured(attempt: Int, configured: CameraCaptureSession) {
        val captureRequest = builder
        val current = request
        val hw = hardware
        if (attempt != generation || captureRequest == null || current == null || hw == null) {
            closeQuietly(configured)
            return
        }
        session = configured
        fill(captureRequest, current, hw)
        lastFrame = SystemClock.elapsedRealtime()
        configured.setRepeatingRequest(captureRequest.build(), captureCallback, handler)
    }

    private fun handleConfigureFailed(attempt: Int, failed: CameraCaptureSession) {
        closeQuietly(failed)
        if (attempt != generation || request == null) return
        fail("Camera session configuration failed", true, Reason.CONFIGURE)
    }

    private fun onFirstFrame() {
        streaming = true
        framesToLensCheck = 30
        streamingSince = lastFrame
        configureFailures = 0
        armWatchdog(STALL_MS)
        publish(CameraState.Streaming)
    }

    private fun fail(message: String, recoverable: Boolean, reason: Reason) {
        Log.w(TAG, "$message (recoverable=$recoverable, reason=$reason)")
        val stable = streamingSince != 0L && SystemClock.elapsedRealtime() - streamingSince >= STABLE_MS
        val starting = builder != null && !streaming
        teardown()
        if (request == null) return
        handler.removeCallbacks(retryRunnable)
        retryScheduled = false
        wantOpen = false
        if (!recoverable) {
            publish(CameraState.Error(message, false))
            return
        }
        if (stable) failures = 0
        failures++
        if (reason == Reason.CONFIGURE || (reason == Reason.FAULT && starting)) level = min(level + 1, MAX_LEVEL)
        if (reason == Reason.CONFIGURE) {
            configureFailures++
            if (configureFailures >= MAX_CONFIGURE_FAILURES) {
                publish(CameraState.Error("Camera configuration failed", false))
                return
            }
        }
        waitingForAvailability = reason == Reason.BUSY
        publish(CameraState.Error(message, true))
        retryScheduled = true
        handler.postDelayed(retryRunnable, retryDelayMs(failures))
    }

    private fun teardown() {
        generation++
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(tapExpiryRunnable)
        streaming = false
        streamingSince = 0L
        pendingTrigger = NONE
        controls.tap = null
        builder = null
        session?.let { closeQuietly(it) }
        session = null
        device?.let { discard(it) }
        device = null
        if (pendingOpen != NONE) armSettle()
    }

    private fun discard(target: CameraDevice) {
        closing += target
        armSettle()
        try {
            target.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing camera device failed", e)
        }
    }

    private fun closeQuietly(target: CameraCaptureSession) {
        try {
            target.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing capture session failed", e)
        }
    }

    private fun armSettle() {
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, SETTLE_MS)
    }

    private fun armWatchdog(delayMs: Long) {
        handler.removeCallbacks(watchdogRunnable)
        handler.postDelayed(watchdogRunnable, delayMs)
    }

    private fun checkWatchdog() {
        val now = SystemClock.elapsedRealtime()
        when {
            streaming -> {
                val idle = now - lastFrame
                if (idle >= STALL_MS) {
                    fail("Camera stopped delivering frames", true, Reason.FAULT)
                } else {
                    handler.postDelayed(watchdogRunnable, STALL_MS - idle)
                }
            }
            now >= startupDeadline ->
                fail("Camera did not start", true, if (session != null) Reason.CONFIGURE else Reason.FAULT)
            else -> handler.postDelayed(watchdogRunnable, startupDeadline - now)
        }
    }

    private fun registerAvailability() {
        if (availabilityRegistered) return
        manager.registerAvailabilityCallback(executor, availabilityCallback)
        availabilityRegistered = true
    }

    private fun unregisterAvailability() {
        if (!availabilityRegistered) return
        manager.unregisterAvailabilityCallback(availabilityCallback)
        availabilityRegistered = false
    }

    private fun handleAvailable(cameraId: String) {
        val current = request ?: return
        if (!retryScheduled || !waitingForAvailability || cameraId != current.camera.id) return
        if (SystemClock.elapsedRealtime() - lastOpenAttempt < MIN_REOPEN_GAP_MS) return
        handler.removeCallbacks(retryRunnable)
        retryRunnable.run()
    }

    private fun scheduleApply() {
        if (applyPosted || session == null) return
        applyPosted = true
        handler.post(applyRunnable)
    }

    private fun applyControls() {
        applyPosted = false
        val active = session ?: return
        val captureRequest = builder ?: return
        val current = request ?: return
        val hw = hardware ?: return
        fill(captureRequest, current, hw)
        if (pendingTrigger != NONE) {
            captureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, pendingTrigger)
            active.capture(captureRequest.build(), null, handler)
            captureRequest.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            pendingTrigger = NONE
        }
        active.setRepeatingRequest(captureRequest.build(), captureCallback, handler)
    }

    private fun currentZoom(current: OpenRequest): Float {
        val range = current.camera.zoomRatioRange
        val zoom = controls.zoom
        return if (zoom.isNaN()) 1f else zoom.coerceIn(range.start, range.endInclusive)
    }

    private fun startTap(x: Float, y: Float) {
        val current = request ?: return
        val hw = hardware ?: return
        if (session == null || hw.activeArray.width <= 0 || hw.activeArray.height <= 0) return
        val zoom = if (hw.zoomRatio) 1f else currentZoom(current)
        val aspect = current.size.width.toFloat() / current.size.height
        controls.tap = Tap(
            meteringRect(hw.activeArray, zoom, aspect, x, y, AF_REGION_FRACTION).toMetering(),
            meteringRect(hw.activeArray, zoom, aspect, x, y, AE_REGION_FRACTION).toMetering(),
        )
        if (hw.autoFocus) pendingTrigger = CameraMetadata.CONTROL_AF_TRIGGER_START
        handler.removeCallbacks(tapExpiryRunnable)
        handler.postDelayed(tapExpiryRunnable, TAP_HOLD_MS)
        scheduleApply()
    }

    private fun expireTap() {
        if (controls.tap == null) return
        controls.tap = null
        if (hardware?.autoFocus == true && !controls.focusLocked) {
            pendingTrigger = CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
        }
        scheduleApply()
    }

    private fun fill(b: CaptureRequest.Builder, current: OpenRequest, hw: Hardware) {
        val tap = controls.tap
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        plan.fpsRange?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (plan.vendorFps > 0 && hw.samsungRecordingFps) {
            b.set(SAMSUNG_RECORDING_MAX_FPS, plan.vendorFps)
            b.set(SAMSUNG_RECORDING_MIN_FPS, plan.vendorFps)
        }
        if (hw.hasVideoStabilization) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, plan.stabilizationMode)
        }
        if (hw.ois) {
            b.set(
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                if (plan.ois) {
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                } else {
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                },
            )
        }
        val zoom = currentZoom(current)
        if (hw.zoomRatio) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else if (hw.activeArray.width > 0 && hw.activeArray.height > 0) {
            b.set(CaptureRequest.SCALER_CROP_REGION, if (zoom > 1f) cropRegion(hw.activeArray, zoom).toRect() else null)
        }
        if (hw.compensationUpper != hw.compensationLower) {
            b.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureIndex(controls.exposure, hw.compensationStep, hw.compensationLower, hw.compensationUpper),
            )
        }
        if (hw.exposureLock) b.set(CaptureRequest.CONTROL_AE_LOCK, controls.exposureLocked)
        if (hw.whiteBalanceLock) b.set(CaptureRequest.CONTROL_AWB_LOCK, controls.whiteBalanceLocked)
        if (hw.flash) {
            b.set(
                CaptureRequest.FLASH_MODE,
                if (controls.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
            )
        }
        val hold = controls.focusLocked || tap != null
        val focusMode = when {
            hold && hw.autoFocus -> CameraMetadata.CONTROL_AF_MODE_AUTO
            else -> hw.continuousFocus
        }
        if (focusMode != null) b.set(CaptureRequest.CONTROL_AF_MODE, focusMode)
        b.set(
            CaptureRequest.CONTROL_AF_REGIONS,
            if (tap != null && hw.maxFocusRegions > 0) arrayOf(tap.af) else null,
        )
        b.set(
            CaptureRequest.CONTROL_AE_REGIONS,
            if (tap != null && hw.maxExposureRegions > 0) arrayOf(tap.ae) else null,
        )
    }

    private inner class DeviceCallback(private val attempt: Int) : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            guarded { handleOpened(attempt, camera) }
        }

        override fun onDisconnected(camera: CameraDevice) {
            guarded { handleDeviceFailure(attempt, camera, "Camera disconnected", Reason.BUSY) }
        }

        override fun onError(camera: CameraDevice, error: Int) {
            guarded { handleError(attempt, camera, error) }
        }

        override fun onClosed(camera: CameraDevice) {
            guarded { handleClosed(camera) }
        }
    }

    private inner class SessionCallback(private val attempt: Int) : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            guarded(Reason.CONFIGURE) { handleConfigured(attempt, session) }
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            guarded(Reason.CONFIGURE) { handleConfigureFailed(attempt, session) }
        }
    }
}
