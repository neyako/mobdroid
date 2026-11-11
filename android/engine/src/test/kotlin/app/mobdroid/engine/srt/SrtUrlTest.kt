package app.mobdroid.engine.srt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SrtUrlTest {
    @Test
    fun parsesPlainSrt() {
        val url = SrtUrl.parse("srt://example.com:4000?streamid=live%2Fmain&latency=1200")
        assertFalse(url.bonded)
        assertEquals("example.com", url.host)
        assertEquals(4000, url.port)
        assertEquals("live/main", url.streamId)
        assertEquals(1200, url.latencyMs)
    }

    @Test
    fun parsesSrtla() {
        val url = SrtUrl.parse("srtla://relay.example.com:5000?streamid=abc")
        assertTrue(url.bonded)
        assertEquals("abc", url.streamId)
        assertEquals(DEFAULT_SRT_LATENCY_MS, url.latencyMs)
    }

    @Test
    fun keepsRawHashAndSpecialCharactersInStreamId() {
        val url = SrtUrl.parse("srt://1.2.3.4:9000?streamid=#!::r=live/x,m=publish&latency=500")
        assertEquals("#!::r=live/x,m=publish", url.streamId)
        assertEquals(500, url.latencyMs)
    }

    @Test
    fun decodesPercentEncodingWithoutTouchingPlus() {
        val url = SrtUrl.parse("srt://1.2.3.4:9000?streamid=%23%21%3A%3Ar%3Da+b%C3%A9")
        assertEquals("#!::r=a+bé", url.streamId)
    }

    @Test
    fun parsesIpv6Literal() {
        val url = SrtUrl.parse("srt://[2001:db8::1]:7000")
        assertEquals("2001:db8::1", url.host)
        assertEquals(7000, url.port)
        assertNull(url.streamId)
    }

    @Test
    fun ignoresPathAndUnknownParameters() {
        val url = SrtUrl.parse("SRT://host:1234/path?foo=bar&streamid=x&mode=caller&pbkeylen=16")
        assertEquals("host", url.host)
        assertEquals("x", url.streamId)
    }

    @Test
    fun rejectsPassphraseWithClearError() {
        val error = assertFailsWith<IllegalArgumentException> {
            SrtUrl.parse("srt://host:1234?passphrase=secretsecret")
        }
        assertEquals("SRT encryption (passphrase) is not supported", error.message)
    }

    @Test
    fun rejectsListenerMode() {
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host:1234?mode=listener") }
    }

    @Test
    fun rejectsBadLatency() {
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host:1234?latency=70000") }
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host:1234?latency=abc") }
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host:1234?latency=-1") }
    }

    @Test
    fun rejectsMissingPortSchemeAndHost() {
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host") }
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://host:0") }
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("srt://:1234") }
        assertFailsWith<IllegalArgumentException> { SrtUrl.parse("rtmp://host:1234") }
    }
}
