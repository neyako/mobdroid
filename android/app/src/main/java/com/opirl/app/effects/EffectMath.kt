package com.opirl.app.effects

import com.opirl.app.settings.Comparison
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

fun pixellateCell(width: Int, height: Int, strength: Float): Float {
    val longest = max(width, height).toFloat()
    val cell = 20 * (longest / 1920) * (1 + 5 * strength)
    return longest / (longest / cell).toInt().coerceAtLeast(1)
}

fun horizonTilt(gravityX: Float, gravityY: Float, displayRotation: Int): Float? {
    if (hypot(gravityX, gravityY) < 3f) return null
    var tilt = Math.toDegrees(atan2(gravityX, gravityY).toDouble()).toFloat() - displayRotation
    while (tilt > 180) tilt -= 360
    while (tilt < -180) tilt += 360
    return Math.toRadians(tilt.toDouble()).toFloat()
}

fun horizonStep(current: Float, target: Float): Float {
    val weight = if (abs(target) < 0.1f) 2 * abs(target) else 0.2f
    return weight * target + (1 - weight) * current
}

fun horizonScale(width: Float, height: Float, angle: Float): Float {
    val boxWidth = abs(width * cos(angle)) + abs(height * sin(angle))
    val boxHeight = abs(width * sin(angle)) + abs(height * cos(angle))
    return max(boxWidth, boxHeight * width / height) / width
}

fun textMask(texts: FloatArray, width: Int, height: Int, out: FloatArray): Int {
    val slots = out.size / 4
    for (index in 0 until texts.size / 4) {
        val pad = (texts[index * 4 + 3] - texts[index * 4 + 1]) * 0.2f * height
        val left = texts[index * 4] * width - pad * 3
        val bottom = (1 - texts[index * 4 + 3]) * height - pad
        val right = texts[index * 4 + 2] * width + pad * 3
        val top = (1 - texts[index * 4 + 1]) * height + pad
        val slot = min(index, slots - 1) * 4
        val merge = index >= slots
        out[slot] = if (merge) min(out[slot], left) else left
        out[slot + 1] = if (merge) min(out[slot + 1], bottom) else bottom
        out[slot + 2] = if (merge) max(out[slot + 2], right) else right
        out[slot + 3] = if (merge) max(out[slot + 3], top) else top
    }
    return min(texts.size / 4, slots)
}

data class ChromaKey(val red: Float, val green: Float, val blue: Float, val threshold: Float)

private fun saturated(hue: Float): FloatArray {
    val sector = hue * 6
    val secondary = 1 - abs(sector % 2 - 1)
    return when (sector.toInt()) {
        0 -> floatArrayOf(1f, secondary, 0f)
        1 -> floatArrayOf(secondary, 1f, 0f)
        2 -> floatArrayOf(0f, 1f, secondary)
        3 -> floatArrayOf(0f, secondary, 1f)
        4 -> floatArrayOf(secondary, 0f, 1f)
        else -> floatArrayOf(1f, 0f, secondary)
    }
}

private fun chroma(color: FloatArray): Pair<Float, Float> {
    val luma = 0.2989f * color[0] + 0.5866f * color[1] + 0.1145f * color[2]
    return 0.7132f * (color[0] - luma) to 0.5647f * (color[2] - luma)
}

fun chromaKey(fromHue: Int, toHue: Int): ChromaKey {
    val from = (fromHue % 360) / 360f
    val to = (toHue % 360) / 360f
    val span = if (to < from) to - from + 1 else to - from
    val color = saturated((from + span / 2) % 1f)
    val key = chroma(color)
    val edge = chroma(saturated(from))
    val threshold = min(hypot(key.first - edge.first, key.second - edge.second), hypot(key.first, key.second) * 0.8f)
    return ChromaKey(color[0], color[1], color[2], threshold)
}

private fun number(text: String): Double? =
    text.trim().takeWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }.toDoubleOrNull()

fun Comparison.evaluate(value: String, other: String): Boolean {
    if (this == Comparison.CONTAINS) return value.contains(other, ignoreCase = true)
    val a = number(value)
    val b = number(other)
    val order = if (a != null && b != null) a.compareTo(b) else value.compareTo(other, ignoreCase = true)
    return when (this) {
        Comparison.EQUAL -> order == 0
        Comparison.NOT_EQUAL -> order != 0
        Comparison.LESS -> order < 0
        Comparison.LESS_EQUAL -> order <= 0
        Comparison.GREATER -> order > 0
        Comparison.GREATER_EQUAL -> order >= 0
        Comparison.CONTAINS -> false
    }
}
