package com.opirl.app.engine.rtmp

import com.opirl.app.engine.flv.ByteWriter

internal object Amf0 {
    private const val NUMBER = 0x00
    private const val BOOLEAN = 0x01
    private const val STRING = 0x02
    private const val OBJECT = 0x03
    private const val NULL = 0x05
    private const val UNDEFINED = 0x06
    private const val ECMA_ARRAY = 0x08
    private const val OBJECT_END = 0x09
    private const val STRICT_ARRAY = 0x0A
    private const val DATE = 0x0B
    private const val LONG_STRING = 0x0C
    private const val UNSUPPORTED = 0x0D
    private const val XML_DOCUMENT = 0x0F
    private const val TYPED_OBJECT = 0x10
    private const val MAX_DEPTH = 32

    fun write(out: ByteWriter, value: Any?) {
        when (value) {
            null -> out.u8(NULL)
            is Boolean -> {
                out.u8(BOOLEAN)
                out.u8(if (value) 1 else 0)
            }
            is Number -> {
                out.u8(NUMBER)
                out.u64(java.lang.Double.doubleToLongBits(value.toDouble()))
            }
            is String -> writeString(out, value)
            is Map<*, *> -> {
                out.u8(OBJECT)
                for ((key, item) in value) {
                    writeKey(out, key as String)
                    write(out, item)
                }
                out.u16(0)
                out.u8(OBJECT_END)
            }
            is List<*> -> {
                out.u8(STRICT_ARRAY)
                out.u32(value.size)
                value.forEach { write(out, it) }
            }
            else -> throw IllegalArgumentException("Unsupported AMF0 value ${value.javaClass}")
        }
    }

    fun writeCommand(out: ByteWriter, name: String, transaction: Int, vararg arguments: Any?) {
        writeString(out, name)
        write(out, transaction.toDouble())
        arguments.forEach { write(out, it) }
    }

    private fun writeString(out: ByteWriter, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 0xFFFF) {
            out.u8(LONG_STRING)
            out.u32(bytes.size)
        } else {
            out.u8(STRING)
            out.u16(bytes.size)
        }
        out.put(bytes)
    }

    private fun writeKey(out: ByteWriter, key: String) {
        val bytes = key.toByteArray(Charsets.UTF_8)
        out.u16(bytes.size)
        out.put(bytes)
    }

    class Reader(private val data: ByteArray, private var position: Int = 0, private val end: Int = data.size) {
        val hasMore: Boolean
            get() = position < end

        fun read(depth: Int = 0): Any? {
            require(depth <= MAX_DEPTH) { "AMF0 nesting too deep" }
            return when (val type = u8()) {
                NUMBER -> java.lang.Double.longBitsToDouble(u64())
                BOOLEAN -> u8() != 0
                STRING -> string(u16())
                LONG_STRING, XML_DOCUMENT -> string(u32())
                OBJECT -> readProperties(depth)
                TYPED_OBJECT -> {
                    string(u16())
                    readProperties(depth)
                }
                ECMA_ARRAY -> {
                    skip(4)
                    readProperties(depth)
                }
                STRICT_ARRAY -> {
                    val count = u32()
                    require(count in 0..(end - position)) { "Invalid AMF0 array length" }
                    List(count) { read(depth + 1) }
                }
                DATE -> {
                    val millis = java.lang.Double.longBitsToDouble(u64())
                    skip(2)
                    millis
                }
                NULL, UNDEFINED, UNSUPPORTED -> null
                else -> throw IllegalArgumentException("Unsupported AMF0 type $type")
            }
        }

        private fun readProperties(depth: Int): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            while (true) {
                val name = string(u16())
                if (name.isEmpty()) {
                    require(u8() == OBJECT_END) { "Missing AMF0 object end" }
                    return result
                }
                result[name] = read(depth + 1)
            }
        }

        private fun u8(): Int {
            require(position < end) { "Truncated AMF0" }
            return data[position++].toInt() and 0xFF
        }

        private fun u16(): Int = (u8() shl 8) or u8()

        private fun rawU32(): Long = (u16().toLong() shl 16) or u16().toLong()

        private fun u32(): Int {
            val value = rawU32()
            require(value <= Int.MAX_VALUE) { "AMF0 length too large" }
            return value.toInt()
        }

        private fun u64(): Long = (rawU32() shl 32) or rawU32()

        private fun skip(count: Int) {
            require(position + count <= end) { "Truncated AMF0" }
            position += count
        }

        private fun string(length: Int): String {
            require(length >= 0 && position + length <= end) { "Truncated AMF0" }
            val value = String(data, position, length, Charsets.UTF_8)
            position += length
            return value
        }
    }
}
