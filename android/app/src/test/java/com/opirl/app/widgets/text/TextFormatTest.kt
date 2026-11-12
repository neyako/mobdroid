package com.opirl.app.widgets.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextFormatTest {
    @Test
    fun everyFieldIsRecognisedWhateverItsCase() {
        for (field in SimpleField.entries) {
            assertEquals(listOf(TextPart.Simple(field)), parseTextFormat("{${field.tag.uppercase()}}"), field.name)
        }
        for (field in SpeedField.entries) {
            assertEquals(listOf(TextPart.Speed(field, SpeedUnit.SYSTEM)), parseTextFormat("{${field.tag}}"), field.name)
        }
        for (field in LengthField.entries) {
            assertEquals(listOf(TextPart.Length(field, LengthUnit.SYSTEM)), parseTextFormat("{${field.tag}}"), field.name)
        }
        for (field in TemperatureField.entries) {
            assertEquals(listOf(TextPart.Temperature(field, TemperatureUnit.SYSTEM)), parseTextFormat("{${field.tag}}"), field.name)
        }
        for (field in DeviceField.entries) {
            assertEquals(listOf(TextPart.Device(field, "")), parseTextFormat("{${field.tag}}"), field.name)
        }
        assertEquals(listOf(TextPart.Subtitles(null)), parseTextFormat("{subtitles}"))
    }

    @Test
    fun unitsAreReadOrTheTagStaysText() {
        assertEquals(listOf(TextPart.Speed(SpeedField.SPEED, SpeedUnit.KILOMETERS_PER_HOUR)), parseTextFormat("{speed:km/h}"))
        assertEquals(listOf(TextPart.Speed(SpeedField.WIND, SpeedUnit.MILES_PER_HOUR)), parseTextFormat("{Wind:MPH}"))
        assertEquals(listOf(TextPart.Length(LengthField.SPLIT_DISTANCE, LengthUnit.NAUTICAL_MILES)), parseTextFormat("{splitDistance:nmi}"))
        assertEquals(listOf(TextPart.Temperature(TemperatureField.FEELS_LIKE, TemperatureUnit.FAHRENHEIT)), parseTextFormat("{feelsLikeTemperature:F}"))
        assertEquals(listOf(TextPart.Device(DeviceField.HEART_RATE, "polar h10")), parseTextFormat("{heartRate:Polar H10}"))
        assertEquals(listOf(TextPart.Subtitles("sv")), parseTextFormat("{subtitles:sv}"))
        assertEquals(listOf(TextPart.Literal("{speed:furlongs}")), parseTextFormat("{speed:furlongs}"))
        assertEquals(listOf(TextPart.Literal("{time:km/h}")), parseTextFormat("{time:km/h}"))
        assertEquals(listOf(TextPart.Literal("{speed:}")), parseTextFormat("{speed:}"))
    }

    @Test
    fun textAndLineBreaksAreKeptInOrder() {
        assertEquals(
            listOf(
                TextPart.Literal("a "),
                TextPart.Simple(SimpleField.CLOCK),
                TextPart.NewLine,
                TextPart.Literal("b"),
                TextPart.NewLine,
                TextPart.Literal("c"),
                TextPart.Simple(SimpleField.DATE),
            ),
            parseTextFormat("a {time}\nb\\nc{date}"),
        )
    }

    @Test
    fun unknownAndUnfinishedTagsStayText() {
        assertEquals(listOf(TextPart.Literal("{nope} {time {1}")), parseTextFormat("{nope} {time {1}"))
        assertEquals(listOf(TextPart.Literal("{{"), TextPart.Simple(SimpleField.CLOCK), TextPart.Literal("}")), parseTextFormat("{{{time}}"))
    }

    @Test
    fun aCheckboxIsNamedByTheTextAfterIt() {
        val parts = parseTextFormat("{checkbox} Buy milk\n{checkbox}\n{checkbox}{checkbox} x {time} y\n{checkbox}")
        assertEquals(listOf("Buy milk", "", "", "x", ""), parts.checkboxLabels())
    }

    @Test
    fun theFieldsDecideWhatTheWidgetNeeds() {
        assertFalse(parseTextFormat("{time} {date} {bitrate}").needsLocation)
        assertTrue(parseTextFormat("{speed}").needsLocation)
        assertTrue(parseTextFormat("{altitude:ft}").needsLocation)
        assertTrue(parseTextFormat("{city}").needsLocation)
        assertTrue(parseTextFormat("{temperature}").needsLocation)
        assertFalse(parseTextFormat("{cyclingSpeed}").needsLocation)
        assertTrue(parseTextFormat("{distance}").needsTrip)
        assertFalse(parseTextFormat("{altitude}").needsTrip)
        assertTrue(parseTextFormat("{wind:m/s}").needsWeather)
        assertTrue(parseTextFormat("{countryFlag}").needsGeography)
        assertTrue(parseTextFormat("{gForceMax}").needsGForce)
        assertTrue(parseTextFormat("{fps}").needsStats)
        assertTrue(parseTextFormat("{muted}").needsMuted)
        assertFalse(parseTextFormat("static {checkbox} {timer}").live)
        assertTrue(parseTextFormat("{shortTime}").live)
    }

    @Test
    fun theOptionListsFollowTheFieldsInTheText() {
        val parts = parseTextFormat("{timer} {timer} {stopwatch} {checkbox} {rating} {lapTimes}")
        val synced = TextSettings(timers = listOf(TextTimer(15, 5)), checkboxes = listOf(true, true, true)).synced(parts)
        assertEquals(listOf(TextTimer(15, 5), TextTimer()), synced.timers)
        assertEquals(listOf(TextStopwatch()), synced.stopwatches)
        assertEquals(listOf(true), synced.checkboxes)
        assertEquals(listOf(0), synced.ratings)
        assertEquals(listOf(TextLaps()), synced.lapTimes)
        assertTrue(synced.isSynced(parts))
    }
}
