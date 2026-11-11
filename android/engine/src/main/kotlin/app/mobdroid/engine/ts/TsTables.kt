package app.mobdroid.engine.ts

import app.mobdroid.engine.flv.ByteWriter

internal const val TS_PACKET_SIZE = 188
internal const val PAT_PID = 0
internal const val PMT_PID = 0x1000
internal const val VIDEO_PID = 0x100
internal const val AUDIO_PID = 0x101

internal class ElementaryStreamEntry(val pid: Int, val streamType: Int, val descriptors: ByteArray)

internal object TsTables {
    private const val PROGRAM_NUMBER = 1
    private const val TRANSPORT_STREAM_ID = 1

    private val crcTable =
        IntArray(256) { index ->
            var crc = index shl 24
            repeat(8) {
                crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1
            }
            crc
        }

    fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = (crc shl 8) xor crcTable[((crc ushr 24) xor data[i].toInt()) and 0xFF]
        }
        return crc
    }

    fun pat(): ByteArray {
        val out = ByteWriter(16)
        out.u8(0x00)
        out.u16(0xB000 or 13)
        out.u16(TRANSPORT_STREAM_ID)
        out.u8(0xC1)
        out.u8(0)
        out.u8(0)
        out.u16(PROGRAM_NUMBER)
        out.u16(0xE000 or PMT_PID)
        out.u32(crc32(out.array, 0, out.size))
        return out.toByteArray()
    }

    fun pmt(version: Int, pcrPid: Int, streams: List<ElementaryStreamEntry>): ByteArray {
        val sectionLength = 9 + 4 + streams.sumOf { 5 + it.descriptors.size }
        val out = ByteWriter(sectionLength + 3)
        out.u8(0x02)
        out.u16(0xB000 or sectionLength)
        out.u16(PROGRAM_NUMBER)
        out.u8(0xC1 or (version shl 1))
        out.u8(0)
        out.u8(0)
        out.u16(0xE000 or pcrPid)
        out.u16(0xF000)
        for (stream in streams) {
            out.u8(stream.streamType)
            out.u16(0xE000 or stream.pid)
            out.u16(0xF000 or stream.descriptors.size)
            out.put(stream.descriptors)
        }
        out.u32(crc32(out.array, 0, out.size))
        return out.toByteArray()
    }
}
