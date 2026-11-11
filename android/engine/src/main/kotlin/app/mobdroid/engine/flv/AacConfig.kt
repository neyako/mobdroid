package app.mobdroid.engine.flv

import app.mobdroid.engine.media.AudioFormat

internal class AacConfig(val audioObjectType: Int, val sampleRateIndex: Int, val channelConfiguration: Int) {
    fun writeAdtsHeader(out: ByteArray, offset: Int, rawLength: Int) {
        val frameLength = rawLength + ADTS_HEADER_SIZE
        out[offset] = 0xFF.toByte()
        out[offset + 1] = 0xF1.toByte()
        val profileAndRate = ((audioObjectType - 1) shl 6) or (sampleRateIndex shl 2)
        out[offset + 2] = (profileAndRate or (channelConfiguration ushr 2)).toByte()
        out[offset + 3] = (((channelConfiguration and 3) shl 6) or (frameLength ushr 11)).toByte()
        out[offset + 4] = (frameLength ushr 3).toByte()
        out[offset + 5] = (((frameLength and 7) shl 5) or 0x1F).toByte()
        out[offset + 6] = 0xFC.toByte()
    }

    companion object {
        const val ADTS_HEADER_SIZE = 7
        const val MAX_RAW_LENGTH = 0x1FFF - ADTS_HEADER_SIZE

        private val sampleRates =
            intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun from(format: AudioFormat): AacConfig {
            require(format.config.size >= 2) { "AAC config needs an AudioSpecificConfig" }
            val reader = BitReader(format.config)
            val objectType = reader.bits(5)
            require(objectType in 1..4) { "Unsupported AAC audio object type $objectType" }
            var rateIndex = reader.bits(4)
            if (rateIndex == 15) {
                rateIndex = nearestRateIndex(reader.bits(24))
            }
            require(rateIndex < sampleRates.size) { "Invalid AAC sampling frequency index" }
            var channels = reader.bits(4)
            if (channels == 0) {
                channels = format.channels
            }
            return AacConfig(objectType, rateIndex, channels)
        }

        private fun nearestRateIndex(sampleRate: Int): Int =
            sampleRates.indices.minByOrNull { kotlin.math.abs(sampleRates[it] - sampleRate) } ?: 4
    }
}
