package com.opirl.app.effects

import java.nio.ByteBuffer
import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

class LutCube(val size: Int, val rgb: ByteBuffer)

private const val MAX_LUT_SIZE = 64

private fun makeCube(size: Int, entry: (Int, Int, Int, FloatArray) -> Unit): LutCube {
    val target = min(size, MAX_LUT_SIZE)
    val rgb = ByteBuffer.allocateDirect(target * target * target * 3)
    val low = FloatArray(3)
    val high = FloatArray(3)
    val value = FloatArray(3)
    val corners = FloatArray(12)
    for (blue in 0 until target) {
        for (green in 0 until target) {
            for (red in 0 until target) {
                if (target == size) {
                    entry(red, green, blue, value)
                } else {
                    val scale = (size - 1f) / (target - 1)
                    val x = red * scale
                    val y = green * scale
                    val z = blue * scale
                    val x0 = floor(x).toInt()
                    val y0 = floor(y).toInt()
                    val z0 = floor(z).toInt()
                    val x1 = min(x0 + 1, size - 1)
                    val y1 = min(y0 + 1, size - 1)
                    val z1 = min(z0 + 1, size - 1)
                    var corner = 0
                    for (cz in intArrayOf(z0, z1)) {
                        for (cy in intArrayOf(y0, y1)) {
                            entry(x0, cy, cz, low)
                            entry(x1, cy, cz, high)
                            for (channel in 0..2) corners[corner * 3 + channel] = low[channel] + (high[channel] - low[channel]) * (x - x0)
                            corner++
                        }
                    }
                    for (channel in 0..2) {
                        val near = corners[channel] + (corners[3 + channel] - corners[channel]) * (y - y0)
                        val far = corners[6 + channel] + (corners[9 + channel] - corners[6 + channel]) * (y - y0)
                        value[channel] = near + (far - near) * (z - z0)
                    }
                }
                for (channel in 0..2) rgb.put((value[channel].coerceIn(0f, 1f) * 255).roundToInt().toByte())
            }
        }
    }
    rgb.position(0)
    return LutCube(target, rgb)
}

fun parseCubeLut(text: String): LutCube {
    var size = 0
    val values = ArrayList<Float>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"))
        when {
            parts[0] == "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("Size missing")
            parts[0] == "LUT_1D_SIZE" -> throw IllegalArgumentException("One dimensional LUT not supported")
            parts[0].first().isLetter() -> Unit
            else -> {
                require(parts.size == 3) { "Invalid data point $line" }
                parts.forEach { values += it.toFloatOrNull() ?: throw IllegalArgumentException("Invalid data point $line") }
            }
        }
    }
    require(size in 2..256) { if (size == 0) "Size missing" else "Size $size too big" }
    require(values.size == size * size * size * 3) { "Wrong number of data points ${values.size / 3}" }
    return makeCube(size) { red, green, blue, out ->
        val index = ((blue * size + green) * size + red) * 3
        out[0] = values[index]
        out[1] = values[index + 1]
        out[2] = values[index + 2]
    }
}

fun imageLut(width: Int, height: Int, pixel: (Int, Int) -> Int): LutCube {
    val size = cbrt((width.toDouble() * height)).roundToInt()
    require(size > 1 && width % size == 0 && height % size == 0 && size * size * size == width * height) {
        "LUT image is not a cube"
    }
    val columns = width / size
    return makeCube(size) { red, green, blue, out ->
        val color = pixel(blue % columns * size + red, blue / columns * size + green)
        out[0] = (color shr 16 and 0xFF) / 255f
        out[1] = (color shr 8 and 0xFF) / 255f
        out[2] = (color and 0xFF) / 255f
    }
}
