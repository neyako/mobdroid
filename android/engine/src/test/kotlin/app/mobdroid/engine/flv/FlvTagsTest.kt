package app.mobdroid.engine.flv

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.VideoCodec
import app.mobdroid.engine.media.VideoFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class FlvTagsTest {
    private val sps = hex("6742c00dda0507ec0440000003004000000f03c50aa8")
    private val pps = hex("68ce0fc8")
    private val hevcConfig = annexB(
        hex("40010c01ffff01600000030090000003000003003cba0240"),
        hex("42010101600000030090000003000003003ca00a080f165ba4a4c2f0168080000003008000000f04"),
        hex("4401c073c189"),
    )
    private val sequenceHeader = hex("0a0b000000043cffbc02f80040")

    private fun video(codec: VideoCodec, config: ByteArray) = FlvVideoTags(VideoFormat(codec, 320, 240, 30, config))

    private fun sequenceHeaderOf(tags: FlvVideoTags): String = ByteWriter().also { tags.writeSequenceHeader(it) }.toByteArray().toHex()

    private fun frameOf(tags: FlvVideoTags, data: ByteArray, key: Boolean): String =
        ByteWriter().also { tags.writeFrame(it, EncodedFrame(data, 0, key)) }.toByteArray().toHex()

    @Test
    fun h264UsesLegacyAvcTags() {
        val tags = video(VideoCodec.H264, annexB(sps, pps))
        assertEquals("17" + "00" + "000000" + AvcConfig.record(annexB(sps, pps)).toHex(), sequenceHeaderOf(tags))
        val nalu = hex("65aabbcc")
        assertEquals("17" + "01" + "000000" + "00000004" + nalu.toHex(), frameOf(tags, annexB(nalu), true))
        assertEquals("27" + "01" + "000000" + "00000004" + "41112233", frameOf(tags, annexB(hex("41112233")), false))
        assertEquals(null, tags.fourCcName)
    }

    @Test
    fun hevcUsesEnhancedHeaderWithCodedFramesX() {
        val tags = video(VideoCodec.HEVC, hevcConfig)
        assertEquals("90" + "68766331" + HevcConfig.record(hevcConfig).toHex(), sequenceHeaderOf(tags))
        assertEquals("93" + "68766331" + "00000003" + "26aabb", frameOf(tags, annexB(hex("26aabb")), true))
        assertEquals("a3" + "68766331" + "00000003" + "0201cc", frameOf(tags, annexB(hex("0201cc")), false))
        assertEquals("hvc1", tags.fourCcName)
    }

    @Test
    fun av1UsesCodedFramesWithoutCompositionTimeAndDropsTemporalDelimiters() {
        val tags = video(VideoCodec.AV1, sequenceHeader)
        assertEquals("90" + "61763031" + "81000c00" + sequenceHeader.toHex(), sequenceHeaderOf(tags))
        val frameObu = hex("320300aabb")
        assertEquals("91" + "61763031" + sequenceHeader.toHex() + frameObu.toHex(), frameOf(tags, concat(hex("1200"), sequenceHeader, frameObu), true))
        assertEquals("a1" + "61763031" + frameObu.toHex(), frameOf(tags, concat(hex("1200"), frameObu), false))
        assertEquals("av01", tags.fourCcName)
    }

    @Test
    fun av1FrameObuWithoutSizeFieldGetsOne() {
        val tags = video(VideoCodec.AV1, sequenceHeader)
        assertEquals("a1" + "61763031" + "3203" + "aabbcc", frameOf(tags, hex("30aabbcc"), false))
    }

    @Test
    fun aacUsesLegacyTagsWithAudioSpecificConfig() {
        val tags = FlvAudioTags(AudioFormat(48000, 2, hex("1190")))
        assertEquals("af00" + "1190", ByteWriter().also { tags.writeSequenceHeader(it) }.toByteArray().toHex())
        assertEquals("af01" + "010203", ByteWriter().also { tags.writeFrame(it, EncodedFrame(hex("010203"), 0, true)) }.toByteArray().toHex())
    }
}
