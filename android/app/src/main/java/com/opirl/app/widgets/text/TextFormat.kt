package com.opirl.app.widgets.text

enum class SpeedUnit(val symbol: String) {
    SYSTEM(""),
    METERS_PER_SECOND("m/s"),
    KILOMETERS_PER_HOUR("km/h"),
    MILES_PER_HOUR("mph"),
}

enum class LengthUnit(val symbol: String) {
    SYSTEM(""),
    METERS("m"),
    KILOMETERS("km"),
    FEET("ft"),
    YARDS("yd"),
    MILES("mi"),
    NAUTICAL_MILES("nmi"),
    LIGHT_YEARS("ly"),
}

enum class TemperatureUnit(val symbol: String) {
    SYSTEM(""),
    KELVIN("k"),
    CELSIUS("c"),
    FAHRENHEIT("f"),
}

enum class SimpleField(val tag: String) {
    CLOCK("time"),
    SHORT_CLOCK("shorttime"),
    DATE("date"),
    FULL_DATE("fulldate"),
    BITRATE("bitrate"),
    BITRATE_AND_TOTAL("bitrateandtotal"),
    BONDING("bonding"),
    RESOLUTION("resolution"),
    FPS("fps"),
    DEBUG_OVERLAY("debugoverlay"),
    SLOPE("slope"),
    TIMER("timer"),
    STOPWATCH("stopwatch"),
    CONDITIONS("conditions"),
    COUNTRY("country"),
    COUNTRY_FLAG("countryflag"),
    STATE("state"),
    AREA("area"),
    CITY("city"),
    NEIGHBORHOOD("neighborhood"),
    CHECKBOX("checkbox"),
    RATING("rating"),
    MUTED("muted"),
    ACTIVE_ENERGY_BURNED("activeenergyburned"),
    POWER("power"),
    STEP_COUNT("stepcount"),
    WORKOUT_DISTANCE("workoutdistance"),
    TESLA_BATTERY_LEVEL("teslabatterylevel"),
    TESLA_DRIVE("tesladrive"),
    TESLA_MEDIA("teslamedia"),
    CYCLING_POWER("cyclingpower"),
    CYCLING_CADENCE("cyclingcadence"),
    LAP_TIMES("laptimes"),
    BROWSER_TITLE("browsertitle"),
    G_FORCE("gforce"),
    G_FORCE_RECENT_MAX("gforcerecentmax"),
    G_FORCE_MAX("gforcemax"),
    LATEST_SUBSCRIBER("latestsubscriber"),
    LATEST_FOLLOWER("latestfollower"),
    SYSTEM_MONITOR("systemmonitor"),
}

enum class SpeedField(val tag: String) {
    SPEED("speed"),
    AVERAGE_SPEED("averagespeed"),
    CYCLING_SPEED("cyclingspeed"),
    WIND("wind"),
}

enum class LengthField(val tag: String) {
    ALTITUDE("altitude"),
    DISTANCE("distance"),
    SPLIT_DISTANCE("splitdistance"),
    ALTITUDE_ASCENT("altitudeascent"),
    ALTITUDE_DESCENT("altitudedescent"),
    SPLIT_ALTITUDE_ASCENT("splitaltitudeascent"),
    SPLIT_ALTITUDE_DESCENT("splitaltitudedescent"),
}

enum class TemperatureField(val tag: String) {
    TEMPERATURE("temperature"),
    FEELS_LIKE("feelsliketemperature"),
}

enum class DeviceField(val tag: String) {
    HEART_RATE("heartrate"),
    RUNNING_PACE("runningpace"),
    RUNNING_CADENCE("runningcadence"),
    RUNNING_DISTANCE("runningdistance"),
}

sealed interface TextPart {
    data class Literal(val text: String) : TextPart

    data object NewLine : TextPart

    data class Simple(val field: SimpleField) : TextPart

    data class Speed(val field: SpeedField, val unit: SpeedUnit) : TextPart

    data class Length(val field: LengthField, val unit: LengthUnit) : TextPart

    data class Temperature(val field: TemperatureField, val unit: TemperatureUnit) : TextPart

    data class Device(val field: DeviceField, val device: String) : TextPart

    data class Subtitles(val language: String?) : TextPart
}

private val tagPattern = Regex("""^\{([a-z]+)(?::([^\}]+))?\}$""")
private val simpleFields = SimpleField.entries.associateBy { it.tag }
private val speedFields = SpeedField.entries.associateBy { it.tag }
private val lengthFields = LengthField.entries.associateBy { it.tag }
private val temperatureFields = TemperatureField.entries.associateBy { it.tag }
private val deviceFields = DeviceField.entries.associateBy { it.tag }

private fun <T> unitOf(entries: List<T>, system: T, option: String?, symbol: (T) -> String): T? =
    if (option == null) system else entries.firstOrNull { it != system && symbol(it) == option }

private fun resolve(name: String, option: String?): TextPart? {
    simpleFields[name]?.let { return if (option == null) TextPart.Simple(it) else null }
    speedFields[name]?.let { field ->
        return unitOf(SpeedUnit.entries, SpeedUnit.SYSTEM, option) { it.symbol }?.let { TextPart.Speed(field, it) }
    }
    lengthFields[name]?.let { field ->
        return unitOf(LengthUnit.entries, LengthUnit.SYSTEM, option) { it.symbol }?.let { TextPart.Length(field, it) }
    }
    temperatureFields[name]?.let { field ->
        return unitOf(TemperatureUnit.entries, TemperatureUnit.SYSTEM, option) { it.symbol }?.let { TextPart.Temperature(field, it) }
    }
    deviceFields[name]?.let { return TextPart.Device(it, option.orEmpty()) }
    if (name == "subtitles") return TextPart.Subtitles(option)
    return null
}

fun parseTextFormat(format: String): List<TextPart> {
    val text = format.replace("\r", "").replace("\\n", "\n")
    val parts = ArrayList<TextPart>()
    val literal = StringBuilder()
    var index = 0
    val flush = {
        if (literal.isNotEmpty()) {
            parts += TextPart.Literal(literal.toString())
            literal.clear()
        }
    }
    while (index < text.length) {
        val char = text[index]
        val end = if (char == '{') text.indexOf('}', index) else -1
        val match = if (end < 0) null else tagPattern.find(text.substring(index, end + 1).lowercase())
        val part = match?.let { resolve(it.groupValues[1], it.groups[2]?.value) }
        if (part != null) {
            flush()
            parts += part
            index = end + 1
        } else if (char == '\n') {
            flush()
            parts += TextPart.NewLine
            index++
        } else {
            literal.append(char)
            index++
        }
    }
    flush()
    return parts
}

private fun List<TextPart>.has(field: SimpleField) = any { it == TextPart.Simple(field) }

fun List<TextPart>.count(field: SimpleField) = count { it == TextPart.Simple(field) }

val List<TextPart>.needsWeather: Boolean
    get() = any {
        it == TextPart.Simple(SimpleField.CONDITIONS) || it is TextPart.Temperature || (it is TextPart.Speed && it.field == SpeedField.WIND)
    }

val List<TextPart>.needsGeography: Boolean
    get() = any {
        it is TextPart.Simple && it.field in setOf(
            SimpleField.COUNTRY,
            SimpleField.COUNTRY_FLAG,
            SimpleField.STATE,
            SimpleField.AREA,
            SimpleField.CITY,
            SimpleField.NEIGHBORHOOD,
        )
    }

val List<TextPart>.needsGForce: Boolean
    get() = any { it is TextPart.Simple && it.field in setOf(SimpleField.G_FORCE, SimpleField.G_FORCE_RECENT_MAX, SimpleField.G_FORCE_MAX) }

val List<TextPart>.needsTrip: Boolean
    get() = any {
        (it is TextPart.Length && it.field != LengthField.ALTITUDE) || it == TextPart.Simple(SimpleField.SLOPE) ||
            (it is TextPart.Speed && it.field == SpeedField.AVERAGE_SPEED)
    }

val List<TextPart>.needsLocation: Boolean
    get() = needsTrip || needsWeather || needsGeography ||
        any { it is TextPart.Length || (it is TextPart.Speed && it.field == SpeedField.SPEED) }

val List<TextPart>.needsStats: Boolean
    get() = any {
        it is TextPart.Simple && it.field in setOf(SimpleField.BITRATE, SimpleField.BITRATE_AND_TOTAL, SimpleField.BONDING, SimpleField.FPS)
    }

val List<TextPart>.needsMuted: Boolean get() = has(SimpleField.MUTED)

private val controlFields = setOf(SimpleField.TIMER, SimpleField.STOPWATCH, SimpleField.CHECKBOX, SimpleField.RATING, SimpleField.LAP_TIMES)

val List<TextPart>.hasControls: Boolean get() = needsTrip || any { it is TextPart.Simple && it.field in controlFields }

fun List<TextPart>.checkboxLabels(): List<String> {
    val labels = ArrayList<String>()
    var open = false
    for (part in this) {
        when {
            part is TextPart.Literal -> if (open) {
                labels[labels.lastIndex] = part.text.trim()
                open = false
            }
            part == TextPart.NewLine -> open = false
            part == TextPart.Simple(SimpleField.CHECKBOX) -> {
                labels += ""
                open = true
            }
        }
    }
    return labels
}

private val liveFields = setOf(
    SimpleField.CLOCK,
    SimpleField.SHORT_CLOCK,
    SimpleField.DATE,
    SimpleField.FULL_DATE,
    SimpleField.BITRATE,
    SimpleField.BITRATE_AND_TOTAL,
    SimpleField.BONDING,
    SimpleField.RESOLUTION,
    SimpleField.FPS,
    SimpleField.SLOPE,
    SimpleField.CONDITIONS,
    SimpleField.COUNTRY,
    SimpleField.COUNTRY_FLAG,
    SimpleField.STATE,
    SimpleField.AREA,
    SimpleField.CITY,
    SimpleField.NEIGHBORHOOD,
    SimpleField.MUTED,
    SimpleField.G_FORCE,
    SimpleField.G_FORCE_RECENT_MAX,
    SimpleField.G_FORCE_MAX,
)

val List<TextPart>.live: Boolean
    get() = any {
        it is TextPart.Speed || it is TextPart.Length || it is TextPart.Temperature || (it is TextPart.Simple && it.field in liveFields)
    }
