package com.opirl.app.widgets.pngtuber

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.math.roundToInt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromStream

class PngTuberVariant(val extent: PngTuberExtent, val bitmap: Bitmap)

class PngTuberModel(
    val name: String,
    val costume: Int,
    val level: Int,
    val parts: List<PngTuberPart>,
    val variants: PngTuberVariants,
    val shown: List<PngTuberVariant?>,
)

private fun scaled(size: Int, scale: Float): Int = (size * scale).roundToInt().coerceAtLeast(1)

@OptIn(ExperimentalSerializationApi::class)
fun loadPngTuber(file: File, name: String, costume: Int, sizePercent: Int, width: Int, height: Int): PngTuberModel? {
    val parsed = file.inputStream().buffered().use { parsePngTuberFile(Json.decodeFromStream<JsonElement>(it)) }
    val parts = parsed.parts
    if (parts.isEmpty()) return null
    val variants = poseVariants(parts, costume)
    val level = decodeLevel(neededScale(parts, variants, sizePercent, width, height))
    val scale = levelScale(level)
    val shown = variants.visible.map { indices ->
        extentOf(parts, indices)?.let {
            val fitted = compositeScale(level, it, width, height)
            PngTuberVariant(it, Bitmap.createBitmap(scaled(it.width, fitted), scaled(it.height, fitted), Bitmap.Config.ARGB_8888))
        }
    }
    val canvases = shown.map { variant -> variant?.let { Canvas(it.bitmap) } }
    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    for (index in parts.indices.sortedBy { parts[it].z }) {
        val users = variants.visible.indices.filter { index in variants.visible[it] }
        if (users.isEmpty()) continue
        val part = parts[index]
        val layer = decodePng(parsed.images[index], scaled(part.width, scale), scaled(part.height, scale))
        for (user in users) {
            val variant = shown[user] ?: continue
            val scaleX = variant.bitmap.width / variant.extent.width.toFloat()
            val scaleY = variant.bitmap.height / variant.extent.height.toFloat()
            val bottom = variant.bitmap.height.toFloat()
            canvases[user]?.drawBitmap(layer, null, RectF(0f, bottom - part.height * scaleY, part.width * scaleX, bottom), paint)
        }
        layer.recycle()
    }
    return PngTuberModel(name, costume, level, parts, variants, shown)
}

private fun decodePng(base64: String, width: Int, height: Int): Bitmap {
    val source = ImageDecoder.createSource(ByteBuffer.wrap(Base64.getDecoder().decode(base64)))
    return ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        decoder.setTargetSize(width, height)
    }
}

fun mirrored(bitmap: Bitmap): Bitmap =
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { preScale(-1f, 1f) }, false)
