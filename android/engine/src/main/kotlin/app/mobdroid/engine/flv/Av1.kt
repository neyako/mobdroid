package app.mobdroid.engine.flv

internal const val OBU_SEQUENCE_HEADER = 1
internal const val OBU_TEMPORAL_DELIMITER = 2

internal inline fun forEachObu(
    data: ByteArray,
    offset: Int,
    length: Int,
    block: (type: Int, start: Int, payloadStart: Int, end: Int) -> Unit,
) {
    val limit = offset + length
    var i = offset
    while (i < limit) {
        val header = data[i].toInt()
        val type = (header ushr 3) and 0xF
        val headerSize = 1 + ((header ushr 2) and 1)
        var payloadStart = i + headerSize
        var end = limit
        if ((header ushr 1) and 1 == 1) {
            var size = 0L
            var shift = 0
            var byte: Int
            do {
                if (payloadStart >= limit || shift > 49) {
                    return
                }
                byte = data[payloadStart++].toInt() and 0xFF
                size = size or ((byte and 0x7F).toLong() shl shift)
                shift += 7
            } while (byte and 0x80 != 0)
            end = minOf(payloadStart + size, limit.toLong()).toInt()
        }
        block(type, i, payloadStart, end)
        i = end
    }
}

internal fun writeObuWithSizeField(
    out: ByteWriter,
    data: ByteArray,
    start: Int,
    payloadStart: Int,
    end: Int,
) {
    val header = data[start].toInt()
    if ((header ushr 1) and 1 == 1) {
        out.put(data, start, end - start)
        return
    }
    out.u8(header or 2)
    if (payloadStart - start == 2) {
        out.u8(data[start + 1].toInt())
    }
    var size = end - payloadStart
    while (size >= 0x80) {
        out.u8((size and 0x7F) or 0x80)
        size = size ushr 7
    }
    out.u8(size)
    out.put(data, payloadStart, end - payloadStart)
}

internal class Av1SequenceHeader(
    val profile: Int,
    val levelIdx: Int,
    val tier: Int,
    val highBitdepth: Boolean,
    val twelveBit: Boolean,
    val monochrome: Boolean,
    val subsamplingX: Int,
    val subsamplingY: Int,
    val chromaSamplePosition: Int,
    val colorDescriptionPresent: Boolean,
    val colorPrimaries: Int,
    val transferCharacteristics: Int,
) {
    val hdrWcgIdc: Int
        get() {
            val hdr = transferCharacteristics == 16 || transferCharacteristics == 18
            val wcg = colorPrimaries == 9 || colorPrimaries == 11 || colorPrimaries == 12
            return when {
                !colorDescriptionPresent -> 3
                hdr -> 2
                wcg -> 1
                else -> 0
            }
        }

    companion object {
        fun parse(data: ByteArray, payloadStart: Int, end: Int): Av1SequenceHeader {
            val reader = BitReader(data, payloadStart, end)
            val profile = reader.bits(3)
            reader.skip(1)
            val reduced = reader.flag()
            var levelIdx0: Int
            var tier0 = 0
            if (reduced) {
                levelIdx0 = reader.bits(5)
            } else {
                val timingInfoPresent = reader.flag()
                var decoderModelInfoPresent = false
                var bufferDelayLength = 0
                if (timingInfoPresent) {
                    reader.skip(64)
                    if (reader.flag()) {
                        reader.uvlc()
                    }
                    decoderModelInfoPresent = reader.flag()
                    if (decoderModelInfoPresent) {
                        bufferDelayLength = reader.bits(5) + 1
                        reader.skip(32)
                        reader.skip(10)
                    }
                }
                val initialDisplayDelayPresent = reader.flag()
                val operatingPoints = reader.bits(5) + 1
                levelIdx0 = 0
                for (i in 0 until operatingPoints) {
                    reader.skip(12)
                    val level = reader.bits(5)
                    val tier = if (level > 7) reader.bit() else 0
                    if (i == 0) {
                        levelIdx0 = level
                        tier0 = tier
                    }
                    if (decoderModelInfoPresent && reader.flag()) {
                        reader.skip(2 * bufferDelayLength + 1)
                    }
                    if (initialDisplayDelayPresent && reader.flag()) {
                        reader.skip(4)
                    }
                }
            }
            val widthBits = reader.bits(4) + 1
            val heightBits = reader.bits(4) + 1
            reader.skip(widthBits + heightBits)
            if (!reduced && reader.flag()) {
                reader.skip(7)
            }
            reader.skip(3)
            if (!reduced) {
                reader.skip(4)
                val orderHint = reader.flag()
                if (orderHint) {
                    reader.skip(2)
                }
                val screenContentTools = if (reader.flag()) 2 else reader.bit()
                if (screenContentTools > 0) {
                    if (!reader.flag()) {
                        reader.skip(1)
                    }
                }
                if (orderHint) {
                    reader.skip(3)
                }
            }
            reader.skip(3)
            val highBitdepth = reader.flag()
            var twelveBit = false
            if (profile == 2 && highBitdepth) {
                twelveBit = reader.flag()
            }
            val monochrome = if (profile == 1) false else reader.flag()
            val colorDescriptionPresent = reader.flag()
            var primaries = 2
            var transfer = 2
            var matrix = 2
            if (colorDescriptionPresent) {
                primaries = reader.bits(8)
                transfer = reader.bits(8)
                matrix = reader.bits(8)
            }
            var subX = 1
            var subY = 1
            var samplePosition = 0
            if (monochrome) {
                reader.skip(1)
            } else if (primaries == 1 && transfer == 13 && matrix == 0) {
                subX = 0
                subY = 0
            } else {
                reader.skip(1)
                when (profile) {
                    0 -> Unit
                    1 -> {
                        subX = 0
                        subY = 0
                    }
                    else -> {
                        if (twelveBit) {
                            subX = reader.bit()
                            subY = if (subX == 1) reader.bit() else 0
                        } else {
                            subY = 0
                        }
                    }
                }
                if (subX == 1 && subY == 1) {
                    samplePosition = reader.bits(2)
                }
            }
            return Av1SequenceHeader(
                profile,
                levelIdx0,
                tier0,
                highBitdepth,
                twelveBit,
                monochrome,
                subX,
                subY,
                samplePosition,
                colorDescriptionPresent,
                primaries,
                transfer,
            )
        }
    }
}

internal class Av1Config private constructor(
    val record: ByteArray,
    val sequenceHeaderObu: ByteArray?,
    val hdrWcgIdc: Int,
) {
    val descriptor: ByteArray
        get() = byteArrayOf(record[0], record[1], record[2], (hdrWcgIdc shl 6).toByte())

    companion object {
        fun from(config: ByteArray): Av1Config {
            if (config.size >= 4 && (config[0].toInt() and 0xFF) == 0x81) {
                val obu = findSequenceHeader(config, 4, config.size - 4)
                val idc = obu?.let { Av1SequenceHeader.parse(it, headerSize(it), it.size).hdrWcgIdc } ?: 3
                return Av1Config(config.copyOf(), obu, idc)
            }
            val obu = requireNotNull(findSequenceHeader(config, 0, config.size)) {
                "AV1 config has no sequence header"
            }
            val header = Av1SequenceHeader.parse(obu, headerSize(obu), obu.size)
            val record = ByteWriter(obu.size + 4)
            record.u8(0x81)
            record.u8((header.profile shl 5) or header.levelIdx)
            record.u8(
                (header.tier shl 7) or ((if (header.highBitdepth) 1 else 0) shl 6) or
                    ((if (header.twelveBit) 1 else 0) shl 5) or ((if (header.monochrome) 1 else 0) shl 4) or
                    (header.subsamplingX shl 3) or (header.subsamplingY shl 2) or header.chromaSamplePosition,
            )
            record.u8(0)
            record.put(obu)
            return Av1Config(record.toByteArray(), obu, header.hdrWcgIdc)
        }

        private fun headerSize(obu: ByteArray): Int {
            var size = 1 + ((obu[0].toInt() ushr 2) and 1)
            while (obu[size].toInt() and 0x80 != 0) {
                size++
            }
            return size + 1
        }

        private fun findSequenceHeader(data: ByteArray, offset: Int, length: Int): ByteArray? {
            var found: ByteArray? = null
            forEachObu(data, offset, length) { type, start, payloadStart, end ->
                if (type == OBU_SEQUENCE_HEADER && found == null) {
                    val out = ByteWriter(end - start + 2)
                    writeObuWithSizeField(out, data, start, payloadStart, end)
                    found = out.toByteArray()
                }
            }
            return found
        }
    }
}
