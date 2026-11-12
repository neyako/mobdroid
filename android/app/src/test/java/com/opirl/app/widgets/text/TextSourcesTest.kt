package com.opirl.app.widgets.text

import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Scene
import com.opirl.app.settings.SceneWidget
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.widgets.slideshow.Slide
import com.opirl.app.widgets.slideshow.SlideshowSettings
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class TextSourcesTest {
    private val openMeteo = """
        {"latitude":10.79,"longitude":106.71,"current_units":{"temperature_2m":"°C"},
         "current":{"time":"2026-11-11T15:00","interval":900,"temperature_2m":26.3,"apparent_temperature":32.8,
         "weather_code":1,"wind_speed_10m":0.18,"wind_gusts_10m":1.50}}
    """.trimIndent()

    @Test
    fun anOpenMeteoAnswerBecomesWeather() {
        val weather = assertNotNull(parseWeather(openMeteo))
        assertEquals(1, weather.code)
        assertEquals(26.3, weather.temperature)
        assertEquals(32.8, weather.feelsLike)
        assertEquals(0.18, weather.wind)
        assertEquals(1.5, weather.gust)
        assertNull(parseWeather(openMeteo.replace("\"weather_code\":1,", "")))
        assertNull(parseWeather("""{"error":true,"reason":"nope"}"""))
        assertNull(assertNotNull(parseWeather(openMeteo.replace(",\"wind_gusts_10m\":1.50", ""))).gust)
    }

    @Test
    fun theWeatherRequestIsTheSameInAnyLocale() {
        val before = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val url = weatherUrl(10.79086, 106.71088)
            assertTrue("latitude=10.7909&longitude=106.7109&current=temperature_2m,apparent_temperature,weather_code" in url)
            assertTrue(url.endsWith("&wind_speed_unit=ms"))
        } finally {
            Locale.setDefault(before)
        }
    }

    private fun text(id: String, text: String) = Widget(id = id, type = WidgetType.TEXT, text = text)

    private val settings = AppSettings(
        widgets = listOf(
            text("plain", "hello"),
            text("weather", "{temperature}"),
            text("hidden", "{gForce} {muted}"),
            text("fps", "{fps}"),
            text("elsewhere", "{city}"),
            Widget(id = "nested", type = WidgetType.SCENE, sceneId = "inner"),
            Widget(id = "back", type = WidgetType.SCENE, sceneId = "main"),
            Widget(
                id = "slides",
                type = WidgetType.SLIDESHOW,
                slideshow = SlideshowSettings(listOf(Slide(widgetId = "fps"), Slide(widgetId = "nested"))),
            ),
        ),
        scenes = listOf(
            Scene(
                id = "main",
                widgets = listOf(SceneWidget("plain"), SceneWidget("nested"), SceneWidget("slides"), SceneWidget("hidden", enabled = false)),
            ),
            Scene(id = "inner", widgets = listOf(SceneWidget("weather"), SceneWidget("back"))),
            Scene(id = "other", widgets = listOf(SceneWidget("elsewhere"))),
        ),
        selectedSceneId = "main",
    )

    @Test
    fun onlyTextWidgetsPlacedInTheSelectedSceneAreCounted() {
        assertEquals(listOf("plain", "weather", "fps"), placedTextWidgets(settings).map { it.id })
    }

    @Test
    fun whatTheSourcesRunFollowsThePlacedText() {
        assertEquals(TextDemand(weather = true, stats = true), textDemand(settings))
        val moved = settings.copy(selectedSceneId = "other")
        assertEquals(TextDemand(geography = true), textDemand(moved))
        val racing = settings.copy(
            widgets = settings.widgets.map {
                if (it.id == "plain") it.copy(text = "{distance}", textOptions = TextSettings(resetWhenGoingLive = true)) else it
            },
        )
        assertTrue(textDemand(racing).resetWhenGoingLive)
        assertEquals(TextDemand(), textDemand(AppSettings()))
    }

    @Test
    fun settingsFromOlderVersionsStillLoad() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }
        assertEquals(TextSettings(), json.decodeFromString<TextSettings>("""{"placeholder":false}"""))
        assertEquals(TextSettings(design = TextDesign.DEFAULT), json.decodeFromString<TextSettings>("""{"design":"FUTURE"}"""))
        val settings = TextSettings(
            timers = listOf(TextTimer(15, 1_794_000_000_000)),
            stopwatches = listOf(TextStopwatch(5_000, 1_794_000_000_000, true)),
            lapTimes = listOf(TextLaps(1_794_000_000_000, listOf(1_000, LAP_FINISHED, 0))),
            checkboxes = listOf(true),
            ratings = listOf(3),
            family = TextFamily.CONDENSED,
        )
        assertEquals(settings, json.decodeFromString<TextSettings>(json.encodeToString(TextSettings.serializer(), settings)))
    }
}
