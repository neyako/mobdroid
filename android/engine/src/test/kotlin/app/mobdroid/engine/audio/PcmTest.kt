package app.mobdroid.engine.audio

import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun samples(pcm: ByteArray) = ShortArray(pcm.size / 2) { ((pcm[it * 2].toInt() and 0xff) or (pcm[it * 2 + 1].toInt() shl 8)).toShort() }

class PcmTest {
    @Test
    fun sameRateStereoPassesThroughAcrossChunks() {
        val converter = PcmConverter(48_000, 2)
        val source = ShortArray(400) { it.toShort() }
        val out = ShortArray(2 * converter.maxFrames(100))
        val result = ArrayList<Short>()
        for (chunk in 0 until 2) {
            val frames = converter.convert(ShortBuffer.wrap(source, chunk * 200, 200), 100, out)
            for (i in 0 until frames * 2) result += out[i]
        }
        assertEquals(source.take(result.size), result)
        assertEquals(398, result.size)
    }

    @Test
    fun monoAt44100IsResampledToStereoAt48000() {
        val converter = PcmConverter(44_100, 1)
        val out = ShortArray(2 * converter.maxFrames(441))
        var total = 0
        var previous = -1
        for (chunk in 0 until 100) {
            val source = ShortArray(441) { ((chunk * 441 + it) / 2).toShort() }
            val frames = converter.convert(ShortBuffer.wrap(source), 441, out)
            for (i in 0 until frames) {
                assertEquals(out[i * 2], out[i * 2 + 1])
                assertTrue(out[i * 2] >= previous)
                previous = out[i * 2].toInt()
            }
            total += frames
        }
        assertTrue(abs(total - 48_000) <= 2, "$total frames")
    }

    @Test
    fun surroundKeepsTheFrontPair() {
        val converter = PcmConverter(48_000, 6)
        val source = ShortArray(60) { (it % 6 + 1).toShort() }
        val out = ShortArray(2 * converter.maxFrames(10))
        val frames = converter.convert(ShortBuffer.wrap(source), 10, out)
        assertEquals(9, frames)
        assertEquals(listOf<Short>(1, 2, 1, 2), out.take(4))
    }

    @Test
    fun mixesAlignedAudioWithVolumeAndClamps() {
        val ring = PcmRing(1000)
        ring.write(shortArrayOf(1000, -1000, 30_000, -30_000), 2, 5_000_000)
        val pcm = ByteArray(12)
        pcm[4] = 0x10
        pcm[5] = 0x27
        assertEquals(0, ring.mixInto(pcm, 5_000_000, 0.5f))
        assertEquals(listOf<Short>(500, -500, 25_000, -15_000, 0, 0), samples(pcm).toList())
        assertEquals(0, ring.bufferedUs())
        ring.write(shortArrayOf(20_000, -20_000), 1, 0)
        val loud = byteArrayOf(0x30, 0x75, 0xd0.toByte(), 0x8a.toByte())
        assertEquals(0, ring.mixInto(loud, 0, 1f))
        assertEquals(listOf<Short>(32_767, -32_768), samples(loud).toList())
    }

    @Test
    fun earlyAudioWaitsAndLateAudioIsSkipped() {
        val ring = PcmRing(48_000)
        val second = ShortArray(96_000) { (it / 2 / 480).toShort() }
        ring.write(second, 48_000, 1_000_000)
        val pcm = ByteArray(4)
        assertEquals(NO_AUDIO, ring.mixInto(pcm, 500_000, 1f))
        assertEquals(1_000_000, ring.bufferedUs())
        assertEquals(-30_000, ring.mixInto(ByteArray(4), 1_030_000, 1f))
        assertEquals(NO_AUDIO, ring.mixInto(pcm, 1_500_000, 1f))
        assertEquals(50, samples(pcm)[0].toInt())
        assertEquals((48_000 - 24_001) * 1_000_000L / 48_000, ring.bufferedUs())
    }

    @Test
    fun audioAfterATimestampGapPlaysAtItsOwnTime() {
        val ring = PcmRing(48_000)
        ring.write(ShortArray(9600) { 1 }, 4800, 0)
        ring.write(ShortArray(9600) { 2 }, 4800, 1_000_000)
        val pcm = ByteArray(4800 * 4 + 400)
        assertEquals(0, ring.mixInto(pcm, 0, 1f))
        assertEquals(listOf<Short>(1, 0), listOf(samples(pcm)[9599], samples(pcm)[9600]))
        assertEquals(NO_AUDIO, ring.mixInto(ByteArray(4), 100_000, 1f))
        val later = ByteArray(4)
        assertEquals(0, ring.mixInto(later, 1_000_000, 1f))
        assertEquals(2, samples(later)[0].toInt())
    }

    @Test
    fun aWriteLargerThanTheRingKeepsItsEndAtTheRightTime() {
        val ring = PcmRing(48_000)
        ring.write(ShortArray(192_000) { (it / 2 / 480).toShort() }, 96_000, 0)
        val pcm = ByteArray(4)
        assertEquals(NO_AUDIO, ring.mixInto(pcm, 0, 1f))
        assertEquals(0, ring.mixInto(pcm, 1_000_000, 1f))
        assertEquals(100, samples(pcm)[0].toInt())
    }

    @Test
    fun aFullRingDropsTheOldestAudio() {
        val ring = PcmRing(4)
        ring.write(shortArrayOf(1, 1, 2, 2, 3, 3), 3, 0)
        ring.write(shortArrayOf(4, 4, 5, 5, 6, 6), 3, 0)
        val pcm = ByteArray(16)
        assertEquals(41, ring.mixInto(pcm, 0, 1f))
        assertEquals(listOf<Short>(3, 3, 4, 4, 5, 5, 6, 6), samples(pcm).toList())
    }
}
