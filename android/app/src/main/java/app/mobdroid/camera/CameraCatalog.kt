package app.mobdroid.camera

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import kotlin.math.max
import kotlin.math.roundToInt

private const val PROBE_LIMIT = 100

object CameraCatalog {
    fun list(context: Context): List<CameraDescriptor> {
        val manager = context.applicationContext.getSystemService(CameraManager::class.java) ?: return emptyList()
        val visible = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            emptyList()
        }
        val ids = LinkedHashSet(visible)
        for (i in 0 until PROBE_LIMIT) ids += i.toString()
        val cameras = ids.mapNotNull { readCamera(manager, it, it in visible) }
        return buildCatalog(cameras).map { it.toDescriptor() }
    }
}

internal val SAMSUNG_VIDEO_CONFIGURATIONS =
    CameraCharacteristics.Key("samsung.android.scaler.availableVideoConfigurations", IntArray::class.java)
private val QUALCOMM_VIDEO_FRAME_RATES =
    CameraCharacteristics.Key("org.quic.camera2.customhfrfps.info.CustomHFRFpsTable", IntArray::class.java)

internal fun vendorTable(chars: CameraCharacteristics, key: CameraCharacteristics.Key<IntArray>): IntArray =
    runCatching { chars.get(key) }.getOrNull() ?: IntArray(0)

private fun characteristics(manager: CameraManager, id: String): CameraCharacteristics? =
    try {
        manager.getCameraCharacteristics(id)
    } catch (e: Exception) {
        null
    }

private fun readCamera(manager: CameraManager, id: String, visible: Boolean): RawCamera? {
    val chars = characteristics(manager, id) ?: return null
    val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
        CameraMetadata.LENS_FACING_BACK -> Facing.BACK
        CameraMetadata.LENS_FACING_FRONT -> Facing.FRONT
        CameraMetadata.LENS_FACING_EXTERNAL -> Facing.EXTERNAL
        else -> return null
    }
    val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
    val outputSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().distinct()
    val sizes = outputSizes.map { Dim(it.width, it.height) }
    val maxFps = outputSizes.associate {
        Dim(it.width, it.height) to framesPerSecond(map.getOutputMinFrameDuration(SurfaceTexture::class.java, it))
    }
    val capabilities = capabilitiesOf(chars)
    val physical = if (Capability.LOGICAL_MULTI_CAMERA in capabilities) {
        chars.physicalCameraIds.mapNotNull { readLens(manager, it, facing, sizes) }
    } else {
        emptyList()
    }
    return RawCamera(
        id = id,
        visible = visible,
        facing = facing,
        capabilities = capabilities,
        monochromeSensor = isMonochrome(chars),
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
        zoomRange = zoomRangeOf(chars),
        lens = lensOf(id, chars, false),
        physicalLenses = physical,
        sizes = sizes,
        maxFps = maxFps,
        fpsRanges = (chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray())
            .map { Fps(it.lower, it.upper) }
            .sortedWith(compareBy({ it.upper }, { it.lower })),
        stabilization = stabilizationOf(chars),
        hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
        realtimeTimestamps = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
        vendorFps = (
            samsungVideoFps(vendorTable(chars, SAMSUNG_VIDEO_CONFIGURATIONS)) +
                qualcommVideoFps(vendorTable(chars, QUALCOMM_VIDEO_FRAME_RATES))
            ).filterKeys { it in sizes },
    )
}

private fun readLens(manager: CameraManager, id: String, facing: Facing, parentSizes: List<Dim>): RawLens? {
    val chars = characteristics(manager, id) ?: return null
    val sizes = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        ?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        .map { Dim(it.width, it.height) }
        .distinct()
        .ifEmpty { parentSizes }
    return lensOf(id, chars, isJunk(facing, capabilitiesOf(chars), isMonochrome(chars), sizes))
}

private fun lensOf(id: String, chars: CameraCharacteristics, junk: Boolean) = RawLens(
    id = id,
    focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f,
    aperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
    sensorWidthMm = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width ?: 0f,
    junk = junk,
)

private fun capabilitiesOf(chars: CameraCharacteristics): Set<Capability> {
    val raw = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?: return setOf(Capability.BACKWARD_COMPATIBLE)
    val result = HashSet<Capability>()
    for (value in raw) {
        val capability = when (value) {
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> Capability.BACKWARD_COMPATIBLE
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> Capability.DEPTH_OUTPUT
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MONOCHROME -> Capability.MONOCHROME
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> Capability.LOGICAL_MULTI_CAMERA
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SYSTEM_CAMERA -> Capability.SYSTEM_CAMERA
            CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_SECURE_IMAGE_DATA -> Capability.SECURE_IMAGE_DATA
            else -> null
        }
        if (capability != null) result += capability
    }
    return result
}

private fun isMonochrome(chars: CameraCharacteristics): Boolean =
    when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_MONO,
        CameraMetadata.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_NIR,
        -> true
        else -> false
    }

private fun zoomRangeOf(chars: CameraCharacteristics): ClosedFloatingPointRange<Float> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        if (range != null && range.lower > 0f && range.upper >= range.lower) return range.lower..range.upper
    }
    return 1f..max(1f, chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
}

private fun stabilizationOf(chars: CameraCharacteristics): List<Stabilization> {
    val modes = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
    val result = arrayListOf(Stabilization.OFF)
    if (CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in modes) result += Stabilization.VIDEO
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION in modes
    ) {
        result += Stabilization.PREVIEW
    }
    return result
}

private fun framesPerSecond(minFrameDurationNs: Long): Int =
    if (minFrameDurationNs <= 0L) 30 else (1e9 / minFrameDurationNs).roundToInt()

private fun CatalogEntry.toDescriptor(): CameraDescriptor = CameraDescriptor(
    id = camera.id,
    physicalId = physicalId,
    facing = camera.facing,
    label = label,
    zoomRatioRange = zoomRange,
    lensZoomStops = zoomStops,
    sensorOrientation = camera.sensorOrientation,
    sizes = camera.sizes.sortedByDescending { it.area }.map { Size(it.width, it.height) },
    fpsRanges = camera.fpsRanges.map { Range(it.lower, it.upper) },
    supports60Fps = supportsFps(camera.fpsRanges, camera.maxFps, 60) || camera.vendorFps.values.any { it >= 60 },
    stabilizationModes = camera.stabilization,
    hasFlash = camera.hasFlash,
    maxFpsBySize = camera.maxFps.mapKeys { Size(it.key.width, it.key.height) },
    realtimeTimestamps = camera.realtimeTimestamps,
    vendorFpsBySize = camera.vendorFps.mapKeys { Size(it.key.width, it.key.height) },
    lenses = lenses.map { LensCamera(it.ratio, it.entry.toDescriptor()) },
)
