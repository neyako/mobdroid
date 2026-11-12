package com.opirl.app.widgets.text

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class TextVariables(
    val time: Long = 0,
    val bitrate: String = "",
    val bitrateAndTotal: String = "",
    val bonding: String = "",
    val resolution: String? = null,
    val fps: Int? = null,
    val speed: Double = 0.0,
    val averageSpeed: Double = 0.0,
    val altitude: Double = 0.0,
    val distance: Double = 0.0,
    val splitDistance: Double = 0.0,
    val altitudeAscent: Double = 0.0,
    val altitudeDescent: Double = 0.0,
    val splitAltitudeAscent: Double = 0.0,
    val splitAltitudeDescent: Double = 0.0,
    val slope: String = "0%",
    val weather: Weather? = null,
    val place: Place? = null,
    val muted: Boolean = false,
    val gForce: GForce? = null,
)

sealed interface TextSegment {
    data class Run(val text: String) : TextSegment

    data class Checkbox(val checked: Boolean) : TextSegment

    data object Muted : TextSegment

    data class Stars(val count: Int) : TextSegment
}

data class TextLine(val segments: List<TextSegment>) {
    val visible: Boolean get() = segments.any { it !is TextSegment.Run || it.text.isNotEmpty() }
}

private fun length(variables: TextVariables, field: LengthField): Double = when (field) {
    LengthField.ALTITUDE -> variables.altitude
    LengthField.DISTANCE -> variables.distance
    LengthField.SPLIT_DISTANCE -> variables.splitDistance
    LengthField.ALTITUDE_ASCENT -> variables.altitudeAscent
    LengthField.ALTITUDE_DESCENT -> variables.altitudeDescent
    LengthField.SPLIT_ALTITUDE_ASCENT -> variables.splitAltitudeAscent
    LengthField.SPLIT_ALTITUDE_DESCENT -> variables.splitAltitudeDescent
}

private fun oneDecimal(value: Double?) = if (value == null) "-" else formatOneDecimal(value)

fun formatText(
    parts: List<TextPart>,
    variables: TextVariables,
    options: TextSettings,
    now: Long,
    environment: TextEnvironment = TextEnvironment(),
): List<TextLine> {
    val lines = ArrayList<TextLine>()
    var line = ArrayList<TextSegment>()
    var timer = 0
    var stopwatch = 0
    var checkbox = 0
    var rating = 0
    var laps = 0
    val text = { value: String -> line.add(TextSegment.Run(value)) }
    val endLine = {
        if (line.isNotEmpty()) lines += TextLine(line)
        line = ArrayList()
    }
    val date = Instant.ofEpochMilli(variables.time).atZone(environment.zone)
    fun style(style: FormatStyle, time: Boolean) =
        (if (time) DateTimeFormatter.ofLocalizedTime(style) else DateTimeFormatter.ofLocalizedDate(style)).withLocale(environment.locale)
    for (part in parts) {
        when (part) {
            is TextPart.Literal -> text(part.text)
            TextPart.NewLine -> {
                lines += TextLine(line)
                line = ArrayList()
            }
            is TextPart.Speed -> text(
                when (part.field) {
                    SpeedField.SPEED -> formatSpeed(variables.speed, part.unit, environment)
                    SpeedField.AVERAGE_SPEED -> formatSpeed(variables.averageSpeed, part.unit, environment)
                    SpeedField.CYCLING_SPEED -> formatSpeed(0.0, part.unit, environment)
                    SpeedField.WIND -> variables.weather?.let { formatWind(it.wind, it.gust, part.unit, environment) } ?: "-"
                },
            )
            is TextPart.Length -> text(
                when (part.field) {
                    LengthField.ALTITUDE, LengthField.ALTITUDE_ASCENT, LengthField.ALTITUDE_DESCENT,
                    LengthField.SPLIT_ALTITUDE_ASCENT, LengthField.SPLIT_ALTITUDE_DESCENT,
                    -> formatAltitude(length(variables, part.field), part.unit, environment)
                    LengthField.DISTANCE, LengthField.SPLIT_DISTANCE -> formatDistance(length(variables, part.field), part.unit, environment)
                },
            )
            is TextPart.Temperature -> text(
                variables.weather?.let {
                    formatTemperature(if (part.field == TemperatureField.TEMPERATURE) it.temperature else it.feelsLike, part.unit, environment)
                } ?: "-",
            )
            is TextPart.Device -> text("-")
            is TextPart.Subtitles -> Unit
            is TextPart.Simple -> when (part.field) {
                SimpleField.CLOCK -> text(date.format(style(FormatStyle.MEDIUM, true)))
                SimpleField.SHORT_CLOCK -> text(date.format(style(FormatStyle.SHORT, true)))
                SimpleField.DATE -> text(date.format(style(FormatStyle.SHORT, false)))
                SimpleField.FULL_DATE -> text(date.format(style(FormatStyle.FULL, false)))
                SimpleField.BITRATE -> text("${variables.bitrate.ifEmpty { "-" }} Mbps")
                SimpleField.BITRATE_AND_TOTAL -> text(variables.bitrateAndTotal)
                SimpleField.BONDING -> text(variables.bonding)
                SimpleField.RESOLUTION -> text(variables.resolution.orEmpty())
                SimpleField.FPS -> text(variables.fps?.toString().orEmpty())
                SimpleField.DEBUG_OVERLAY, SimpleField.BROWSER_TITLE, SimpleField.LATEST_SUBSCRIBER, SimpleField.LATEST_FOLLOWER -> text("")
                SimpleField.SLOPE -> text(variables.slope)
                SimpleField.TIMER -> {
                    options.timers.getOrNull(timer)?.let { text(formatDuration(it.timeLeft(now) / 1000)) }
                    timer++
                }
                SimpleField.STOPWATCH -> {
                    options.stopwatches.getOrNull(stopwatch)?.let { text(formatDuration(it.elapsed(now) / 1000)) }
                    stopwatch++
                }
                SimpleField.CONDITIONS -> text(variables.weather?.let { weatherEmoji(it.code) } ?: "-")
                SimpleField.COUNTRY -> text(variables.place?.country.orEmpty())
                SimpleField.COUNTRY_FLAG -> text(countryFlag(variables.place?.countryCode))
                SimpleField.STATE -> text(variables.place?.state ?: "-")
                SimpleField.AREA -> text(variables.place?.area ?: "-")
                SimpleField.CITY -> text(variables.place?.city ?: "-")
                SimpleField.NEIGHBORHOOD -> text(variables.place?.neighborhood ?: "-")
                SimpleField.CHECKBOX -> {
                    options.checkboxes.getOrNull(checkbox)?.let { line.add(TextSegment.Checkbox(it)) }
                    checkbox++
                }
                SimpleField.RATING -> {
                    options.ratings.getOrNull(rating)?.let { line.add(TextSegment.Stars(it)) }
                    rating++
                }
                SimpleField.MUTED -> if (variables.muted) line.add(TextSegment.Muted)
                SimpleField.ACTIVE_ENERGY_BURNED, SimpleField.POWER, SimpleField.STEP_COUNT, SimpleField.WORKOUT_DISTANCE,
                SimpleField.TESLA_BATTERY_LEVEL, SimpleField.TESLA_DRIVE, SimpleField.TESLA_MEDIA,
                -> text("-")
                SimpleField.CYCLING_POWER -> text("0 W")
                SimpleField.CYCLING_CADENCE -> text("0")
                SimpleField.LAP_TIMES -> {
                    options.lapTimes.getOrNull(laps)?.let { lap ->
                        for (entry in lap.lines(now)) {
                            endLine()
                            text(entry)
                        }
                        endLine()
                    }
                    laps++
                }
                SimpleField.G_FORCE -> text(oneDecimal(variables.gForce?.now))
                SimpleField.G_FORCE_RECENT_MAX -> text(oneDecimal(variables.gForce?.recentMax))
                SimpleField.G_FORCE_MAX -> text(oneDecimal(variables.gForce?.max))
                SimpleField.SYSTEM_MONITOR -> text("-% - MB")
            }
        }
    }
    endLine()
    return lines
}
