package app.mobdroid.engine.flv

import app.mobdroid.engine.media.AudioFormat
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigRecordsTest {
    private val highSps = hex("6764000dacb20283f60220000003002000000781e28549")
    private val highPps = hex("68ebc3cb22c0")
    private val baselineSps = hex("6742c00dda0507ec0440000003004000000f03c50aa8")
    private val baselinePps = hex("68ce0fc8")
    private val hevcVps = hex("40010c01ffff01600000030090000003000003003cba0240")
    private val hevcSps = hex("42010101600000030090000003000003003ca00a080f165ba4a4c2f0168080000003008000000f04")
    private val hevcPps = hex("4401c073c189")
    private val hevcMain10Sps =
        hex("42010102200000030090000003000003003ca00a080f1365ba4a4c2f016808000003000800000300f040")

    @Test
    fun avcHighProfileRecordMatchesFfmpegIncludingChromaExtension() {
        val record = AvcConfig.record(annexB(highSps, highPps))
        assertEquals(
            "0164000dffe10017" + highSps.toHex() + "010006" + highPps.toHex() + "fdf8f800",
            record.toHex(),
        )
    }

    @Test
    fun avcBaselineProfileRecordHasNoExtension() {
        val record = AvcConfig.record(annexB(baselineSps, baselinePps))
        assertEquals(
            "0142c00dffe10016" + baselineSps.toHex() + "010004" + baselinePps.toHex(),
            record.toHex(),
        )
    }

    @Test
    fun avcRecordAcceptsThreeByteStartCodesAndTrailingZeros() {
        val config = concat(
            annexB(baselineSps, baselinePps, startCode = byteArrayOf(0, 0, 1)),
            byteArrayOf(0, 0),
        )
        assertContentEquals(AvcConfig.record(annexB(baselineSps, baselinePps)), AvcConfig.record(config))
    }

    @Test
    fun avcRecordRequiresBothParameterSets() {
        assertFailsWith<IllegalArgumentException> { AvcConfig.record(annexB(baselineSps)) }
    }

    @Test
    fun hevcMainRecordMatchesFfmpegHeaderAndCarriesAllArrays() {
        val record = HevcConfig.record(annexB(hevcVps, hevcSps, hevcPps))
        val expectedHeader = "0101600000009000000000003cf000fcfdf8f800000f03"
        val expectedArrays = "a00001" + "0018" + hevcVps.toHex() + "a10001" + "0028" + hevcSps.toHex() +
            "a20001" + "0006" + hevcPps.toHex()
        assertEquals(expectedHeader + expectedArrays, record.toHex())
    }

    @Test
    fun hevcMain10RecordCarriesProfileAndBitDepth() {
        val record = HevcConfig.record(annexB(hevcVps, hevcMain10Sps, hevcPps))
        assertEquals("0102200000009000000000003cf000fcfdfafa00000f03", record.toHex().substring(0, 46))
    }

    @Test
    fun hevcSpsInfoExposesProfileTierLevelAndChroma() {
        val info = HevcConfig.parseSps(hevcSps)
        assertEquals(1, info.profileIdc)
        assertEquals(0, info.tier)
        assertEquals(60, info.levelIdc)
        assertEquals(1, info.chromaFormat)
        assertEquals(8, info.bitDepthLuma)
        assertEquals(1, info.maxSubLayers)
        assertEquals(true, info.temporalIdNested)
    }

    @Test
    fun hevcRecordRequiresVps() {
        assertFailsWith<IllegalArgumentException> { HevcConfig.record(annexB(hevcSps, hevcPps)) }
    }

    @Test
    fun av1RecordFromBareSequenceHeaderMatchesFfmpeg() {
        val obu = hex("0a0b000000043cffbc02f80040")
        assertEquals("81000c00" + obu.toHex(), Av1Config.from(obu).record.toHex())
    }

    @Test
    fun av1RecordFromTenBitSequenceHeaderSetsHighBitdepth() {
        val obu = hex("0a0b000000043cffbc02f84040")
        assertEquals("81004c00" + obu.toHex(), Av1Config.from(obu).record.toHex())
    }

    @Test
    fun av1ConfigSkipsTemporalDelimiterAndKeepsExistingRecord() {
        val obu = hex("0a0b000000043cffbc02f80040")
        val fromObus = Av1Config.from(concat(hex("1200"), obu))
        assertEquals("81000c00" + obu.toHex(), fromObus.record.toHex())
        val existing = hex("81000c00") + obu
        val fromRecord = Av1Config.from(existing)
        assertContentEquals(existing, fromRecord.record)
        assertContentEquals(obu, fromRecord.sequenceHeaderObu)
    }

    @Test
    fun av1RecordWithoutConfigObusHasNoSequenceHeader() {
        val config = Av1Config.from(hex("81000c00"))
        assertContentEquals(hex("81000c00"), config.record)
        assertEquals(null, config.sequenceHeaderObu)
        assertEquals(3, config.hdrWcgIdc)
    }

    @Test
    fun av1ConfigWithoutSequenceHeaderIsRejected() {
        assertFailsWith<IllegalArgumentException> { Av1Config.from(hex("1200")) }
    }

    @Test
    fun av1DescriptorCarriesHdrWcgIndicationFromColorDescription() {
        val cases = mapOf(
            "0a0b000000043cffbc02f80040" to 3,
            "0a0e000000043cffbc02f81010101040" to 0,
            "0a0e000000043cffbc02f85091009040" to 2,
            "0a0e000000043cffbc02f85091209040" to 2,
            "0a0e000000043cffbc02f810c0101040" to 1,
        )
        for ((obu, expected) in cases) {
            val config = Av1Config.from(hex(obu))
            assertEquals(expected, config.hdrWcgIdc, obu)
            assertEquals(expected shl 6, config.descriptor[3].toInt() and 0xFF, obu)
            assertContentEquals(config.record.copyOf(3), config.descriptor.copyOf(3), obu)
        }
    }

    @Test
    fun aacAdtsHeaderEncodesProfileRateChannelsAndLength() {
        val config = AacConfig.from(AudioFormat(48000, 2, hex("1190")))
        val header = ByteArray(7)
        config.writeAdtsHeader(header, 0, 200)
        assertEquals("fff14c8019fffc", header.toHex())
    }

    @Test
    fun aacConfigFallsBackToFormatChannelsWhenConfigDefersToPce() {
        val config = AacConfig.from(AudioFormat(44100, 2, hex("1200")))
        assertEquals(4, config.sampleRateIndex)
        assertEquals(2, config.channelConfiguration)
    }

    @Test
    fun aacConfigRejectsUnsupportedObjectType() {
        assertFailsWith<IllegalArgumentException> { AacConfig.from(AudioFormat(48000, 2, hex("2990"))) }
    }
}
