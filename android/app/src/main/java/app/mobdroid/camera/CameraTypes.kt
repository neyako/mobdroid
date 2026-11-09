package app.mobdroid.camera

import android.util.Range
import android.util.Size

enum class Facing { BACK, FRONT, EXTERNAL }

enum class Stabilization { OFF, VIDEO, PREVIEW }

data class CameraDescriptor(
    val id: String,
    val physicalId: String?,
    val facing: Facing,
    val label: String,
    val zoomRatioRange: ClosedFloatingPointRange<Float>,
    val lensZoomStops: List<Float>,
    val sensorOrientation: Int,
    val sizes: List<Size>,
    val fpsRanges: List<Range<Int>>,
    val supports60Fps: Boolean,
    val stabilizationModes: List<Stabilization>,
    val hasFlash: Boolean,
    val maxFpsBySize: Map<Size, Int> = emptyMap(),
    val realtimeTimestamps: Boolean = false,
) {
    fun maxFps(size: Size): Int = maxFpsBySize[size] ?: fpsRanges.maxOfOrNull { it.upper } ?: 30

    fun chooseSize(width: Int, height: Int, fps: Int): Size? =
        pickSize(sizes, { it.width }, { it.height }, ::maxFps, width, height, fps)
}

sealed interface CameraState {
    data object Closed : CameraState

    data object Opening : CameraState

    data object Streaming : CameraState

    data class Error(val message: String, val recoverable: Boolean) : CameraState
}
