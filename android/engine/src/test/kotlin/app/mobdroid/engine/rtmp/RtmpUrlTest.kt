package app.mobdroid.engine.rtmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RtmpUrlTest {
    private class Case(
        val url: String,
        val key: String,
        val secure: Boolean,
        val host: String,
        val port: Int,
        val app: String,
        val tcUrl: String,
        val streamKey: String,
    )

    private val cases = listOf(
        Case("rtmp://foo.com/app", "abc", false, "foo.com", 1935, "app", "rtmp://foo.com/app", "abc"),
        Case("rtmp://foo.com/app/live_xyz", "", false, "foo.com", 1935, "app", "rtmp://foo.com/app", "live_xyz"),
        Case("rtmp://foo.com/foobar", "", false, "foo.com", 1935, "", "rtmp://foo.com", "foobar"),
        Case("rtmp://foo.com/live/?foo=bar&a=b", "", false, "foo.com", 1935, "live", "rtmp://foo.com/live", "?foo=bar&a=b"),
        Case(
            "rtmp://foo.com/live/g:3234234?key=1234", "", false, "foo.com", 1935, "live", "rtmp://foo.com/live",
            "g:3234234?key=1234",
        ),
        Case("rtmps://a.rtmp.youtube.com:443/live2", "k", true, "a.rtmp.youtube.com", 443, "live2", "rtmps://a.rtmp.youtube.com:443/live2", "k"),
        Case("rtmps://live-api-s.facebook.com/rtmp/", "FB-1", true, "live-api-s.facebook.com", 443, "rtmp", "rtmps://live-api-s.facebook.com/rtmp", "FB-1"),
        Case("RTMP://user:pw@host:1936/app?token=x", "k", false, "host", 1936, "app?token=x", "rtmp://host:1936/app?token=x", "k"),
        Case("rtmp://[::1]:1935/live", "k", false, "::1", 1935, "live", "rtmp://[::1]:1935/live", "k"),
        Case("rtmp://host", "k", false, "host", 1935, "", "rtmp://host", "k"),
        Case("  rtmp://host/a/b/c ", "k", false, "host", 1935, "a/b/c", "rtmp://host/a/b/c", "k"),
    )

    @Test
    fun urlAndKeyAreSplitIntoConnectionParts() {
        for (case in cases) {
            val parsed = RtmpUrl.parse(case.url, case.key)
            val message = "${case.url} + ${case.key}"
            assertEquals(case.secure, parsed.secure, message)
            assertEquals(case.host, parsed.host, message)
            assertEquals(case.port, parsed.port, message)
            assertEquals(case.app, parsed.app, message)
            assertEquals(case.tcUrl, parsed.tcUrl, message)
            assertEquals(case.streamKey, parsed.streamKey, message)
        }
    }

    @Test
    fun malformedUrlsAndMissingKeysAreRejected() {
        listOf(
            "http://host/app" to "k",
            "rtmp:///app" to "k",
            "rtmp://host:99999/app" to "k",
            "rtmp://host:abc/app" to "k",
            "rtmp://host/app/" to "",
            "rtmp://host" to "",
            "not a url" to "k",
        ).forEach { (url, key) -> assertFailsWith<IllegalArgumentException>("$url + $key") { RtmpUrl.parse(url, key) } }
    }
}
