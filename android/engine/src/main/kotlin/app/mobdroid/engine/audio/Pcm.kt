package app.mobdroid.engine.audio

import java.nio.ShortBuffer

const val PCM_RATE = 48_000
const val NO_AUDIO = Long.MIN_VALUE

class PcmConverter(sourceRate: Int, private val sourceChannels: Int) {
    private val step = sourceRate.toDouble() / PCM_RATE
    private var position = 0.0
    private var lastLeft = 0
    private var lastRight = 0

    fun maxFrames(sourceFrames: Int): Int = (sourceFrames / step).toInt() + 2

    fun reset() {
        position = 0.0
        lastLeft = 0
        lastRight = 0
    }

    fun convert(source: ShortBuffer, sourceFrames: Int, out: ShortArray): Int {
        if (sourceFrames == 0) return 0
        val base = source.position()
        val right = if (sourceChannels > 1) 1 else 0
        var frames = 0
        var at = position
        while (at < sourceFrames - 1) {
            val index = Math.floor(at).toInt()
            val fraction = at - index
            val left0 = if (index < 0) lastLeft else source.get(base + index * sourceChannels).toInt()
            val right0 = if (index < 0) lastRight else source.get(base + index * sourceChannels + right).toInt()
            val left1 = source.get(base + (index + 1) * sourceChannels).toInt()
            val right1 = source.get(base + (index + 1) * sourceChannels + right).toInt()
            out[frames * 2] = (left0 + (left1 - left0) * fraction).toInt().toShort()
            out[frames * 2 + 1] = (right0 + (right1 - right0) * fraction).toInt().toShort()
            frames++
            at += step
        }
        position = at - sourceFrames
        lastLeft = source.get(base + (sourceFrames - 1) * sourceChannels).toInt()
        lastRight = source.get(base + (sourceFrames - 1) * sourceChannels + right).toInt()
        return frames
    }
}

class PcmRing(private val capacityFrames: Int, private val toleranceUs: Long = 80_000) {
    private val data = ShortArray(capacityFrames * 2)
    private var head = 0
    private var count = 0
    private var basePtsUs = 0L
    private var readFrames = 0L

    private val headPtsUs: Long get() = basePtsUs + readFrames * 1_000_000 / PCM_RATE

    @Synchronized
    fun clear() {
        head = 0
        count = 0
    }

    @Synchronized
    fun bufferedUs(): Long = count * 1_000_000L / PCM_RATE

    @Synchronized
    fun write(samples: ShortArray, frames: Int, ptsUs: Long) {
        if (count == 0) {
            basePtsUs = ptsUs
            readFrames = 0
        }
        val skipped = maxOf(frames - capacityFrames, 0)
        drop(maxOf(count + frames - skipped - capacityFrames, 0))
        var tail = (head + count) % capacityFrames
        for (frame in skipped until frames) {
            data[tail * 2] = samples[frame * 2]
            data[tail * 2 + 1] = samples[frame * 2 + 1]
            tail = (tail + 1) % capacityFrames
        }
        count += frames - skipped
    }

    @Synchronized
    fun mixInto(pcm: ByteArray, positionUs: Long, volume: Float): Long {
        if (count == 0) return NO_AUDIO
        val deviationUs = headPtsUs - positionUs
        if (deviationUs > toleranceUs) return NO_AUDIO
        if (deviationUs < -toleranceUs) drop(minOf(count.toLong(), -deviationUs * PCM_RATE / 1_000_000).toInt())
        val frames = minOf(pcm.size / 4, count)
        val gain = (volume * 256).toInt()
        for (frame in 0 until frames) {
            for (channel in 0..1) {
                val at = frame * 4 + channel * 2
                val existing = (pcm[at].toInt() and 0xff) or (pcm[at + 1].toInt() shl 8)
                val mixed = (existing + (data[head * 2 + channel] * gain shr 8)).coerceIn(-32768, 32767)
                pcm[at] = mixed.toByte()
                pcm[at + 1] = (mixed shr 8).toByte()
            }
            head = (head + 1) % capacityFrames
        }
        count -= frames
        readFrames += frames
        return if (frames > 0 && deviationUs >= -toleranceUs) deviationUs else NO_AUDIO
    }

    private fun drop(frames: Int) {
        head = (head + frames) % capacityFrames
        count -= frames
        readFrames += frames
    }
}
