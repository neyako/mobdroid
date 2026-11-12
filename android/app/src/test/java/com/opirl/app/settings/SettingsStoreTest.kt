package com.opirl.app.settings

import com.opirl.app.engine.media.VideoCodec
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

class SettingsStoreTest {
    private val store = SettingsStore(File(createTempDirectory().toFile(), "settings.json"), CoroutineScope(Job()))

    @Test
    fun `missing fields take defaults and unknown keys are ignored`() {
        val settings = store.decode("""{"profiles":[{"id":"a","name":"Bonded","url":"srtla://x:5000"}],"future":1}""")
        val profile = settings.profiles.single()
        assertEquals("Bonded", profile.name)
        assertEquals(Resolution.R1080, profile.resolution)
        assertEquals(30, profile.fps)
        assertEquals(null, profile.codec)
    }

    @Test
    fun `unknown enum values fall back to defaults`() {
        val settings = store.decode("""{"profiles":[{"id":"a","platform":"MIXER","codec":"VP9"}]}""")
        assertEquals(Platform.CUSTOM, settings.profiles.single().platform)
        assertEquals(null, settings.profiles.single().codec)
    }

    @Test
    fun `an empty profile list gets the default profile`() {
        assertEquals(1, store.decode("""{"profiles":[]}""").profiles.size)
    }

    @Test
    fun `srt url takes the profile latency unless it names one`() {
        assertEquals("srt://x:1?latency=2000", streamUrl(StreamProfile(url = "srt://x:1", srtLatencyMs = 2000)))
        assertEquals(
            "srtla://x:1?streamid=a&latency=2000",
            streamUrl(StreamProfile(url = "srtla://x:1?streamid=a", srtLatencyMs = 2000)),
        )
        assertEquals("srt://x:1?latency=500", streamUrl(StreamProfile(url = "srt://x:1?latency=500", srtLatencyMs = 2000)))
        assertEquals("rtmp://x/app", streamUrl(StreamProfile(url = " rtmp://x/app ", srtLatencyMs = 2000)))
    }

    @Test
    fun `an export imports back and a broken file is refused`() {
        store.update { it.copy(keepScreenOn = false, hiddenStatusItems = setOf(StatusItem.BONDING)) }
        val exported = store.exportJson()
        store.update { AppSettings() }
        assertEquals(false, store.importJson("not json"))
        assertEquals(AppSettings().keepScreenOn, store.settings.value.keepScreenOn)
        assertEquals(true, store.importJson(exported))
        assertEquals(false, store.settings.value.keepScreenOn)
        assertEquals(setOf(StatusItem.BONDING), store.settings.value.hiddenStatusItems)
    }

    @Test
    fun `settings from before the setup wizard count as set up`() {
        assertEquals(true, store.decode("""{"keepScreenOn":false}""").setupDone)
        assertEquals(false, store.decode("""{"setupDone":false}""").setupDone)
        assertEquals(false, AppSettings().setupDone)
    }

    @Test
    fun `a widget stores no settings of other widget types`() {
        store.update { it.copy(widgets = listOf(Widget(id = "w", type = WidgetType.IMAGE))) }
        val widget = store.exportJson().substringAfter("\"widgets\":[").substringBefore("]")
        assertEquals(
            """{"id":"w","name":"Widget","type":"IMAGE","text":"","textSize":15,"image":null,"url":"","browserWidth":1280,""" +
                """"browserHeight":720,"previewOnly":false,"cover":true,"hideWhenPaused":true,"removeBackground":false,"keyFrom":90,""" +
                """"keyTo":150}""",
            widget,
        )
    }

    @Test
    fun `the old microphone choice becomes a mic key`() {
        assertEquals("USB", store.decode("""{"mic":"USB"}""").micKey)
        assertEquals("PHONE", store.decode("""{"mic":"BUILT_IN"}""").micKey)
        assertEquals(null, store.decode("""{"mic":"AUTO"}""").micKey)
        assertEquals("PHONE:back", store.decode("""{"mic":"USB","micKey":"PHONE:back"}""").micKey)
    }

    @Test
    fun `the url names the transport and what it can carry`() {
        assertEquals(Transport.WHIP, transportOf(" whips://x/whip "))
        assertEquals(Transport.RIST, transportOf("rist://x:5000"))
        assertEquals(null, transportOf("x:5000"))
        val custom = StreamProfile(platform = Platform.CUSTOM)
        assertEquals(Platform.CUSTOM.codecs, custom.copy(url = "rtmp://x/app").allowedCodecs)
        assertEquals(false, VideoCodec.AV1 in custom.copy(url = "whip://x/whip").allowedCodecs)
        assertEquals(false, VideoCodec.AV1 in custom.copy(url = "rist://x:1").allowedCodecs)
        assertEquals(true, custom.copy(url = "srtla://x:1").bonded)
        assertEquals(false, custom.copy(url = "rist://x:1").bonded)
        assertEquals(true, custom.copy(url = "rist://x:1", ristBonding = true).bonded)
        assertEquals("rist://x:1", streamUrl(custom.copy(url = "rist://x:1")))
    }
}
