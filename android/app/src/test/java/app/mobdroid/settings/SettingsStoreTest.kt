package app.mobdroid.settings

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
}
