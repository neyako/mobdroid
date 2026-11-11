package com.opirl.app.engine.rtmp

import com.opirl.app.engine.flv.ByteWriter
import com.opirl.app.engine.flv.hex
import com.opirl.app.engine.flv.toHex
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RtmpChunksTest {
    private val writer = RtmpChunkWriter()
    private val out = ByteWriter()

    private fun write(csid: Int, type: Int, stream: Int, ts: Long, body: ByteArray, compress: Boolean = true) {
        writer.write(out, csid, type, stream, ts, body, 0, body.size, compress)
    }

    private fun written() = out.toByteArray().toHex().also { out.clear() }

    private fun body(size: Int) = ByteArray(size) { (it % 251 + 1).toByte() }

    @Test
    fun firstMessageUsesAFullHeaderAndLaterOnesCompress() {
        write(6, 9, 1, 1000, hex("0102030405"))
        assertEquals("06" + "0003e8" + "000005" + "09" + "01000000" + "0102030405", written())
        write(6, 9, 1, 1033, hex("01020304050607"))
        assertEquals("46" + "000021" + "000007" + "09" + "01020304050607", written())
        write(6, 9, 1, 1066, hex("07060504030201"))
        assertEquals("86" + "000021" + "07060504030201", written())
    }

    @Test
    fun compressionIsAbandonedWhenTheStreamChangesTimeGoesBackwardsOrCompressionIsOff() {
        write(6, 9, 1, 1000, hex("01"))
        written()
        write(6, 9, 2, 1010, hex("01"))
        assertEquals("06" + "0003f2" + "000001" + "09" + "02000000" + "01", written())
        write(6, 9, 2, 900, hex("01"))
        assertEquals("06" + "000384" + "000001" + "09" + "02000000" + "01", written())
        write(6, 9, 2, 910, hex("01"), compress = false)
        assertEquals("06" + "00038e" + "000001" + "09" + "02000000" + "01", written())
    }

    @Test
    fun largeMessagesAreSplitIntoChunksOfTheCurrentChunkSize() {
        val data = body(300)
        write(4, 8, 1, 5, data)
        val expected = "04" + "000005" + "00012c" + "08" + "01000000" + data.copyOfRange(0, 128).toHex() +
            "c4" + data.copyOfRange(128, 256).toHex() + "c4" + data.copyOfRange(256, 300).toHex()
        assertEquals(expected, written())
        writer.chunkSize = 4096
        write(4, 8, 1, 6, data)
        assertEquals("84" + "000001" + data.toHex(), written())
    }

    @Test
    fun extendedTimestampsAreRepeatedOnEveryChunkAndKeepFullHeaders() {
        val data = body(300)
        write(6, 9, 1, 0x01000000, data)
        val expected = "06" + "ffffff" + "00012c" + "09" + "01000000" + "01000000" + data.copyOfRange(0, 128).toHex() +
            "c6" + "01000000" + data.copyOfRange(128, 256).toHex() + "c6" + "01000000" + data.copyOfRange(256, 300).toHex()
        assertEquals(expected, written())
        write(6, 9, 1, 0x01000021, hex("aa"))
        assertEquals("06" + "ffffff" + "000001" + "09" + "01000000" + "01000021" + "aa", written())
    }

    @Test
    fun timestampsAreMaskedTo32Bits() {
        write(6, 9, 1, 0x1_0000_0005L, hex("aa"))
        assertEquals("06" + "000005" + "000001" + "09" + "01000000" + "aa", written())
    }

    private fun roundTrip(chunkSize: Int, messages: List<Triple<Int, Long, Int>>, readerInput: (ByteArray) -> InputStream) {
        writer.chunkSize = chunkSize
        val bodies = messages.mapIndexed { index, (csid, ts, size) ->
            ByteArray(size) { ((it + index) % 250 + 1).toByte() }.also { write(csid, 9 + csid % 2, 1, ts, it) }
        }
        val reader = RtmpChunkReader(readerInput(out.toByteArray()))
        reader.chunkSize = chunkSize
        messages.forEachIndexed { index, (csid, ts, size) ->
            val message = reader.read()
            assertEquals(9 + csid % 2, message.type, "message $index")
            assertEquals(1, message.streamId)
            assertEquals(ts, message.timestamp, "message $index")
            assertEquals(size, message.body.size)
            assertContentEquals(bodies[index], message.body, "message $index")
        }
        assertEquals(out.size.toLong(), reader.bytesRead)
        assertFailsWith<EOFException> { reader.read() }
    }

    private val sequence = listOf(
        Triple(6, 0L, 5), Triple(4, 3L, 300), Triple(6, 33L, 127), Triple(4, 24L, 128), Triple(6, 66L, 129),
        Triple(6, 99L, 129), Triple(5, 99L, 0), Triple(6, 132L, 10000), Triple(4, 70L, 1), Triple(6, 0x01000000L, 4000),
        Triple(6, 0x01000021L, 3), Triple(4, 0x02000000L, 777),
    )

    @Test
    fun interleavedChunkStreamsRoundTripAtDifferentChunkSizes() {
        roundTrip(128, sequence) { ByteArrayInputStream(it) }
    }

    @Test
    fun roundTripSurvivesOneByteReadsAndLargeChunkSizes() {
        roundTrip(4096, sequence) { data ->
            object : InputStream() {
                private var position = 0

                override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1

                override fun read(target: ByteArray, offset: Int, length: Int): Int =
                    if (position >= data.size) -1 else {
                        target[offset] = data[position++]
                        1
                    }
            }
        }
    }

    @Test
    fun readerAppliesThePreviousDeltaToTypeThreeHeadersThatStartNewMessages() {
        val stream = "06" + "000010" + "000002" + "09" + "01000000" + "aaaa" +
            "46" + "000021" + "000002" + "09" + "cccc" + "c6" + "bbbb"
        val reader = RtmpChunkReader(ByteArrayInputStream(hex(stream)))
        assertEquals(16L, reader.read().timestamp)
        assertEquals(16L + 33, reader.read().timestamp)
        assertEquals(16L + 66, reader.read().timestamp)
    }

    @Test
    fun readerAppliesTheChunkSizeAnnouncedByTheCaller() {
        val data = body(300)
        writer.chunkSize = 100
        write(3, 20, 0, 0, data)
        val reader = RtmpChunkReader(ByteArrayInputStream(out.toByteArray()))
        reader.chunkSize = 100
        assertContentEquals(data, reader.read().body)
    }

    @Test
    fun readerDecodesTwoAndThreeByteChunkStreamIds() {
        val twoByte = hex("00" + "05" + "000000" + "000001" + "08" + "01000000" + "aa")
        val threeByte = hex("01" + "0001" + "000000" + "000001" + "08" + "01000000" + "bb")
        val reader = RtmpChunkReader(ByteArrayInputStream(twoByte + threeByte))
        assertEquals("aa", reader.read().body.toHex())
        assertEquals("bb", reader.read().body.toHex())
    }

    @Test
    fun oversizedMessagesAreRejected() {
        val reader = RtmpChunkReader(ByteArrayInputStream(hex("03" + "000000" + "ffffff" + "09" + "01000000")))
        assertFailsWith<IllegalArgumentException> { reader.read() }
    }
}
