package com.opirl.app.widgets.alerts

private const val MAX_CANVAS_PIXELS = 4_000_000
private const val MIN_DELAY_MS = 10
private const val DEFAULT_DELAY_MS = 100
private val INTERLACE_START = intArrayOf(0, 4, 2, 1)
private val INTERLACE_STEP = intArrayOf(8, 8, 4, 2)

fun isGif(bytes: ByteArray): Boolean =
    bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()

class GifReader(private val data: ByteArray) {
    val width: Int
    val height: Int
    val valid: Boolean

    private var position = 0
    private var globalColors: IntArray? = null
    private var canvas = IntArray(0)
    private var saved: IntArray? = null
    private var disposal = 0
    private var previous = Rect(0, 0, 0, 0)
    private var finished = false
    private var disposalNext = 0

    private class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

    init {
        var w = 0
        var h = 0
        var ok = false
        if (isGif(data) && data.size >= 13) {
            w = short(6)
            h = short(8)
            val packed = data[10].toInt() and 0xFF
            position = 13
            if (packed and 0x80 != 0) globalColors = readColors(2 shl (packed and 7))
            ok = w > 0 && h > 0 && w * h <= MAX_CANVAS_PIXELS && position <= data.size
        }
        width = w
        height = h
        valid = ok
        if (ok) canvas = IntArray(w * h)
    }

    fun forEachFrame(maxFrames: Int, action: (pixels: IntArray, delayMs: Int) -> Unit): Int {
        if (!valid) return 0
        var count = 0
        var delayMs = DEFAULT_DELAY_MS
        var transparent = -1
        while (count < maxFrames && !finished && position < data.size) {
            when (data[position++].toInt() and 0xFF) {
                0x21 -> {
                    if (position >= data.size) break
                    val label = data[position++].toInt() and 0xFF
                    if (label == 0xF9 && position + 5 < data.size) {
                        val packed = data[position + 1].toInt() and 0xFF
                        disposalNext = (packed shr 2) and 7
                        val delay = short(position + 2) * 10
                        delayMs = if (delay <= MIN_DELAY_MS) DEFAULT_DELAY_MS else delay
                        transparent = if (packed and 1 != 0) data[position + 4].toInt() and 0xFF else -1
                    }
                    skipBlocks()
                }
                0x2C -> {
                    if (!readFrame(transparent)) {
                        finished = true
                        break
                    }
                    action(canvas, delayMs)
                    count++
                    delayMs = DEFAULT_DELAY_MS
                    transparent = -1
                }
                else -> finished = true
            }
        }
        return count
    }

    private fun readFrame(transparent: Int): Boolean {
        if (position + 9 > data.size) return false
        val x = short(position)
        val y = short(position + 2)
        val w = short(position + 4)
        val h = short(position + 6)
        val packed = data[position + 8].toInt() and 0xFF
        position += 9
        var colors = globalColors
        if (packed and 0x80 != 0) colors = readColors(2 shl (packed and 7))
        val palette = colors ?: return false
        if (position >= data.size) return false
        val minCodeSize = data[position++].toInt() and 0xFF
        if (minCodeSize !in 1..11) return false
        val lzw = readBlocks()
        val indices = ByteArray(w * h)
        lzwDecode(lzw, minCodeSize, indices)
        applyDisposal()
        val thisDisposal = disposalNext
        if (thisDisposal == 3) saved = canvas.copyOf()
        val rows = if (packed and 0x40 != 0) interlacedRows(h) else IntArray(h) { it }
        var source = 0
        for (line in 0 until h) {
            val canvasRow = y + rows[line]
            if (canvasRow in 0 until height) {
                for (column in 0 until w) {
                    val canvasColumn = x + column
                    val index = indices[source + column].toInt() and 0xFF
                    if (canvasColumn in 0 until width && index != transparent && index < palette.size) {
                        canvas[canvasRow * width + canvasColumn] = palette[index]
                    }
                }
            }
            source += w
        }
        previous = Rect(x, y, w, h)
        disposal = thisDisposal
        disposalNext = 0
        return true
    }

    private fun applyDisposal() {
        when (disposal) {
            2 -> for (row in previous.y until minOf(previous.y + previous.height, height)) {
                val from = row * width + previous.x
                val to = row * width + minOf(previous.x + previous.width, width)
                if (from < to) canvas.fill(0, from, to)
            }
            3 -> saved?.let { System.arraycopy(it, 0, canvas, 0, canvas.size) }
        }
        saved = null
    }

    private fun readColors(count: Int): IntArray {
        val colors = IntArray(count)
        for (index in 0 until count) {
            val at = position + index * 3
            if (at + 2 >= data.size) break
            colors[index] = 0xFF000000.toInt() or
                ((data[at].toInt() and 0xFF) shl 16) or
                ((data[at + 1].toInt() and 0xFF) shl 8) or
                (data[at + 2].toInt() and 0xFF)
        }
        position += count * 3
        return colors
    }

    private fun readBlocks(): ByteArray {
        var out = ByteArray(4096)
        var size = 0
        while (position < data.size) {
            val length = data[position++].toInt() and 0xFF
            if (length == 0) break
            val take = minOf(length, data.size - position)
            if (size + take > out.size) out = out.copyOf(maxOf(out.size * 2, size + take))
            System.arraycopy(data, position, out, size, take)
            size += take
            position += length
        }
        return out.copyOf(size)
    }

    private fun skipBlocks() {
        while (position < data.size) {
            val length = data[position++].toInt() and 0xFF
            if (length == 0) break
            position += length
        }
    }

    private fun short(at: Int): Int =
        if (at + 1 < data.size) (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) else 0
}

internal fun interlacedRows(height: Int): IntArray {
    val rows = IntArray(height)
    var next = 0
    for (pass in 0..3) {
        var row = INTERLACE_START[pass]
        while (row < height) {
            rows[next++] = row
            row += INTERLACE_STEP[pass]
        }
    }
    return rows
}

internal fun lzwDecode(data: ByteArray, minCodeSize: Int, out: ByteArray): Int {
    val clear = 1 shl minCodeSize
    val end = clear + 1
    val prefix = IntArray(4096)
    val suffix = ByteArray(4096)
    val stack = ByteArray(4097)
    for (code in 0 until clear) suffix[code] = code.toByte()
    var codeSize = minCodeSize + 1
    var codeMask = (1 shl codeSize) - 1
    var available = clear + 2
    var old = -1
    var first = 0
    var bits = 0
    var bitCount = 0
    var position = 0
    var written = 0
    while (written < out.size) {
        while (bitCount < codeSize) {
            if (position >= data.size) return written
            bits = bits or ((data[position++].toInt() and 0xFF) shl bitCount)
            bitCount += 8
        }
        var code = bits and codeMask
        bits = bits ushr codeSize
        bitCount -= codeSize
        if (code == clear) {
            codeSize = minCodeSize + 1
            codeMask = (1 shl codeSize) - 1
            available = clear + 2
            old = -1
            continue
        }
        if (code == end) break
        if (old == -1) {
            if (code >= clear) break
            out[written++] = suffix[code]
            old = code
            first = code
            continue
        }
        val incoming = code
        var top = 0
        if (code >= available) {
            if (code > available) break
            stack[top++] = first.toByte()
            code = old
        }
        while (code >= clear) {
            stack[top++] = suffix[code]
            code = prefix[code]
        }
        first = suffix[code].toInt() and 0xFF
        stack[top++] = first.toByte()
        if (available < 4096) {
            prefix[available] = old
            suffix[available] = first.toByte()
            available++
            if (available and codeMask == 0 && available < 4096) {
                codeSize++
                codeMask = (1 shl codeSize) - 1
            }
        }
        old = incoming
        while (top > 0 && written < out.size) out[written++] = stack[--top]
    }
    return written
}
