package com.opirl.app.widgets.pngtuber

import java.util.Base64
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

const val COSTUMES = 10
private const val MAX_LEVEL = 8

class PngTuberPart(
    val width: Int,
    val height: Int,
    val costumes: List<Boolean>,
    val blink: Int,
    val talk: Int,
    val z: Int,
)

class PngTuberFile(val parts: List<PngTuberPart>, val images: List<String>)

class PngTuberExtent(val width: Int, val height: Int)

data class PngTuberPose(val mouthOpen: Boolean, val eyesOpen: Boolean)

class PngTuberVariants(val visible: List<List<Int>>, val byPose: List<Int>)

private val POSES = listOf(
    PngTuberPose(mouthOpen = false, eyesOpen = true),
    PngTuberPose(mouthOpen = true, eyesOpen = true),
    PngTuberPose(mouthOpen = false, eyesOpen = false),
    PngTuberPose(mouthOpen = true, eyesOpen = false),
)

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

fun pngSize(base64: String): Pair<Int, Int>? {
    if (base64.length < 32) return null
    val head = runCatching { Base64.getDecoder().decode(base64.substring(0, 32)) }.getOrNull() ?: return null
    if (!head.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
    val width = bigEndian(head, 16)
    val height = bigEndian(head, 20)
    return if (width > 0 && height > 0) width to height else null
}

private fun bigEndian(bytes: ByteArray, offset: Int): Int =
    (0 until 4).fold(0) { value, index -> (value shl 8) or (bytes[offset + index].toInt() and 0xFF) }

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Int = (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0

private fun costumeFlags(text: String): List<Boolean>? {
    val flags = runCatching { Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.int } }.getOrNull()
    return flags?.takeIf { it.size == COSTUMES }?.map { it == 1 }
}

fun parsePngTuberFile(root: JsonElement): PngTuberFile {
    val parts = ArrayList<PngTuberPart>()
    val images = ArrayList<String>()
    val entries = (root as? JsonObject)?.entries.orEmpty().sortedBy { it.key.toIntOrNull() ?: 0 }
    for ((_, entry) in entries) {
        val image = entry as? JsonObject ?: continue
        val data = image.string("imageData") ?: continue
        val (width, height) = pngSize(data) ?: continue
        val costumes = image.string("costumeLayers")?.let(::costumeFlags) ?: continue
        parts += PngTuberPart(width, height, costumes, image.number("showBlink"), image.number("showTalk"), image.number("zindex"))
        images += data
    }
    return PngTuberFile(parts, images)
}

fun PngTuberPart.visible(costume: Int, pose: PngTuberPose): Boolean =
    costumes.getOrNull(costume - 1) == true &&
        when (blink) {
            1 -> pose.eyesOpen
            2 -> !pose.eyesOpen
            else -> true
        } &&
        when (talk) {
            1 -> !pose.mouthOpen
            2 -> pose.mouthOpen
            else -> true
        }

fun visibleParts(parts: List<PngTuberPart>, costume: Int, pose: PngTuberPose): List<Int> =
    parts.indices.filter { parts[it].visible(costume, pose) }.sortedBy { parts[it].z }

fun poseVariants(parts: List<PngTuberPart>, costume: Int): PngTuberVariants {
    val all = POSES.map { visibleParts(parts, costume, it) }
    val distinct = all.distinct()
    return PngTuberVariants(distinct, all.map { distinct.indexOf(it) })
}

fun variantOf(variants: PngTuberVariants, pose: PngTuberPose): Int = variants.byPose[POSES.indexOf(pose)]

fun extentOf(parts: List<PngTuberPart>, indices: List<Int>): PngTuberExtent? =
    if (indices.isEmpty()) null else PngTuberExtent(indices.maxOf { parts[it].width }, indices.maxOf { parts[it].height })

fun fitScale(sizePercent: Int, extent: PngTuberExtent, width: Int, height: Int): Float {
    val share = sizePercent / 100f
    return min(share * width / extent.width, share * height / extent.height)
}

fun neededScale(parts: List<PngTuberPart>, variants: PngTuberVariants, sizePercent: Int, width: Int, height: Int): Float =
    variants.visible.mapNotNull { extentOf(parts, it) }.maxOfOrNull { fitScale(sizePercent, it, width, height) }?.coerceAtMost(1f) ?: 1f

fun decodeLevel(needed: Float): Int =
    floor(-2 * ln(needed.coerceIn(0.0001f, 1f)) / ln(2f)).toInt().coerceIn(0, MAX_LEVEL)

fun levelScale(level: Int): Float = 2f.pow(-level / 2f)

fun compositeScale(level: Int, extent: PngTuberExtent, width: Int, height: Int): Float =
    minOf(levelScale(level), width / extent.width.toFloat(), height / extent.height.toFloat())

fun isMouthOpen(mouthOpen: Float, sensitivity: Float): Boolean = mouthOpen * sensitivity > 0.2f

fun isEyeOpen(eyeOpen: Float, sensitivity: Float): Boolean = eyeOpen * sensitivity > 0.4f
