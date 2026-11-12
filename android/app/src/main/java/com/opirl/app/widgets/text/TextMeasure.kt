package com.opirl.app.widgets.text

import java.text.NumberFormat
import java.time.ZoneId
import java.util.Locale

class TextEnvironment(val locale: Locale = Locale.getDefault(), val zone: ZoneId = ZoneId.systemDefault()) {
    private val region = locale.country

    val milesPerHour = region in setOf("US", "GB", "LR", "MM")
    val fahrenheit = region in setOf("US", "BS", "BZ", "KY", "PW", "LR")
    val feetAltitude = region == "US"
    val imperialDistance = region in setOf("US", "GB", "LR", "MM")

    fun number(value: Double, decimals: Int): String = NumberFormat.getNumberInstance(locale).run {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
        format(value)
    }
}

class Weather(
    val code: Int,
    val temperature: Double,
    val feelsLike: Double,
    val wind: Double,
    val gust: Double?,
)

class Place(
    val country: String?,
    val countryCode: String?,
    val state: String?,
    val area: String?,
    val city: String?,
    val neighborhood: String?,
)

class GForce(val now: Double, val recentMax: Double, val max: Double)

private const val FEET = 0.3048
private const val YARD = 0.9144
private const val MILE = 1609.344
private const val NAUTICAL_MILE = 1852.0
private const val LIGHT_YEAR = 9.4607304725808e15

fun formatSpeed(metersPerSecond: Double, unit: SpeedUnit, environment: TextEnvironment): String {
    val speed = maxOf(metersPerSecond, 0.0)
    val resolved = when {
        unit != SpeedUnit.SYSTEM -> unit
        environment.milesPerHour -> SpeedUnit.MILES_PER_HOUR
        else -> SpeedUnit.KILOMETERS_PER_HOUR
    }
    val value = when (resolved) {
        SpeedUnit.KILOMETERS_PER_HOUR -> speed * 3.6
        SpeedUnit.MILES_PER_HOUR -> speed * 3600 / MILE
        else -> speed
    }
    return "${environment.number(value, 0)} ${resolved.symbol}"
}

private fun metersTo(meters: Double, unit: LengthUnit): Double = when (unit) {
    LengthUnit.KILOMETERS -> meters / 1000
    LengthUnit.FEET -> meters / FEET
    LengthUnit.YARDS -> meters / YARD
    LengthUnit.MILES -> meters / MILE
    LengthUnit.NAUTICAL_MILES -> meters / NAUTICAL_MILE
    LengthUnit.LIGHT_YEARS -> meters / LIGHT_YEAR
    else -> meters
}

private fun formatLength(meters: Double, unit: LengthUnit, environment: TextEnvironment): String =
    "${environment.number(metersTo(meters, unit), 0)} ${unit.symbol}"

fun formatAltitude(meters: Double, unit: LengthUnit, environment: TextEnvironment): String {
    val resolved = if (unit == LengthUnit.SYSTEM) {
        if (environment.feetAltitude) LengthUnit.FEET else LengthUnit.METERS
    } else {
        unit
    }
    return formatLength(meters, resolved, environment)
}

fun formatDistance(meters: Double, unit: LengthUnit, environment: TextEnvironment): String {
    if (unit != LengthUnit.SYSTEM) return formatLength(meters, unit, environment)
    val magnitude = Math.abs(meters)
    return if (environment.imperialDistance) {
        if (magnitude < MILE / 10) {
            "${environment.number(meters / FEET, 1)} ft"
        } else {
            "${environment.number(meters / MILE, 1)} mi"
        }
    } else if (magnitude < 1000) {
        "${environment.number(meters, 1)} m"
    } else {
        "${environment.number(meters / 1000, 1)} km"
    }
}

fun formatTemperature(celsius: Double, unit: TemperatureUnit, environment: TextEnvironment): String {
    val resolved = when {
        unit != TemperatureUnit.SYSTEM -> unit
        environment.fahrenheit -> TemperatureUnit.FAHRENHEIT
        else -> TemperatureUnit.CELSIUS
    }
    return when (resolved) {
        TemperatureUnit.KELVIN -> "${environment.number(celsius + 273.15, 0)} K"
        TemperatureUnit.FAHRENHEIT -> "${environment.number(celsius * 9 / 5 + 32, 0)}°F"
        else -> "${environment.number(celsius, 0)}°C"
    }
}

fun formatWind(speed: Double, gust: Double?, unit: SpeedUnit, environment: TextEnvironment): String {
    val resolved = when {
        unit != SpeedUnit.SYSTEM -> unit
        environment.milesPerHour -> SpeedUnit.MILES_PER_HOUR
        else -> SpeedUnit.METERS_PER_SECOND
    }
    val convert = { value: Double ->
        when (resolved) {
            SpeedUnit.KILOMETERS_PER_HOUR -> value * 3.6
            SpeedUnit.MILES_PER_HOUR -> value * 3600 / MILE
            else -> value
        }
    }
    return if (gust == null) {
        "${environment.number(convert(speed), 0)} ${resolved.symbol}"
    } else {
        "${convert(speed).toInt()} (${convert(gust).toInt()}) ${resolved.symbol}"
    }
}

fun formatDuration(seconds: Long): String {
    val total = maxOf(seconds, 0)
    val parts = listOf(total / 86400 to "d", total / 3600 % 24 to "h", total / 60 % 60 to "m", total % 60 to "s")
        .filter { it.first != 0L }
    return if (parts.isEmpty()) "0s" else parts.joinToString(" ") { "${it.first}${it.second}" }
}

fun formatOneDecimal(value: Double): String = "%.1f".format(Locale.ROOT, value)

fun formatBitrate(bitsPerSecond: Long): String = formatOneDecimal(bitsPerSecond / 1_000_000.0)

private fun decimalBytes(value: Long): Pair<String, String> {
    val units = listOf("bytes", "KB", "MB", "GB", "TB")
    var scaled = value.toDouble()
    var unit = 0
    while (scaled >= 1000 && unit < units.lastIndex) {
        scaled /= 1000
        unit++
    }
    val decimals = if (unit <= 1) 0 else if (unit == 2) 1 else 2
    val number = NumberFormat.getNumberInstance(Locale.ROOT).run {
        maximumFractionDigits = decimals
        minimumFractionDigits = 0
        format(scaled)
    }
    return number to units[unit]
}

fun formatBitrateAndTotal(bitsPerSecond: Long, totalBytes: Long): String {
    val (rate, rateUnit) = decimalBytes(bitsPerSecond)
    val speedUnit = if (rateUnit == "bytes") "bps" else "${rateUnit.first()}bps"
    val (total, totalUnit) = decimalBytes(totalBytes)
    return "$rate $speedUnit ($total $totalUnit)"
}

fun formatBonding(shares: List<Pair<String, Int>>): String = shares.joinToString(", ") { "${it.second}% ${it.first}" }

fun resolutionLabel(width: Int, height: Int): String {
    val dimension = minOf(width, height)
    return if (dimension == 2160) "4K" else "${dimension}p"
}

fun countryFlag(countryCode: String?): String =
    countryCode?.uppercase()?.map { String(Character.toChars(127_397 + it.code)) }?.joinToString("").orEmpty()

fun weatherEmoji(code: Int): String = when (code) {
    0 -> "☀️"
    1 -> "🌤️"
    2 -> "⛅"
    3 -> "☁️"
    45, 48 -> "🌫️"
    51, 53, 55, 80, 81 -> "🌦️"
    56, 57, 61, 63, 65, 66, 67, 82 -> "🌧️"
    71, 73, 77, 85 -> "🌨️"
    75, 86 -> "❄️"
    95, 96, 99 -> "⛈️"
    else -> "-"
}
