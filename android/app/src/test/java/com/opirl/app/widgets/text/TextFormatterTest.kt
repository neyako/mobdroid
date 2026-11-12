package com.opirl.app.widgets.text

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class TextFormatterTest {
    private val metric = TextEnvironment(Locale.GERMANY, ZoneId.of("Europe/Berlin"))
    private val american = TextEnvironment(Locale.US, ZoneId.of("America/New_York"))
    private val now = ZonedDateTime.parse("2026-11-11T14:34:56+01:00[Europe/Berlin]").toInstant().toEpochMilli()

    private fun render(
        format: String,
        variables: TextVariables = TextVariables(time = now),
        options: TextSettings = TextSettings(),
        environment: TextEnvironment = metric,
    ): List<String> = formatText(parseTextFormat(format), variables, options, now, environment).map { line ->
        line.segments.joinToString("") {
            when (it) {
                is TextSegment.Run -> it.text
                is TextSegment.Checkbox -> if (it.checked) "[x]" else "[ ]"
                TextSegment.Muted -> "(muted)"
                is TextSegment.Stars -> "*".repeat(it.count)
            }
        }
    }

    @Test
    fun theClockFollowsTheLocale() {
        assertEquals(listOf("14:34:56 / 14:34"), render("{time} / {shortTime}"))
        assertEquals(listOf("8:34:56 AM"), render("{time}", environment = american).map { it.replace('\u202F', ' ') })
    }

    @Test
    fun speedUsesTheLocalesUnitOrTheOneAsked() {
        val variables = TextVariables(speed = 10.0)
        assertEquals(listOf("36 km/h 22 mph 10 m/s"), render("{speed} {speed:mph} {speed:m/s}", variables))
        assertEquals(listOf("22 mph"), render("{speed}", variables, environment = american))
        assertEquals(listOf("0 km/h"), render("{speed}", TextVariables(speed = -1.0)))
    }

    @Test
    fun lengthsAreWholeNumbersExceptTheDefaultDistance() {
        val variables = TextVariables(altitude = 100.0, distance = 1234.5, splitDistance = 500.0, altitudeAscent = 3.4)
        assertEquals(listOf("100 m 328 ft"), render("{altitude} {altitude:ft}", variables))
        assertEquals(listOf("328 ft"), render("{altitude}", variables, environment = american))
        assertEquals(listOf("1,2 km 500 m"), render("{distance} {splitDistance}", variables))
        assertEquals(listOf("1 km 1,350 yd"), render("{distance:km} {distance:yd}", variables, environment = american))
        assertEquals(listOf("3 m"), render("{altitudeAscent}", variables))
    }

    @Test
    fun temperatureAndWindNeedWeatherAndAreDashesWithout() {
        val weather = Weather(2, 21.4, 19.6, 3.2, 7.9)
        val variables = TextVariables(weather = weather)
        assertEquals(listOf("21°C 20°C 295 K 71°F"), render("{temperature} {feelsLikeTemperature} {temperature:k} {temperature:f}", variables))
        assertEquals(listOf("71°F"), render("{temperature}", variables, environment = american))
        assertEquals(listOf("3 (7) m/s 7 (17) mph"), render("{wind} {wind:mph}", variables))
        assertEquals(listOf("3 m/s"), render("{wind}", TextVariables(weather = Weather(2, 1.0, 1.0, 3.2, null))))
        assertEquals(listOf("- - -"), render("{conditions} {temperature} {wind}"))
    }

    @Test
    fun placeFieldsFallBackTheWayTheIosAppDoes() {
        val place = Place("Vietnam", "VN", "Ho Chi Minh City", null, "Saigon", null)
        val format = "{country} {countryFlag} {state} {area} {city} {neighborhood}"
        assertEquals(listOf("Vietnam 🇻🇳 Ho Chi Minh City - Saigon -"), render(format, TextVariables(place = place)))
        assertEquals(listOf("  - - - -"), render(format))
    }

    @Test
    fun weatherCodesBecomeTheEmojiTheIosAppShows() {
        assertEquals("☀️ ⛅ ☁️ 🌫️ 🌦️ 🌧️ 🌨️ ❄️ ⛈️", listOf(0, 2, 3, 45, 51, 63, 71, 75, 95).joinToString(" ") { weatherEmoji(it) })
        assertEquals(listOf("⛅"), render("{conditions}", TextVariables(weather = Weather(2, 1.0, 1.0, 1.0, null))))
    }

    @Test
    fun fieldsWithoutADataSourceRenderTheirEmptyValue() {
        assertEquals(
            listOf(listOf("-", "-", "-", "-", "-", "-", "0 W", "0", "0 km/h", "-", "-", "-", "-", "-", "-% - MB").joinToString(" ")),
            render(
                "{heartRate} {activeEnergyBurned} {power} {stepCount} {workoutDistance} {teslaDrive} {cyclingPower} {cyclingCadence} " +
                    "{cyclingSpeed} {runningPace:x} {runningCadence:x} {runningDistance:x} {teslaBatteryLevel} {teslaMedia} {systemMonitor}",
            ),
        )
        assertEquals(listOf(" Subtitles"), render("{debugOverlay}{browserTitle}{latestSubscriber}{latestFollower}{subtitles} Subtitles"))
    }

    @Test
    fun streamFieldsShowTheirDashesWhenOffline() {
        assertEquals(listOf("- Mbps"), render("{bitrate}"))
        val live = TextVariables(bitrate = "5.2", bitrateAndTotal = "5.2 Mbps (123 MB)", bonding = "60% Cellular, 40% Wi-Fi", resolution = "1080p", fps = 30)
        val format = "{bitrate} | {bitrateAndTotal} | {bonding} | {resolution}@{fps}"
        assertEquals(listOf("5.2 Mbps | 5.2 Mbps (123 MB) | 60% Cellular, 40% Wi-Fi | 1080p@30"), render(format, live))
    }

    @Test
    fun gForceIsOneDecimalOrDash() {
        assertEquals(listOf("1.0 1.4 2.2"), render("{gForce} {gForceRecentMax} {gForceMax}", TextVariables(gForce = GForce(1.0, 1.44, 2.2))))
        assertEquals(listOf("-"), render("{gForce}"))
    }

    @Test
    fun timersAndStopwatchesAreTakenInOrderAndSkippedWithoutState() {
        val options = TextSettings(
            timers = listOf(TextTimer(5, now + 61_500)),
            stopwatches = listOf(TextStopwatch(total = 3_600_000, startedAt = now - 2000, running = true)),
        )
        assertEquals(listOf("1m 1s | 1h 2s|"), render("{timer} | {stopwatch}|{timer}{stopwatch}", options = options))
        assertEquals(listOf("0s"), render("{timer}", options = TextSettings(timers = listOf(TextTimer(5, now - 5000)))))
    }

    @Test
    fun checkboxesRatingsAndMutedAreSymbols() {
        val options = TextSettings(checkboxes = listOf(true, false), ratings = listOf(4))
        assertEquals(listOf("[x] a", "[ ] b ", "****"), render("{checkbox} a\n{checkbox} b {muted}\n{rating}", options = options))
        assertEquals(listOf("[ ](muted)"), render("{checkbox}{muted}", TextVariables(muted = true), TextSettings(checkboxes = listOf(false))))
    }

    @Test
    fun lapTimesTakeTheirOwnLines() {
        val laps = TextLaps(start = now - 5000, laps = listOf(61_000, LAP_FINISHED, 0, 0))
        val lines = render("Race{lapTimes}end", options = TextSettings(lapTimes = listOf(laps)))
        assertEquals(listOf("Race", "Lap 1 1m 1s", "🏁 Finished 🏁", "Lap 1 0s", "Lap 2 5s", "end"), lines)
    }

    @Test
    fun lineBreaksBetweenFieldsKeepEmptyLinesButTheyAreNotVisible() {
        val lines = formatText(parseTextFormat("a\n\n{country}\nb"), TextVariables(), TextSettings(), now, metric)
        assertEquals(listOf(true, false, false, true), lines.map { it.visible })
    }

    @Test
    fun durationsDropZeroUnits() {
        assertEquals(listOf("0s", "59s", "1m", "1h 1m 1s", "1h 1s", "1d 1h 1m 1s"), listOf(0L, 59, 60, 3661, 3601, 90061).map(::formatDuration))
    }

    @Test
    fun theBitrateAndTotalAreDecimalUnits() {
        assertEquals("5.2 Mbps (123 MB)", formatBitrateAndTotal(5_200_000, 123_000_000))
        assertEquals("812 Kbps (1.57 GB)", formatBitrateAndTotal(812_000, 1_570_000_000))
        assertEquals("900 bps (0 bytes)", formatBitrateAndTotal(900, 0))
    }
}
