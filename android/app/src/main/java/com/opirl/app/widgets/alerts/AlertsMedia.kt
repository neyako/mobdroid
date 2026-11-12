package com.opirl.app.widgets.alerts

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.Log
import com.opirl.app.widgets.WidgetRuntime
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

private const val TAG = "Alerts"
private const val FRAME_BUDGET_BYTES = 48L * 1024 * 1024
private const val STATIC_LOOP_MS = 1000
private const val REFERENCE_SHORT_SIDE = 1080f

internal class PreparedAlert(
    val plan: AlertPlan,
    val frames: List<Bitmap>,
    val timeline: AlertTimeline,
    val width: Int,
    val height: Int,
    val sound: AlertSound?,
)

internal fun alertUnit(width: Int, height: Int): Float = minOf(width, height) / REFERENCE_SHORT_SIDE

internal fun prepareAlert(
    runtime: WidgetRuntime,
    settings: AlertsSettings,
    plan: AlertPlan,
    width: Int,
    height: Int,
): PreparedAlert {
    val loops = plan.alert.loops.coerceIn(1, 10)
    val sound = alertSound(runtime, settings, plan.alert.sound)
    val bytes = runCatching { imageBytes(runtime, settings, plan.alert.image) }
        .onFailure { Log.w(TAG, "Alert image failed", it) }
        .getOrNull()
    val unit = alertUnit(width, height)
    val decoded = bytes?.let { runCatching { decodeFrames(it, unit, width, height) }.onFailure { e -> Log.w(TAG, "Alert frames failed", e) }.getOrNull() }
    if (decoded == null) return PreparedAlert(plan, emptyList(), AlertTimeline(IntArray(0), loops), 0, 0, sound)
    return PreparedAlert(plan, decoded.frames, AlertTimeline(decoded.durations, loops), decoded.width, decoded.height, sound)
}

internal class DecodedFrames(val frames: List<Bitmap>, val durations: IntArray, val width: Int, val height: Int)

internal fun fitScale(naturalWidth: Int, naturalHeight: Int, unit: Float, maxWidth: Int, maxHeight: Int): Float =
    minOf(1f, maxWidth / (naturalWidth * unit), maxHeight / (naturalHeight * unit)) * unit

internal fun decodeFrames(bytes: ByteArray, unit: Float, maxWidth: Int, maxHeight: Int): DecodedFrames? {
    if (isGif(bytes)) {
        val reader = GifReader(bytes)
        if (reader.valid) return decodeGifFrames(reader, unit, maxWidth, maxHeight)
    }
    return decodeStill(bytes, unit, maxWidth, maxHeight)
}

private fun decodeGifFrames(reader: GifReader, unit: Float, maxWidth: Int, maxHeight: Int): DecodedFrames? {
    val scale = fitScale(reader.width, reader.height, unit, maxWidth, maxHeight)
    val displayWidth = (reader.width * scale).roundToInt().coerceAtLeast(1)
    val displayHeight = (reader.height * scale).roundToInt().coerceAtLeast(1)
    val bitmapWidth = minOf(displayWidth, reader.width)
    val bitmapHeight = minOf(displayHeight, reader.height)
    val maxFrames = (FRAME_BUDGET_BYTES / (bitmapWidth.toLong() * bitmapHeight * 4)).toInt().coerceAtLeast(1)
    val frames = ArrayList<Bitmap>()
    val durations = ArrayList<Int>()
    reader.forEachFrame(maxFrames) { pixels, delayMs ->
        val full = Bitmap.createBitmap(pixels, reader.width, reader.height, Bitmap.Config.ARGB_8888)
        frames += if (bitmapWidth == reader.width && bitmapHeight == reader.height) {
            full
        } else {
            Bitmap.createScaledBitmap(full, bitmapWidth, bitmapHeight, true).also { full.recycle() }
        }
        durations += delayMs
    }
    if (frames.isEmpty()) return null
    if (frames.size == 1) durations[0] = STATIC_LOOP_MS
    return DecodedFrames(frames, durations.toIntArray(), displayWidth, displayHeight)
}

private fun decodeStill(bytes: ByteArray, unit: Float, maxWidth: Int, maxHeight: Int): DecodedFrames {
    var displayWidth = 0
    var displayHeight = 0
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
        val naturalWidth = info.size.width
        val naturalHeight = info.size.height
        val scale = fitScale(naturalWidth, naturalHeight, unit, maxWidth, maxHeight)
        displayWidth = (naturalWidth * scale).roundToInt().coerceAtLeast(1)
        displayHeight = (naturalHeight * scale).roundToInt().coerceAtLeast(1)
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (displayWidth < naturalWidth) decoder.setTargetSize(displayWidth, displayHeight)
    }
    return DecodedFrames(listOf(bitmap), intArrayOf(STATIC_LOOP_MS), displayWidth, displayHeight)
}

private fun imageBytes(runtime: WidgetRuntime, settings: AlertsSettings, id: String): ByteArray? {
    if (BUNDLED_IMAGES.any { it.id == id }) {
        return runtime.context.assets.open("alerts/images/$id.gif").use { it.readBytes() }
    }
    val name = settings.imageFile(id) ?: return null
    return File(runtime.files, name).takeIf { it.isFile }?.readBytes()
}

internal fun alertSound(runtime: WidgetRuntime, settings: AlertsSettings, id: String): AlertSound? {
    if (BUNDLED_SOUNDS.any { it.id == id }) return AlertSound("alerts/sounds/$id.mp3", null)
    val name = settings.soundFile(id) ?: return null
    return File(runtime.files, name).takeIf { it.isFile }?.let { AlertSound(null, it) }
}
