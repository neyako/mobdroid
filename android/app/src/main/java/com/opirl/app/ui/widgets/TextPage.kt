package com.opirl.app.ui.widgets

import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.settings.Widget
import com.opirl.app.ui.ChoiceRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.text.LengthUnit
import com.opirl.app.widgets.text.SpeedUnit
import com.opirl.app.widgets.text.TIMER_STEPS
import com.opirl.app.widgets.text.TemperatureUnit
import com.opirl.app.widgets.text.TextAlignment
import com.opirl.app.widgets.text.TextDesign
import com.opirl.app.widgets.text.TextFamily
import com.opirl.app.widgets.text.TextFontStyle
import com.opirl.app.widgets.text.TextLaps
import com.opirl.app.widgets.text.TextPart
import com.opirl.app.widgets.text.TextSegment
import com.opirl.app.widgets.text.TextSettings
import com.opirl.app.widgets.text.TextStopwatch
import com.opirl.app.widgets.text.TextVariables
import com.opirl.app.widgets.text.TextWeight
import com.opirl.app.widgets.text.add
import com.opirl.app.widgets.text.checkboxLabels
import com.opirl.app.widgets.text.elapsed
import com.opirl.app.widgets.text.finish
import com.opirl.app.widgets.text.formatDuration
import com.opirl.app.widgets.text.formatText
import com.opirl.app.widgets.text.hasControls
import com.opirl.app.widgets.text.isSynced
import com.opirl.app.widgets.text.lap
import com.opirl.app.widgets.text.needsLocation
import com.opirl.app.widgets.text.needsTrip
import com.opirl.app.widgets.text.needsWeather
import com.opirl.app.widgets.text.parseTextFormat
import com.opirl.app.widgets.text.running
import com.opirl.app.widgets.text.set
import com.opirl.app.widgets.text.synced
import com.opirl.app.widgets.text.textSources
import com.opirl.app.widgets.text.timeLeft
import com.opirl.app.widgets.text.toggled
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val PAGE_SUGGESTIONS = "suggestions"
private const val PAGE_VARIABLES = "variables"
private const val PAGE_CATEGORY = "category:"
private const val PAGE_UNITS = "units:"

private enum class TextUnitKind { SPEED, LENGTH, TEMPERATURE }

private class TextVariable(val tag: String, @StringRes val name: Int, val units: TextUnitKind? = null)

private class TextCategory(val id: String, @StringRes val title: Int, val variables: List<TextVariable>)

private val categories = listOf(
    TextCategory(
        "general",
        R.string.text_category_general,
        listOf(
            TextVariable("{checkbox}", R.string.text_name_checkbox),
            TextVariable("{rating}", R.string.text_name_rating),
            TextVariable("{muted}", R.string.text_name_muted),
            TextVariable("{gForce}", R.string.text_name_g_force),
            TextVariable("{gForceRecentMax}", R.string.text_name_g_force_recent_max),
            TextVariable("{gForceMax}", R.string.text_name_g_force_max),
        ),
    ),
    TextCategory(
        "time",
        R.string.text_category_time,
        listOf(
            TextVariable("{time}", 0),
            TextVariable("{shortTime}", 0),
            TextVariable("{date}", 0),
            TextVariable("{fullDate}", 0),
            TextVariable("{timer}", R.string.text_name_timer),
            TextVariable("{stopwatch}", R.string.text_name_stopwatch),
            TextVariable("{lapTimes}", R.string.text_name_lap_times),
        ),
    ),
    TextCategory(
        "location",
        R.string.text_category_location,
        listOf(
            TextVariable("{country}", R.string.text_name_country),
            TextVariable("{countryFlag}", R.string.text_name_country_flag),
            TextVariable("{state}", R.string.text_name_state),
            TextVariable("{area}", R.string.text_name_area),
            TextVariable("{city}", R.string.text_name_city),
            TextVariable("{neighborhood}", R.string.text_name_neighborhood),
            TextVariable("{speed}", R.string.text_name_speed, TextUnitKind.SPEED),
            TextVariable("{averageSpeed}", R.string.text_name_average_speed, TextUnitKind.SPEED),
            TextVariable("{altitude}", R.string.text_name_altitude, TextUnitKind.LENGTH),
            TextVariable("{distance}", R.string.text_name_distance, TextUnitKind.LENGTH),
            TextVariable("{splitDistance}", R.string.text_name_split_distance, TextUnitKind.LENGTH),
            TextVariable("{altitudeAscent}", R.string.text_name_altitude_ascent, TextUnitKind.LENGTH),
            TextVariable("{altitudeDescent}", R.string.text_name_altitude_descent, TextUnitKind.LENGTH),
            TextVariable("{splitAltitudeAscent}", R.string.text_name_split_altitude_ascent, TextUnitKind.LENGTH),
            TextVariable("{splitAltitudeDescent}", R.string.text_name_split_altitude_descent, TextUnitKind.LENGTH),
            TextVariable("{slope}", R.string.text_name_slope),
        ),
    ),
    TextCategory(
        "weather",
        R.string.text_category_weather,
        listOf(
            TextVariable("{conditions}", R.string.text_name_conditions),
            TextVariable("{temperature}", R.string.text_name_temperature, TextUnitKind.TEMPERATURE),
            TextVariable("{feelsLikeTemperature}", R.string.text_name_feels_like, TextUnitKind.TEMPERATURE),
            TextVariable("{wind}", R.string.text_name_wind, TextUnitKind.SPEED),
        ),
    ),
    TextCategory(
        "stream",
        R.string.text_category_stream,
        listOf(
            TextVariable("{bitrate}", R.string.text_name_bitrate),
            TextVariable("{bitrateAndTotal}", R.string.text_name_bitrate_and_total),
            TextVariable("{bonding}", R.string.text_name_bonding),
            TextVariable("{resolution}", R.string.text_name_resolution),
            TextVariable("{fps}", R.string.text_name_fps),
        ),
    ),
)

private class TextSuggestion(@StringRes val name: Int, val text: String)

private const val MOVEMENT = "📏 {distance} 💨 {speed} 🏔️ {altitude}"

private val suggestions = listOf(
    TextSuggestion(R.string.text_suggestion_travel, "{conditions} {temperature}\n🕑 {shortTime}\n{countryFlag} {city}\n$MOVEMENT"),
    TextSuggestion(R.string.text_suggestion_weather, "{conditions} {temperature}"),
    TextSuggestion(R.string.text_suggestion_time, "🕑 {shortTime}"),
    TextSuggestion(R.string.text_suggestion_date, "📅 {date}"),
    TextSuggestion(R.string.text_suggestion_full_date, "📅 {fullDate}"),
    TextSuggestion(R.string.text_suggestion_timer, "⏳ {timer}"),
    TextSuggestion(R.string.text_suggestion_stopwatch, "⏱️ {stopwatch}"),
    TextSuggestion(R.string.text_suggestion_city, "{countryFlag} {city}"),
    TextSuggestion(R.string.text_suggestion_country, "{countryFlag} {country}"),
    TextSuggestion(R.string.text_suggestion_movement, MOVEMENT),
    TextSuggestion(R.string.text_suggestion_muted, "{muted}"),
    TextSuggestion(R.string.text_suggestion_debug, "{time}\n{bitrateAndTotal}\n{resolution} {fps}"),
    TextSuggestion(R.string.text_suggestion_racing, "🏎️ Racing 🏎️\n{lapTimes}"),
)

private enum class TextAction { MINUS, PLUS, SET, RESET, TOGGLE, LAP, FINISH, SPLIT, NONE }

private fun <T> List<T>.change(index: Int, transform: (T) -> T) = mapIndexed { i, old -> if (i == index) transform(old) else old }

private fun TextSettings.withText(text: String): TextSettings = synced(parseTextFormat(text))

private fun speedLabel(unit: SpeedUnit) = when (unit) {
    SpeedUnit.METERS_PER_SECOND -> R.string.text_unit_meters_per_second
    SpeedUnit.KILOMETERS_PER_HOUR -> R.string.text_unit_kilometers_per_hour
    else -> R.string.text_unit_miles_per_hour
}

private fun lengthLabel(unit: LengthUnit) = when (unit) {
    LengthUnit.METERS -> R.string.text_unit_meters
    LengthUnit.KILOMETERS -> R.string.text_unit_kilometers
    LengthUnit.FEET -> R.string.text_unit_feet
    LengthUnit.YARDS -> R.string.text_unit_yards
    LengthUnit.MILES -> R.string.text_unit_miles
    LengthUnit.NAUTICAL_MILES -> R.string.text_unit_nautical_miles
    else -> R.string.text_unit_light_years
}

private fun temperatureLabel(unit: TemperatureUnit) = when (unit) {
    TemperatureUnit.KELVIN -> R.string.text_unit_kelvin
    TemperatureUnit.CELSIUS -> R.string.text_unit_celsius
    else -> R.string.text_unit_fahrenheit
}

private fun units(kind: TextUnitKind): List<Pair<Int, String>> = when (kind) {
    TextUnitKind.SPEED -> SpeedUnit.entries.filter { it != SpeedUnit.SYSTEM }.map { speedLabel(it) to it.symbol }
    TextUnitKind.LENGTH -> LengthUnit.entries.filter { it != LengthUnit.SYSTEM }.map { lengthLabel(it) to it.symbol }
    TextUnitKind.TEMPERATURE -> TemperatureUnit.entries.filter { it != TemperatureUnit.SYSTEM }.map { temperatureLabel(it) to it.symbol }
}

private fun sample(tag: String, now: Long): String =
    formatText(parseTextFormat(tag), TextVariables(time = now), TextSettings(), now)
        .joinToString(" ") { line -> line.segments.joinToString("") { (it as? TextSegment.Run)?.text.orEmpty() } }

private fun stepLabel(minutes: Int) = if (minutes % 60 == 0) "${minutes / 60}h" else "${minutes}m"

@Composable
internal fun TextPage(scope: WidgetScope) {
    val page = scope.page
    when {
        page == null -> TextMain(scope)
        page == PAGE_SUGGESTIONS -> SuggestionsPage(scope)
        page == PAGE_VARIABLES -> CategoriesPage(scope)
        page.startsWith(PAGE_CATEGORY) -> VariablesPage(scope, page.removePrefix(PAGE_CATEGORY))
        page.startsWith(PAGE_UNITS) -> UnitsPage(scope, page.removePrefix(PAGE_UNITS))
    }
}

@Composable
private fun SyncOptions(scope: WidgetScope, parts: List<TextPart>) {
    val synced = scope.widget.textOptions.isSynced(parts)
    LaunchedEffect(synced, parts) {
        if (!synced) scope.update { it.copy(textOptions = it.textOptions.synced(parts)) }
    }
}

@Composable
private fun TextMain(scope: WidgetScope) {
    val widget = scope.widget
    val options = widget.textOptions
    val parts = remember(widget.text) { parseTextFormat(widget.text) }
    val update = { transform: (TextSettings) -> TextSettings -> scope.update { it.copy(textOptions = transform(it.textOptions)) } }
    val suggestionsTitle = stringResource(R.string.text_suggestions)
    val variablesTitle = stringResource(R.string.text_variables)
    SyncOptions(scope, parts)
    Group {
        row { shapes ->
            TextRow(
                shapes,
                stringResource(R.string.widget_text),
                widget.text,
                stringResource(R.string.text_placeholder),
                singleLine = false,
            ) { text -> scope.update { it.copy(text = text, textOptions = it.textOptions.withText(text)) } }
        }
        row { shapes -> NavRow(shapes, suggestionsTitle) { scope.push(PAGE_SUGGESTIONS, suggestionsTitle) } }
        row { shapes -> NavRow(shapes, variablesTitle) { scope.push(PAGE_VARIABLES, variablesTitle) } }
    }
    if (parts.needsLocation) LocationAccess(scope.engine)
    if (parts.needsWeather) Note(stringResource(R.string.text_weather_credit))
    Controls(scope, parts)
    Group {
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.text_background), options.backgroundColor) { color ->
                update { it.copy(backgroundColor = color) }
            }
        }
        row { shapes ->
            ColorRow(shapes, stringResource(R.string.text_foreground), options.foregroundColor) { color ->
                update { it.copy(foregroundColor = color) }
            }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.text_size), widget.textSize, 8..100, "${widget.textSize}") { size ->
                scope.update { it.copy(textSize = size) }
            }
        }
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.text_family),
                TextFamily.entries,
                options.family,
                { stringResource(it.label) },
            ) { family -> update { it.copy(family = family) } }
        }
        if (options.family == TextFamily.SYSTEM) {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.text_design),
                    TextDesign.entries,
                    options.design,
                    { stringResource(it.label) },
                ) { design -> update { it.copy(design = design) } }
            }
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.text_weight),
                    TextWeight.entries,
                    options.weight,
                    { stringResource(it.label) },
                ) { weight -> update { it.copy(weight = weight) } }
            }
            row { shapes ->
                SwitchRow(shapes, stringResource(R.string.text_monospaced_digits), options.monospacedDigits) { on ->
                    update { it.copy(monospacedDigits = on) }
                }
            }
        } else {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.text_style),
                    TextFontStyle.entries,
                    options.fontStyle,
                    { stringResource(it.label) },
                ) { style -> update { it.copy(fontStyle = style) } }
            }
        }
    }
    Group {
        row { shapes ->
            ChoiceRow(
                shapes,
                stringResource(R.string.text_alignment),
                TextAlignment.entries,
                options.alignment,
                { stringResource(it.label) },
            ) { alignment -> update { it.copy(alignment = alignment) } }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.text_minimum_width), options.widthEnabled) { on ->
                update { it.copy(widthEnabled = on) }
            }
        }
        if (options.widthEnabled) {
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.width), options.width, 10..700, "${options.width}", 5) { width ->
                    update { it.copy(width = width) }
                }
            }
        }
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.text_corner_radius),
                options.cornerRadius,
                0..50,
                "${options.cornerRadius}",
            ) { radius -> update { it.copy(cornerRadius = radius) } }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.text_delay), (options.delay * 2).roundToInt(), 0..20, "${options.delay} s") { half ->
                update { it.copy(delay = half / 2f) }
            }
        }
    }
    Note(stringResource(R.string.text_delay_note))
    if (parts.needsTrip) {
        Group {
            row { shapes ->
                SwitchRow(shapes, stringResource(R.string.text_reset_when_live), options.resetWhenGoingLive) { on ->
                    update { it.copy(resetWhenGoingLive = on) }
                }
            }
        }
    }
}

internal fun hasTextControls(widget: Widget): Boolean = parseTextFormat(widget.text).hasControls

@Composable
internal fun TextControls(scope: WidgetScope) {
    val parts = remember(scope.widget.text) { parseTextFormat(scope.widget.text) }
    SyncOptions(scope, parts)
    Controls(scope, parts)
}

@Composable
private fun rememberNow(active: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(active) {
        now = System.currentTimeMillis()
        while (active) {
            delay(1000 - System.currentTimeMillis() % 1000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
private fun actionLabel(action: TextAction, stopwatchRunning: Boolean, step: Int): String = when (action) {
    TextAction.MINUS -> "-${stepLabel(step)}"
    TextAction.PLUS -> "+${stepLabel(step)}"
    TextAction.SET -> stringResource(R.string.text_set)
    TextAction.RESET -> stringResource(R.string.text_reset)
    TextAction.TOGGLE -> stringResource(if (stopwatchRunning) R.string.stop else R.string.text_start)
    TextAction.LAP -> stringResource(R.string.text_lap)
    TextAction.FINISH -> stringResource(R.string.text_finish)
    TextAction.SPLIT -> stringResource(R.string.text_split)
    TextAction.NONE -> ""
}

@Composable
private fun TimeDialog(initial: Long, onSet: (Long) -> Unit, onDismiss: () -> Unit) {
    var hours by remember { mutableIntStateOf((initial / 3600).toInt().coerceAtMost(23)) }
    var minutes by remember { mutableIntStateOf((initial / 60 % 60).toInt()) }
    var seconds by remember { mutableIntStateOf((initial % 60).toInt()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.text_set_time)) },
        text = {
            Column {
                TimeSlider(R.string.text_hours, hours, 23) { hours = it }
                TimeSlider(R.string.text_minutes, minutes, 59) { minutes = it }
                TimeSlider(R.string.text_seconds, seconds, 59) { seconds = it }
            }
        },
        confirmButton = { TextButton({ onSet(hours * 3600L + minutes * 60 + seconds) }) { Text(stringResource(R.string.text_set)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TimeSlider(@StringRes title: Int, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(title))
        Text("$value")
    }
    Slider(value.toFloat(), { onChange(it.toInt()) }, valueRange = 0f..max.toFloat())
}

@Composable
private fun Controls(scope: WidgetScope, parts: List<TextPart>) {
    val options = scope.widget.textOptions
    val trip = parts.needsTrip
    val labels = remember(parts) { parts.checkboxLabels() }
    if (!parts.hasControls) return
    val clock = System.currentTimeMillis()
    val ticking = options.timers.any { it.timeLeft(clock) > 0 } || options.stopwatches.any { it.running } ||
        options.lapTimes.any { it.running() }
    val now = rememberNow(ticking)
    val update = { transform: (TextSettings) -> TextSettings -> scope.update { it.copy(textOptions = transform(it.textOptions)) } }
    Group {
        options.timers.forEachIndexed { index, timer ->
            row { shapes ->
                var open by remember { mutableStateOf(false) }
                val title = stringResource(R.string.text_timer, index + 1) + "  " + formatDuration(timer.timeLeft(now) / 1000)
                ChoiceRow(
                    shapes,
                    title,
                    listOf(TextAction.MINUS, TextAction.PLUS, TextAction.SET),
                    TextAction.NONE,
                    { actionLabel(it, false, timer.delta) },
                ) { action ->
                    val stamp = System.currentTimeMillis()
                    when (action) {
                        TextAction.MINUS -> update {
                            it.copy(timers = it.timers.change(index) { timer -> timer.add(-timer.delta * 60_000L, stamp) })
                        }
                        TextAction.PLUS -> update {
                            it.copy(timers = it.timers.change(index) { timer -> timer.add(timer.delta * 60_000L, stamp) })
                        }
                        else -> open = true
                    }
                }
                if (open) {
                    TimeDialog(timer.timeLeft(now) / 1000, { seconds ->
                        update { it.copy(timers = it.timers.change(index) { timer -> timer.set(seconds, System.currentTimeMillis()) }) }
                        open = false
                    }) { open = false }
                }
            }
            row { shapes ->
                PickerRow(shapes, stringResource(R.string.text_step), TIMER_STEPS, timer.delta, { stepLabel(it) }) { delta ->
                    update { it.copy(timers = it.timers.change(index) { timer -> timer.copy(delta = delta) }) }
                }
            }
        }
        options.stopwatches.forEachIndexed { index, stopwatch ->
            row { shapes ->
                var open by remember { mutableStateOf(false) }
                val title = stringResource(R.string.text_stopwatch, index + 1) + "  " + formatDuration(stopwatch.elapsed(now) / 1000)
                ChoiceRow(
                    shapes,
                    title,
                    listOf(TextAction.SET, TextAction.RESET, TextAction.TOGGLE),
                    TextAction.NONE,
                    { actionLabel(it, stopwatch.running, 0) },
                ) { action ->
                    val stamp = System.currentTimeMillis()
                    when (action) {
                        TextAction.RESET -> update { it.copy(stopwatches = it.stopwatches.change(index) { TextStopwatch() }) }
                        TextAction.TOGGLE -> update {
                            it.copy(stopwatches = it.stopwatches.change(index) { stopwatch -> stopwatch.toggled(stamp) })
                        }
                        else -> open = true
                    }
                }
                if (open) {
                    TimeDialog(stopwatch.elapsed(now) / 1000, { seconds ->
                        update {
                            it.copy(
                                stopwatches = it.stopwatches.change(index) { stopwatch -> stopwatch.set(seconds, System.currentTimeMillis()) },
                            )
                        }
                        open = false
                    }) { open = false }
                }
            }
        }
        options.checkboxes.forEachIndexed { index, checked ->
            row { shapes ->
                val label = labels.getOrElse(index) { "" }.ifEmpty { stringResource(R.string.text_checkbox, index + 1) }
                SwitchRow(shapes, label, checked) { on -> update { it.copy(checkboxes = it.checkboxes.change(index) { on }) } }
            }
        }
        options.ratings.forEachIndexed { index, rating ->
            row { shapes ->
                ChoiceRow(shapes, stringResource(R.string.text_rating, index + 1), (0..5).toList(), rating, { "$it" }) { value ->
                    update { it.copy(ratings = it.ratings.change(index) { value }) }
                }
            }
        }
        options.lapTimes.forEachIndexed { index, laps ->
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.text_lap_times, index + 1),
                    listOf(TextAction.LAP, TextAction.FINISH, TextAction.RESET),
                    TextAction.NONE,
                    { actionLabel(it, false, 0) },
                ) { action ->
                    val stamp = System.currentTimeMillis()
                    update { options ->
                        options.copy(
                            lapTimes = options.lapTimes.change(index) { laps ->
                                when (action) {
                                    TextAction.LAP -> laps.lap(stamp)
                                    TextAction.FINISH -> laps.finish(stamp)
                                    else -> TextLaps()
                                }
                            },
                        )
                    }
                }
            }
        }
        if (trip) {
            row { shapes ->
                ChoiceRow(
                    shapes,
                    stringResource(R.string.text_location_data),
                    listOf(TextAction.SPLIT, TextAction.RESET),
                    TextAction.NONE,
                    { actionLabel(it, false, 0) },
                ) { action ->
                    val sources = scope.runtime.textSources()
                    if (action == TextAction.SPLIT) sources.splitTrip() else sources.resetTrip(System.currentTimeMillis())
                    scope.runtime.invalidate()
                }
            }
        }
    }
}

private fun append(scope: WidgetScope, tag: String) =
    scope.update { it.copy(text = it.text + tag, textOptions = it.textOptions.withText(it.text + tag)) }

@Composable
private fun CategoriesPage(scope: WidgetScope) {
    Group {
        categories.forEach { category ->
            row { shapes ->
                val title = stringResource(category.title)
                NavRow(shapes, title) { scope.push(PAGE_CATEGORY + category.id, title) }
            }
        }
    }
}

@Composable
private fun VariablesPage(scope: WidgetScope, id: String) {
    val category = categories.firstOrNull { it.id == id } ?: return
    val context = LocalContext.current
    val resources = LocalResources.current
    val now = remember { System.currentTimeMillis() }
    val appended = { tag: String ->
        append(scope, tag)
        Toast.makeText(context, resources.getString(R.string.text_appended, tag), Toast.LENGTH_SHORT).show()
    }
    val unitTitle = stringResource(R.string.text_unit)
    Group {
        category.variables.forEach { variable ->
            row { shapes ->
                val description = when (variable.tag) {
                    "{time}", "{shortTime}" -> stringResource(R.string.text_show_time_as, sample(variable.tag, now))
                    "{date}", "{fullDate}" -> stringResource(R.string.text_show_date_as, sample(variable.tag, now))
                    else -> stringResource(R.string.text_show, stringResource(variable.name))
                }
                NavRow(shapes, variable.tag, supporting = description) { appended(variable.tag) }
            }
            variable.units?.let { kind ->
                val name = variable.tag.trim('{', '}')
                val tag = "{$name:<$unitTitle>}"
                row { shapes ->
                    NavRow(shapes, tag, supporting = stringResource(R.string.text_show_unit, stringResource(variable.name))) {
                        scope.push("$PAGE_UNITS$name:${kind.name}", unitTitle)
                    }
                }
            }
        }
    }
}

@Composable
private fun UnitsPage(scope: WidgetScope, id: String) {
    val (name, kind) = id.split(":").let { it[0] to TextUnitKind.valueOf(it[1]) }
    val context = LocalContext.current
    val resources = LocalResources.current
    Group {
        units(kind).forEach { (label, symbol) ->
            row { shapes ->
                NavRow(shapes, "${stringResource(label)} [$symbol]") {
                    val tag = "{$name:$symbol}"
                    append(scope, tag)
                    Toast.makeText(context, resources.getString(R.string.text_appended, tag), Toast.LENGTH_SHORT).show()
                    scope.pop()
                }
            }
        }
    }
}

@Composable
private fun SuggestionsPage(scope: WidgetScope) {
    var pending by remember { mutableStateOf<TextSuggestion?>(null) }
    val replace = { suggestion: TextSuggestion ->
        scope.update { it.copy(text = suggestion.text, textOptions = it.textOptions.withText(suggestion.text)) }
        scope.pop()
    }
    Group {
        suggestions.forEach { suggestion ->
            row { shapes ->
                NavRow(shapes, stringResource(suggestion.name), supporting = suggestion.text.replace("\n", "  ")) {
                    if (scope.widget.text.isEmpty()) replace(suggestion) else pending = suggestion
                }
            }
        }
    }
    pending?.let { suggestion ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.text_replace_title)) },
            confirmButton = {
                TextButton({
                    pending = null
                    replace(suggestion)
                }) { Text(stringResource(R.string.text_replace)) }
            },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
