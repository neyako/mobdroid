package app.mobdroid.engine.flv

internal class BitReader(private val data: ByteArray, offset: Int = 0, private val end: Int = data.size) {
    private var position = offset * 8

    val bitsLeft: Int
        get() = end * 8 - position

    fun bit(): Int {
        require(position < end * 8) { "Truncated bitstream" }
        val value = (data[position ushr 3].toInt() ushr (7 - (position and 7))) and 1
        position++
        return value
    }

    fun flag(): Boolean = bit() == 1

    fun bits(count: Int): Int {
        require(count in 0..32)
        var value = 0L
        repeat(count) { value = (value shl 1) or bit().toLong() }
        return value.toInt()
    }

    fun bitsLong(count: Int): Long {
        var value = 0L
        repeat(count) { value = (value shl 1) or bit().toLong() }
        return value
    }

    fun skip(count: Int) {
        require(position + count <= end * 8) { "Truncated bitstream" }
        position += count
    }

    fun ue(): Int {
        var zeros = 0
        while (bit() == 0) {
            zeros++
            require(zeros < 32) { "Invalid exp-golomb code" }
        }
        return ((1L shl zeros) - 1 + bitsLong(zeros)).toInt()
    }

    fun uvlc(): Long {
        var zeros = 0
        while (bit() == 0) {
            zeros++
            if (zeros >= 32) {
                return 0xFFFFFFFFL
            }
        }
        return bitsLong(zeros) + (1L shl zeros) - 1
    }
}
