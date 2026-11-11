package com.opirl.app.engine.flv

internal fun findStartCode(data: ByteArray, from: Int, limit: Int): Int {
    var i = from
    val last = limit - 3
    while (i <= last) {
        if ((data[i + 2].toInt() and 0xFF) > 1) {
            i += 3
        } else if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
            return i
        } else {
            i++
        }
    }
    return -1
}

internal inline fun forEachNalu(
    data: ByteArray,
    offset: Int,
    length: Int,
    block: (start: Int, end: Int) -> Unit,
) {
    val limit = offset + length
    var codeAt = findStartCode(data, offset, limit)
    while (codeAt >= 0) {
        val start = codeAt + 3
        val next = findStartCode(data, start, limit)
        var end = if (next < 0) limit else next
        while (end > start && data[end - 1].toInt() == 0) {
            end--
        }
        if (end > start) {
            block(start, end)
        }
        codeAt = next
    }
}

internal fun splitNalus(data: ByteArray): List<ByteArray> {
    val result = ArrayList<ByteArray>()
    forEachNalu(data, 0, data.size) { start, end -> result.add(data.copyOfRange(start, end)) }
    return result
}

internal fun h264NaluType(header: Byte): Int = header.toInt() and 0x1F

internal fun hevcNaluType(header: Byte): Int = (header.toInt() ushr 1) and 0x3F

internal fun unescapeRbsp(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
    val out = ByteArray(length)
    var size = 0
    var zeros = 0
    for (i in offset until offset + length) {
        val value = data[i].toInt() and 0xFF
        if (zeros >= 2 && value == 3) {
            zeros = 0
            continue
        }
        out[size++] = value.toByte()
        zeros = if (value == 0) zeros + 1 else 0
    }
    return out.copyOf(size)
}

internal fun annexBToLengthPrefixed(source: ByteArray, offset: Int, length: Int, out: ByteWriter) {
    forEachNalu(source, offset, length) { start, end ->
        out.u32(end - start)
        out.put(source, start, end - start)
    }
}

internal const val H264_SPS = 7
internal const val H264_PPS = 8
internal const val H264_SLICE_IDR = 5
internal const val HEVC_VPS = 32
internal const val HEVC_SPS = 33
internal const val HEVC_PPS = 34

internal fun annexBParameterSets(config: ByteArray, wanted: IntArray, typeOf: (Byte) -> Int): ByteArray {
    val out = ByteWriter(config.size + 16)
    forEachNalu(config, 0, config.size) { start, end ->
        if (typeOf(config[start]) in wanted) {
            out.u32(1)
            out.put(config, start, end - start)
        }
    }
    return out.toByteArray()
}

internal inline fun leadingNaluTypes(data: ByteArray, lastVclType: Int, typeOf: (Byte) -> Int): Long {
    var seen = 0L
    forEachNalu(data, 0, data.size) { start, _ ->
        val type = typeOf(data[start])
        seen = seen or (1L shl type)
        if (type <= lastVclType) {
            return seen
        }
    }
    return seen
}

internal fun typeMask(vararg types: Int): Long = types.fold(0L) { mask, type -> mask or (1L shl type) }
