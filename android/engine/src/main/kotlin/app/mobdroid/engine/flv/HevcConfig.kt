package app.mobdroid.engine.flv

internal class HevcSpsInfo(
    val profileSpace: Int,
    val tier: Int,
    val profileIdc: Int,
    val compatibilityFlags: Int,
    val constraintFlags: Long,
    val levelIdc: Int,
    val chromaFormat: Int,
    val bitDepthLuma: Int,
    val bitDepthChroma: Int,
    val maxSubLayers: Int,
    val temporalIdNested: Boolean,
)

internal object HevcConfig {
    fun parseSps(sps: ByteArray): HevcSpsInfo {
        require(sps.size > 2) { "HEVC SPS too short" }
        val reader = BitReader(unescapeRbsp(sps, 2))
        reader.skip(4)
        val maxSubLayersMinus1 = reader.bits(3)
        val temporalIdNested = reader.flag()
        val profileSpace = reader.bits(2)
        val tier = reader.bit()
        val profileIdc = reader.bits(5)
        val compatibility = reader.bits(32)
        val constraints = reader.bitsLong(48)
        val levelIdc = reader.bits(8)
        val profilePresent = BooleanArray(maxSubLayersMinus1)
        val levelPresent = BooleanArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            profilePresent[i] = reader.flag()
            levelPresent[i] = reader.flag()
        }
        if (maxSubLayersMinus1 > 0) {
            reader.skip(2 * (8 - maxSubLayersMinus1))
        }
        for (i in 0 until maxSubLayersMinus1) {
            if (profilePresent[i]) {
                reader.skip(88)
            }
            if (levelPresent[i]) {
                reader.skip(8)
            }
        }
        reader.ue()
        val chroma = reader.ue()
        if (chroma == 3) {
            reader.skip(1)
        }
        reader.ue()
        reader.ue()
        if (reader.flag()) {
            repeat(4) { reader.ue() }
        }
        val lumaDepth = reader.ue() + 8
        val chromaDepth = reader.ue() + 8
        return HevcSpsInfo(
            profileSpace,
            tier,
            profileIdc,
            compatibility,
            constraints,
            levelIdc,
            chroma,
            lumaDepth,
            chromaDepth,
            maxSubLayersMinus1 + 1,
            temporalIdNested,
        )
    }

    fun record(annexBConfig: ByteArray): ByteArray {
        val vps = ArrayList<ByteArray>()
        val sps = ArrayList<ByteArray>()
        val pps = ArrayList<ByteArray>()
        forEachNalu(annexBConfig, 0, annexBConfig.size) { start, end ->
            when (hevcNaluType(annexBConfig[start])) {
                HEVC_VPS -> vps.add(annexBConfig.copyOfRange(start, end))
                HEVC_SPS -> sps.add(annexBConfig.copyOfRange(start, end))
                HEVC_PPS -> pps.add(annexBConfig.copyOfRange(start, end))
            }
        }
        require(vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()) {
            "HEVC config needs a VPS, an SPS and a PPS"
        }
        val info = parseSps(sps[0])
        val arrays = listOf(HEVC_VPS to vps, HEVC_SPS to sps, HEVC_PPS to pps)
        val out = ByteWriter(vps[0].size + sps[0].size + pps[0].size + 64)
        out.u8(1)
        out.u8((info.profileSpace shl 6) or (info.tier shl 5) or info.profileIdc)
        out.u32(info.compatibilityFlags)
        out.u16((info.constraintFlags ushr 32).toInt())
        out.u32(info.constraintFlags.toInt())
        out.u8(info.levelIdc)
        out.u16(0xF000)
        out.u8(0xFC)
        out.u8(0xFC or info.chromaFormat)
        out.u8(0xF8 or (info.bitDepthLuma - 8))
        out.u8(0xF8 or (info.bitDepthChroma - 8))
        out.u16(0)
        out.u8((info.maxSubLayers shl 3) or ((if (info.temporalIdNested) 1 else 0) shl 2) or 3)
        out.u8(arrays.size)
        for ((type, units) in arrays) {
            out.u8(0x80 or type)
            out.u16(units.size)
            for (unit in units) {
                out.u16(unit.size)
                out.put(unit)
            }
        }
        return out.toByteArray()
    }
}
