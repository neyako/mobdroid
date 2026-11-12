package com.opirl.app.effects

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer

class DetectedFace(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val eyes: FloatArray?,
    val mouth: FloatArray?,
)

class Detector {
    private val thread = HandlerThread("Detector", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(thread.looper)
    private var faceDetector: FaceDetector? = null
    private var faceContours = false
    private var textRecognizer: TextRecognizer? = null
    private var bitmap: Bitmap? = null
    private var lastFaces = 0L

    @Volatile
    var busy = false
        private set

    @Volatile
    var faces: List<DetectedFace> = emptyList()
        private set

    @Volatile
    var texts = NO_TEXTS
        private set

    fun submit(pixels: ByteBuffer, width: Int, height: Int, wantFaces: Boolean, contours: Boolean, wantText: Boolean, keepText: Boolean) {
        busy = true
        handler.post {
            try {
                detect(pixels, width, height, wantFaces, contours, wantText, keepText)
            } catch (e: Exception) {
                Log.w(TAG, "Detection failed", e)
            } finally {
                busy = false
            }
        }
    }

    fun release() {
        handler.post {
            faceDetector?.close()
            textRecognizer?.close()
            bitmap?.recycle()
        }
        thread.quitSafely()
    }

    private fun detect(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        wantFaces: Boolean,
        contours: Boolean,
        wantText: Boolean,
        keepText: Boolean,
    ) {
        val frame = bitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                bitmap?.recycle()
                bitmap = it
            }
        pixels.position(0)
        frame.copyPixelsFromBuffer(pixels)
        val image = InputImage.fromBitmap(frame, 0)
        if (wantFaces) {
            val found = Tasks.await(faceDetector(contours).process(image)).map { face ->
                val box = face.boundingBox
                val left = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val right = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                DetectedFace(
                    box.left.toFloat() / width,
                    box.top.toFloat() / height,
                    box.width().toFloat() / width,
                    box.height().toFloat() / height,
                    if (left != null && right != null) eyes(left, right, width, height) else null,
                    mouth(
                        face.getContour(FaceContour.UPPER_LIP_BOTTOM)?.points.orEmpty() +
                            face.getContour(FaceContour.LOWER_LIP_TOP)?.points.orEmpty(),
                        width,
                        height,
                    ),
                )
            }
            val now = System.nanoTime()
            if (found.isNotEmpty() || now - lastFaces > FACE_HOLD_NANOS) {
                faces = found
                if (found.isNotEmpty()) lastFaces = now
            }
        } else {
            faces = emptyList()
        }
        if (wantText) {
            val recognizer = textRecognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { textRecognizer = it }
            val lines = Tasks.await(recognizer.process(image)).textBlocks.flatMap { it.lines }.mapNotNull { it.boundingBox }
            val rects = FloatArray(lines.size * 4)
            lines.forEachIndexed { index, box ->
                rects[index * 4] = box.left.toFloat() / width
                rects[index * 4 + 1] = box.top.toFloat() / height
                rects[index * 4 + 2] = box.right.toFloat() / width
                rects[index * 4 + 3] = box.bottom.toFloat() / height
            }
            texts = rects
        } else if (!keepText) {
            texts = NO_TEXTS
        }
    }

    private fun faceDetector(contours: Boolean): FaceDetector {
        faceDetector?.takeIf { faceContours == contours }?.let { return it }
        faceDetector?.close()
        faceContours = contours
        return FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setContourMode(if (contours) FaceDetectorOptions.CONTOUR_MODE_ALL else FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(0.05f)
                .build(),
        ).also { faceDetector = it }
    }

    private fun eyes(first: PointF, second: PointF, width: Int, height: Int): FloatArray {
        val left = if (first.x <= second.x) first else second
        val right = if (first.x <= second.x) second else first
        return floatArrayOf(left.x / width, left.y / height, right.x / width, right.y / height)
    }

    private fun mouth(points: List<PointF>, width: Int, height: Int): FloatArray? {
        if (points.isEmpty()) return null
        return floatArrayOf(
            points.minOf { it.x } / width,
            points.minOf { it.y } / height,
            points.maxOf { it.x } / width,
            points.maxOf { it.y } / height,
        )
    }

    private companion object {
        const val TAG = "Detector"
        const val FACE_HOLD_NANOS = 400_000_000L
        val NO_TEXTS = FloatArray(0)
    }
}
