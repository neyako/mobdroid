package app.mobdroid.engine.rtmp

import app.mobdroid.engine.flv.ByteWriter
import app.mobdroid.engine.flv.hex
import app.mobdroid.engine.flv.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Amf0Test {
    private fun encode(value: Any?): String = ByteWriter().also { Amf0.write(it, value) }.toByteArray().toHex()

    private fun decode(text: String): Any? = Amf0.Reader(hex(text)).read()

    @Test
    fun scalarsEncodeToTheAmf0WireFormat() {
        assertEquals("00401c000000000000", encode(7.0))
        assertEquals("00" + "3ff0000000000000", encode(1))
        assertEquals("0101", encode(true))
        assertEquals("0100", encode(false))
        assertEquals("05", encode(null))
        assertEquals("02" + "0004" + "68766331", encode("hvc1"))
    }

    @Test
    fun objectsAndStrictArraysEncodeInOrder() {
        val map = linkedMapOf("app" to "live", "n" to 1.0, "b" to true, "z" to null)
        assertEquals(
            "03" + "0003" + "617070" + "02" + "0004" + "6c697665" + "0001" + "6e" + "00" + "3ff0000000000000" +
                "0001" + "62" + "0101" + "0001" + "7a" + "05" + "0000" + "09",
            encode(map),
        )
        assertEquals("0a" + "00000002" + "02" + "0004" + "68766331" + "02" + "0004" + "61763031", encode(listOf("hvc1", "av01")))
    }

    @Test
    fun commandsStartWithNameAndTransactionNumber() {
        val out = ByteWriter()
        Amf0.writeCommand(out, "publish", 0, null, "key", "live")
        assertEquals(
            "02" + "0007" + "7075626c697368" + "00" + "0000000000000000" + "05" + "02" + "0003" + "6b6579" + "02" + "0004" + "6c697665",
            out.toByteArray().toHex(),
        )
    }

    @Test
    fun valuesRoundTripThroughTheReader() {
        val value = linkedMapOf(
            "code" to "NetStream.Publish.Start",
            "level" to "status",
            "nested" to linkedMapOf("a" to listOf(1.0, "x", false, null)),
            "n" to 3.5,
        )
        assertEquals(value, decode(encode(value)))
    }

    @Test
    fun readerHandlesEcmaArraysUndefinedDatesAndLongStrings() {
        assertEquals(mapOf("a" to 1.0), decode("08" + "00000001" + "0001" + "61" + "00" + "3ff0000000000000" + "000009"))
        assertEquals(null, decode("06"))
        assertEquals(1000.0, decode("0b" + "408f400000000000" + "0000"))
        assertEquals("abc", decode("0c" + "00000003" + "616263"))
    }

    @Test
    fun readerReportsCommandArgumentsSequentially() {
        val reader = Amf0.Reader(hex("02" + "0007" + "5f726573756c74" + "00" + "3ff0000000000000" + "05" + "00" + "4000000000000000"))
        assertEquals("_result", reader.read())
        assertEquals(1.0, reader.read())
        assertEquals(null, reader.read())
        assertEquals(2.0, reader.read())
        assertEquals(false, reader.hasMore)
    }

    @Test
    fun malformedInputIsRejectedInsteadOfOverreading() {
        assertFailsWith<IllegalArgumentException> { decode("02" + "0010" + "6162") }
        assertFailsWith<IllegalArgumentException> { decode("00" + "3ff00000") }
        assertFailsWith<IllegalArgumentException> { decode("03" + "0001" + "61") }
        assertFailsWith<IllegalArgumentException> { decode("0a" + "7fffffff") }
        assertFailsWith<IllegalArgumentException> { decode("63") }
        assertFailsWith<IllegalArgumentException> { decode("0a00000001".repeat(40)) }
    }
}
