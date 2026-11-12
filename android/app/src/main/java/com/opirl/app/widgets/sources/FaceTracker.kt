package com.opirl.app.widgets.sources

import android.graphics.Bitmap
import android.graphics.PointF
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.opirl.app.widgets.Face
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

fun mouthOpening(upperLip: List<Float>, lowerLip: List<Float>, faceHeight: Float): Float {
    if (upperLip.isEmpty() || lowerLip.isEmpty() || faceHeight <= 0f) return 0f
    val gap = lowerLip[lowerLip.size / 2] - upperLip[upperLip.size / 2]
    return (gap / faceHeight * 8f).coerceIn(0f, 1f)
}

class FaceTracker {
    private val _faces = MutableStateFlow<Map<String?, Face>>(emptyMap())
    val faces: StateFlow<Map<String?, Face>> = _faces.asStateFlow()
    private var detector: FaceDetector? = null
    private val direct = Executor { it.run() }

    @Synchronized
    fun stop() {
        detector?.close()
        detector = null
        _faces.value = emptyMap()
    }

    @Synchronized
    fun process(camera: String?, frame: Bitmap, done: () -> Unit) {
        val current = detector ?: FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .build(),
        ).also { detector = it }
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()
        current.process(InputImage.fromBitmap(frame, 0)).addOnCompleteListener(direct) { task ->
            val found = task.takeIf { it.isSuccessful }?.result?.maxByOrNull { it.boundingBox.width() }
            synchronized(this) {
                if (detector === current) {
                    _faces.value = if (found == null) {
                        emptyMap()
                    } else {
                        val box = found.boundingBox
                        fun ys(type: Int) = found.getContour(type)?.points.orEmpty().map(PointF::y)
                        mapOf(
                            camera to Face(
                                mouthOpen = mouthOpening(ys(FaceContour.UPPER_LIP_BOTTOM), ys(FaceContour.LOWER_LIP_TOP), box.height().toFloat()),
                                leftEyeOpen = found.leftEyeOpenProbability ?: 1f,
                                rightEyeOpen = found.rightEyeOpenProbability ?: 1f,
                                yaw = found.headEulerAngleY,
                                pitch = found.headEulerAngleX,
                                roll = found.headEulerAngleZ,
                                x = box.left / width,
                                y = box.top / height,
                                width = box.width() / width,
                                height = box.height() / height,
                            ),
                        )
                    }
                }
            }
            done()
        }
    }
}
