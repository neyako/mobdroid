package app.mobdroid.engine.flv

internal object AvcConfig {
    private val spsWithChromaFormat = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    fun record(annexBConfig: ByteArray): ByteArray {
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        forEachNalu(annexBConfig, 0, annexBConfig.size) { start, end ->
            when (h264NaluType(annexBConfig[start])) {
                H264_SPS -> sps.add(annexBConfig.copyOfRange(start, end))
                H264_PPS -> pps.add(annexBConfig.copyOfRange(start, end))
            }
        }
        require(sps.isNotEmpty() && pps.isNotEmpty()) { "H264 config needs an SPS and a PPS" }
        val first = sps[0]
        require(first.size >= 4) { "H264 SPS too short" }
        val profile = first[1].toInt() and 0xFF
        val out = ByteWriter(first.size + pps[0].size + 32)
        out.u8(1)
        out.u8(profile)
        out.u8(first[2].toInt())
        out.u8(first[3].toInt())
        out.u8(0xFF)
        out.u8(0xE0 or sps.size)
        for (unit in sps) {
            out.u16(unit.size)
            out.put(unit)
        }
        out.u8(pps.size)
        for (unit in pps) {
            out.u16(unit.size)
            out.put(unit)
        }
        if (profile != 66 && profile != 77 && profile != 88) {
            val format = chromaAndBitDepth(first, profile)
            out.u8(0xFC or format[0])
            out.u8(0xF8 or (format[1] - 8))
            out.u8(0xF8 or (format[2] - 8))
            out.u8(0)
        }
        return out.toByteArray()
    }

    private fun chromaAndBitDepth(sps: ByteArray, profile: Int): IntArray {
        if (profile !in spsWithChromaFormat) {
            return intArrayOf(1, 8, 8)
        }
        val reader = BitReader(unescapeRbsp(sps, 1), 3)
        reader.ue()
        val chroma = reader.ue()
        if (chroma == 3) {
            reader.skip(1)
        }
        val luma = reader.ue() + 8
        val chromaDepth = reader.ue() + 8
        return intArrayOf(chroma, luma, chromaDepth)
    }
}
