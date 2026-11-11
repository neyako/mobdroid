package com.opirl.app.engine.ts

import java.io.ByteArrayOutputStream

class TsPacket(
    val index: Int,
    val pid: Int,
    val payloadUnitStart: Boolean,
    val continuity: Int,
    val hasPayload: Boolean,
    val adaptationLength: Int,
    val randomAccess: Boolean,
    val priority: Boolean,
    val pcr: Long?,
    val payload: ByteArray,
)

class PesPacket(
    val pid: Int,
    val firstPacket: TsPacket,
    val streamId: Int,
    val declaredLength: Int,
    val alignment: Boolean,
    val pts: Long?,
    val dts: Long?,
    val payload: ByteArray,
)

class Section(val tableId: Int, val version: Int, val body: ByteArray, val crcValid: Boolean)

object TsParser {
    fun packets(data: ByteArray): List<TsPacket> {
        check(data.size % 188 == 0) { "Not a whole number of packets: ${data.size}" }
        return (0 until data.size / 188).map { index ->
            val at = index * 188
            check(data[at].toInt() == 0x47) { "Lost sync at packet $index" }
            val pid = ((data[at + 1].toInt() and 0x1F) shl 8) or (data[at + 2].toInt() and 0xFF)
            val control = data[at + 3].toInt() and 0xFF
            var offset = at + 4
            var adaptationLength = -1
            var randomAccess = false
            var priority = false
            var pcr: Long? = null
            if (control and 0x20 != 0) {
                adaptationLength = data[offset].toInt() and 0xFF
                if (adaptationLength > 0) {
                    val flags = data[offset + 1].toInt() and 0xFF
                    randomAccess = flags and 0x40 != 0
                    priority = flags and 0x20 != 0
                    if (flags and 0x10 != 0) {
                        val b = (2..7).map { data[offset + it].toLong() and 0xFF }
                        pcr = (b[0] shl 25) or (b[1] shl 17) or (b[2] shl 9) or (b[3] shl 1) or (b[4] ushr 7)
                        check(b[4] and 0x7E == 0x7EL) { "PCR reserved bits not set" }
                    }
                }
                offset += 1 + adaptationLength
            }
            val hasPayload = control and 0x10 != 0
            TsPacket(
                index,
                pid,
                data[at + 1].toInt() and 0x40 != 0,
                control and 0x0F,
                hasPayload,
                adaptationLength,
                randomAccess,
                priority,
                pcr,
                if (hasPayload) data.copyOfRange(offset, at + 188) else ByteArray(0),
            )
        }
    }

    fun pes(packets: List<TsPacket>, pid: Int): List<PesPacket> {
        val result = ArrayList<PesPacket>()
        var first: TsPacket? = null
        val body = ByteArrayOutputStream()
        fun finish() {
            val start = first ?: return
            result.add(parsePes(start, body.toByteArray()))
            body.reset()
        }
        for (packet in packets.filter { it.pid == pid && it.hasPayload }) {
            if (packet.payloadUnitStart) {
                finish()
                first = packet
            }
            body.write(packet.payload)
        }
        finish()
        return result
    }

    private fun parsePes(first: TsPacket, data: ByteArray): PesPacket {
        check(data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) { "Bad PES start code" }
        val streamId = data[3].toInt() and 0xFF
        val length = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        check(data[6].toInt() and 0xC0 == 0x80) { "Bad PES marker" }
        val alignment = data[6].toInt() and 0x04 != 0
        val flags = (data[7].toInt() and 0xC0) shr 6
        val headerLength = data[8].toInt() and 0xFF
        var pts: Long? = null
        var dts: Long? = null
        if (flags and 2 != 0) pts = timestamp(data, 9, if (flags == 3) 3 else 2)
        if (flags == 3) dts = timestamp(data, 14, 1)
        if (length != 0) {
            check(length == data.size - 6) { "PES length $length does not match ${data.size - 6}" }
        }
        return PesPacket(first.pid, first, streamId, length, alignment, pts, dts, data.copyOfRange(9 + headerLength, data.size))
    }

    private fun timestamp(data: ByteArray, at: Int, marker: Int): Long {
        val b = (0 until 5).map { data[at + it].toLong() and 0xFF }
        check(b[0] ushr 4 == marker.toLong() && b[0] and 1 == 1L && b[2] and 1 == 1L && b[4] and 1 == 1L) {
            "Bad timestamp markers"
        }
        return ((b[0] shr 1 and 7) shl 30) or (b[1] shl 22) or ((b[2] shr 1) shl 15) or (b[3] shl 7) or (b[4] shr 1)
    }

    fun section(packets: List<TsPacket>, pid: Int): Section {
        val packet = packets.first { it.pid == pid && it.payloadUnitStart }
        check(packet.payload[0].toInt() == 0) { "Unexpected pointer field" }
        val data = packet.payload
        val length = ((data[2].toInt() and 0x0F) shl 8) or (data[3].toInt() and 0xFF)
        val section = data.copyOfRange(1, 4 + length)
        check(data.drop(4 + length).all { it.toInt() == -1 }) { "PSI packet is not stuffed with 0xFF" }
        return Section(
            section[0].toInt() and 0xFF,
            (section[5].toInt() shr 1) and 0x1F,
            section.copyOfRange(8, section.size - 4),
            TsTables.crc32(section, 0, section.size) == 0,
        )
    }
}
