package com.opirl.app.settings

import com.opirl.app.engine.media.VideoCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeepLinkTest {
    private val belabox =
        """moblin://?{"streams":[{"name":"BELABOX%20UK","url":"srtla://uk.srt.belabox.net:5000?streamid=9812098rh9hf8942hid",""" +
            """"video":{"codec":"H.265/HEVC"},"obs":{"webSocketUrl":"ws://123.22.32.112:5465","webSocketPassword":"foobar"}}]}"""

    @Test
    fun `the stream example from Moblin's README becomes a profile`() {
        val settings = AppSettings().withDeepLink(parseDeepLink(belabox), replace = false)
        val profile = settings.profiles.last()
        assertEquals("BELABOX UK", profile.name)
        assertEquals("srtla://uk.srt.belabox.net:5000?streamid=9812098rh9hf8942hid", profile.url)
        assertEquals(Platform.CUSTOM, profile.platform)
        assertEquals(VideoCodec.HEVC, profile.codec)
        assertEquals(ObsSettings(enabled = true, url = "ws://123.22.32.112:5465", password = "foobar"), profile.obs)
        assertEquals(2, settings.profiles.size)
    }

    @Test
    fun `the quick button example hides everything but the listed buttons`() {
        val link = parseDeepLink(
            """moblin://?{"quickButtons":{"twoColumns":false,"disableAllButtons":true,""" +
                """"buttons":[{"type":"Mute","enabled":true},{"type":"Draw","enabled":true}]}}""",
        )
        val settings = AppSettings().withDeepLink(link, replace = false)
        assertEquals(QuickButtonId.entries.toSet() - QuickButtonId.MIC, settings.hiddenQuickButtons)
    }

    @Test
    fun `a stream with a taken name is replaced or kept beside the old one`() {
        val existing = AppSettings(profiles = listOf(StreamProfile(id = "a", name = "BELABOX UK", fps = 60, twitchChannel = "me")))
        val link = parseDeepLink(belabox)
        assertEquals(listOf("BELABOX UK"), link.collisions(existing))
        val replaced = existing.withDeepLink(link, replace = true).profiles.single()
        assertEquals(listOf("a", "srtla://uk.srt.belabox.net:5000?streamid=9812098rh9hf8942hid", 60, "me"), listOf(replaced.id, replaced.url, replaced.fps, replaced.twitchChannel))
        assertEquals(listOf("BELABOX UK", "BELABOX UK 2"), existing.withDeepLink(link, replace = false).profiles.map { it.name })
    }

    @Test
    fun `video, audio, transport and selection are taken from the link`() {
        val link = parseDeepLink(
            """moblin://?{"streams":[{"name":"A","url":"rtmp://live.twitch.tv/app/key+1","selected":true,""" +
                """"video":{"resolution":"1280x720","fps":60,"bitrate":4500000,"codec":"H.265/HEVC","maxKeyFrameInterval":0},""" +
                """"audio":{"bitrate":96000},"srt":{"latency":1500,"adaptiveBitrateEnabled":false},"twitch":{"channelName":" me ","channelId":"1"}}]}""",
        )
        val settings = AppSettings().withDeepLink(link, replace = false)
        val profile = settings.profiles.last()
        assertEquals(profile.id, settings.selectedProfileId)
        assertEquals(
            StreamProfile(
                id = profile.id,
                name = "A",
                platform = Platform.TWITCH,
                url = "rtmp://live.twitch.tv/app/key+1",
                resolution = Resolution.R720,
                fps = 60,
                bitrateKbps = 4500,
                codec = null,
                audioBitrateKbps = 96,
                srtLatencyMs = 1500,
                adaptiveBitrate = false,
                twitchChannel = "me",
            ),
            profile,
        )
    }

    @Test
    fun `remote control settings are applied`() {
        val link = parseDeepLink(
            """moblin://?{"remoteControl":{"password":"pw","streamer":{"enabled":true,"url":"ws://10.0.0.2:2345"},""" +
                """"assistant":{"enabled":false,"port":2345,"relay":{"enabled":true,"baseUrl":"wss://relay.example","bridgeId":"b"}}}}""",
        )
        val remote = AppSettings().withDeepLink(link, replace = false).remoteControl
        assertEquals(
            RemoteControlSettings(password = "pw", streamer = true, streamerUrl = "ws://10.0.0.2:2345", relay = true, relayUrl = "wss://relay.example", bridgeId = "b"),
            remote,
        )
    }

    @Test
    fun `what the link cannot mean is refused with the reason`() {
        val reason = { link: String -> assertFailsWith<DeepLinkException> { parseDeepLink(link) }.message }
        assertEquals("Not a moblin:// link", reason("https://example.com"))
        assertEquals("The link cannot be read", reason("moblin://host?{}"))
        assertEquals("The link cannot be read", reason("moblin://?{broken"))
        assertEquals("Unsupported stream URL", reason("""moblin://?{"streams":[{"name":"A","url":"whip://x"}]}"""))
        assertEquals("Unsupported video FPS", reason("""moblin://?{"streams":[{"name":"A","url":"srt://x:1","video":{"fps":15}}]}"""))
        assertEquals("Unsupported video resolution", reason("""moblin://?{"streams":[{"name":"A","url":"srt://x:1","video":{"resolution":"1024x768"}}]}"""))
        assertEquals("Video bitrate out of range", reason("""moblin://?{"streams":[{"name":"A","url":"srt://x:1","video":{"bitrate":10}}]}"""))
        assertEquals("Malformed WebSocket URL", reason("""moblin://?{"remoteControl":{"password":"p","streamer":{"enabled":true,"url":"http://x"}}}"""))
    }

    @Test
    fun `a created link imports back as the same stream`() {
        val profile = StreamProfile(
            name = "Tên luồng & more",
            platform = Platform.YOUTUBE,
            url = "rtmps://a.rtmps.youtube.com/live2",
            streamKey = "ab+c/d=",
            codec = VideoCodec.HEVC,
            resolution = Resolution.R1440,
            fps = 60,
            bitrateKbps = 12000,
            audioBitrateKbps = 160,
            keyFrameIntervalSeconds = 3,
            kickChannel = "kick",
            obs = ObsSettings(enabled = true, url = "192.168.1.2:4455", password = "p w"),
        )
        val imported = AppSettings().withDeepLink(parseDeepLink(createDeepLink(listOf(profile))), replace = false).profiles.last()
        assertEquals(
            profile.copy(
                id = imported.id,
                url = "rtmps://a.rtmps.youtube.com/live2/ab+c/d=",
                streamKey = "",
                obs = profile.obs.copy(url = "ws://192.168.1.2:4455"),
            ),
            imported,
        )
    }
}
