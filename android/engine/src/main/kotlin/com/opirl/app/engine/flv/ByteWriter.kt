package com.opirl.app.engine.flv

internal class ByteWriter(initialCapacity: Int = 256) {
    var array = ByteArray(initialCapacity)
        private set
    var size = 0
        private set

    fun clear() {
        size = 0
    }

    fun truncate(newSize: Int) {
        size = newSize
    }

    fun ensure(extra: Int) {
        val needed = size + extra
        if (needed > array.size) {
            array = array.copyOf(maxOf(needed, array.size * 2))
        }
    }

    fun u8(value: Int) {
        ensure(1)
        array[size++] = value.toByte()
    }

    fun u16(value: Int) {
        ensure(2)
        array[size++] = (value ushr 8).toByte()
        array[size++] = value.toByte()
    }

    fun u24(value: Int) {
        ensure(3)
        array[size++] = (value ushr 16).toByte()
        array[size++] = (value ushr 8).toByte()
        array[size++] = value.toByte()
    }

    fun u32(value: Int) {
        ensure(4)
        array[size++] = (value ushr 24).toByte()
        array[size++] = (value ushr 16).toByte()
        array[size++] = (value ushr 8).toByte()
        array[size++] = value.toByte()
    }

    fun u32le(value: Int) {
        ensure(4)
        array[size++] = value.toByte()
        array[size++] = (value ushr 8).toByte()
        array[size++] = (value ushr 16).toByte()
        array[size++] = (value ushr 24).toByte()
    }

    fun u64(value: Long) {
        u32((value ushr 32).toInt())
        u32(value.toInt())
    }

    fun put(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        ensure(length)
        System.arraycopy(source, offset, array, size, length)
        size += length
    }

    fun putEscaped(source: ByteArray, offset: Int, length: Int) {
        ensure(length + length / 2 + 1)
        var zeros = 0
        for (i in offset until offset + length) {
            val value = source[i].toInt() and 0xFF
            if (zeros >= 2 && value <= 3) {
                array[size++] = 3
                zeros = 0
            }
            array[size++] = value.toByte()
            zeros = if (value == 0) zeros + 1 else 0
        }
    }

    fun fill(value: Int, count: Int) {
        ensure(count)
        array.fill(value.toByte(), size, size + count)
        size += count
    }

    fun reserve(count: Int): Int {
        ensure(count)
        val at = size
        size += count
        return at
    }

    fun setU16(at: Int, value: Int) {
        array[at] = (value ushr 8).toByte()
        array[at + 1] = value.toByte()
    }

    fun setU32(at: Int, value: Int) {
        array[at] = (value ushr 24).toByte()
        array[at + 1] = (value ushr 16).toByte()
        array[at + 2] = (value ushr 8).toByte()
        array[at + 3] = value.toByte()
    }

    fun toByteArray(): ByteArray = array.copyOf(size)
}
