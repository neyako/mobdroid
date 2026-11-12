package com.opirl.app.control

const val ELGATO_VENDOR_ID = 0x0fd9
private const val REPORT_LENGTH = 1024

enum class StreamDeckKind(
    val productIds: Set<Int>,
    val keys: Int,
    val columns: Int,
    val pixels: Int,
    val jpeg: Boolean,
) {
    MINI(setOf(0x0063, 0x0090), 6, 3, 80, false),
    CLASSIC(setOf(0x006d, 0x0080, 0x00a5), 15, 5, 72, true),
    XL(setOf(0x006c, 0x008f), 32, 8, 96, true),
    ;

    val featureLength: Int get() = if (jpeg) 32 else 17
    val headerLength: Int get() = if (jpeg) 8 else 16
    val stateOffset: Int get() = if (jpeg) 4 else 1

    companion object {
        fun of(vendorId: Int, productId: Int): StreamDeckKind? =
            if (vendorId == ELGATO_VENDOR_ID) entries.firstOrNull { productId in it.productIds } else null
    }
}

private fun feature(kind: StreamDeckKind, vararg bytes: Int) = ByteArray(kind.featureLength).also { report ->
    bytes.forEachIndexed { index, value -> report[index] = value.toByte() }
}

fun streamDeckReset(kind: StreamDeckKind): ByteArray = if (kind.jpeg) feature(kind, 0x03, 0x02) else feature(kind, 0x0B, 0x63)

fun streamDeckBrightness(kind: StreamDeckKind, percent: Int): ByteArray {
    val value = percent.coerceIn(0, 100)
    return if (kind.jpeg) feature(kind, 0x03, 0x08, value) else feature(kind, 0x05, 0x55, 0xaa, 0xd1, 0x01, value)
}

fun streamDeckImageReports(kind: StreamDeckKind, key: Int, image: ByteArray): List<ByteArray> {
    val payload = REPORT_LENGTH - kind.headerLength
    val pages = maxOf((image.size + payload - 1) / payload, 1)
    return List(pages) { page ->
        val offset = page * payload
        val length = minOf(image.size - offset, payload)
        val last = if (page == pages - 1) 1 else 0
        ByteArray(REPORT_LENGTH).also { report ->
            report[0] = 0x02
            if (kind.jpeg) {
                report[1] = 0x07
                report[2] = key.toByte()
                report[3] = last.toByte()
                report[4] = length.toByte()
                report[5] = (length shr 8).toByte()
                report[6] = page.toByte()
                report[7] = (page shr 8).toByte()
            } else {
                report[1] = 0x01
                report[2] = page.toByte()
                report[4] = last.toByte()
                report[5] = (key + 1).toByte()
            }
            image.copyInto(report, kind.headerLength, offset, offset + length)
        }
    }
}

fun streamDeckKeyStates(kind: StreamDeckKind, report: ByteArray, length: Int): BooleanArray? {
    if (length < kind.stateOffset + kind.keys || report[0].toInt() != 0x01) return null
    if (kind.jpeg && report[1].toInt() != 0x00) return null
    return BooleanArray(kind.keys) { report[kind.stateOffset + it].toInt() != 0 }
}

fun streamDeckBmp(size: Int, argb: IntArray): ByteArray {
    val row = (size * 3 + 3) / 4 * 4
    val data = row * size
    val file = ByteArray(54 + data)
    fun int(offset: Int, value: Int) {
        for (index in 0 until 4) file[offset + index] = (value shr (8 * index)).toByte()
    }
    file[0] = 'B'.code.toByte()
    file[1] = 'M'.code.toByte()
    int(2, file.size)
    int(10, 54)
    int(14, 40)
    int(18, size)
    int(22, size)
    file[26] = 1
    file[28] = 24
    int(34, data)
    int(38, 3780)
    int(42, 3780)
    for (y in 0 until size) {
        val source = (size - 1 - y) * size
        val target = 54 + y * row
        for (x in 0 until size) {
            val pixel = argb[source + x]
            file[target + x * 3] = pixel.toByte()
            file[target + x * 3 + 1] = (pixel shr 8).toByte()
            file[target + x * 3 + 2] = (pixel shr 16).toByte()
        }
    }
    return file
}

fun streamDeckMiniPixels(size: Int, argb: IntArray): IntArray = IntArray(size * size) { index ->
    val x = index % size
    val y = index / size
    argb[x * size + y]
}
