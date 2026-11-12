package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.RemovableRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.wheel.WHEEL_WEIGHTS
import com.opirl.app.widgets.wheel.WheelOption
import com.opirl.app.widgets.wheel.WheelSettings
import com.opirl.app.widgets.wheel.WheelState
import com.opirl.app.widgets.wheel.optionsFromText
import com.opirl.app.widgets.wheel.optionsToText
import com.opirl.app.widgets.wheel.weightPercent
import java.text.DateFormat
import java.util.Date

@Composable
internal fun WheelPage(scope: WidgetScope) {
    val wheel = scope.widget.wheel
    val update = { transform: (WheelSettings) -> WheelSettings -> scope.update { it.copy(wheel = transform(it.wheel)) } }
    val page = scope.page
    if (page != null) {
        val index = page.toIntOrNull() ?: return
        val option = wheel.options.getOrNull(index) ?: return
        val change = { changed: WheelOption ->
            update { w -> w.copy(options = w.options.mapIndexed { at, other -> if (at == index) changed else other }) }
        }
        Group {
            row { shapes ->
                TextRow(shapes, stringResource(R.string.wheel_option_text), option.text) { text -> change(option.copy(text = text)) }
            }
            row { shapes ->
                PickerRow(shapes, stringResource(R.string.wheel_weight), WHEEL_WEIGHTS, option.weight, { "$it" }) { weight ->
                    change(option.copy(weight = weight))
                }
            }
        }
        return
    }
    var text by remember(scope.widget.id) { mutableStateOf(optionsToText(wheel.options)) }
    LaunchedEffect(wheel.options) {
        if (optionsFromText(text) != wheel.options) text = optionsToText(wheel.options)
    }
    Group {
        if (wheel.advanced) {
            wheel.options.forEachIndexed { index, option ->
                row { shapes ->
                    val title = stringResource(R.string.wheel_option_number, index + 1)
                    val shown = option.text.ifBlank { title }
                    val share = "${weightPercent(wheel.options, option.weight)}%"
                    if (wheel.options.size > 1) {
                        RemovableRow(shapes, shown, share, { scope.push(index.toString(), title) }) {
                            update { w -> w.copy(options = w.options.filterIndexed { at, _ -> at != index }) }
                        }
                    } else {
                        NavRow(shapes, shown, share) { scope.push(index.toString(), title) }
                    }
                }
            }
            row { shapes ->
                val title = stringResource(R.string.wheel_option_number, wheel.options.size + 1)
                ActionRow(shapes, stringResource(R.string.wheel_add_option), Icons.Rounded.Add) {
                    update { w -> w.copy(options = w.options + WheelOption()) }
                    scope.push(wheel.options.size.toString(), title)
                }
            }
        } else {
            row { shapes ->
                TextRow(
                    shapes,
                    stringResource(R.string.wheel_options),
                    text,
                    stringResource(R.string.wheel_options_hint),
                    singleLine = false,
                ) { changed ->
                    text = changed
                    update { it.copy(options = optionsFromText(changed)) }
                }
            }
        }
    }
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.wheel_advanced), wheel.advanced) { on ->
                update { w -> w.copy(advanced = on, options = if (on) w.options else w.options.map { it.copy(weight = 1) }) }
            }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.wheel_only_when_spinning), wheel.onlyShowWhenSpinning) { on ->
                update { it.copy(onlyShowWhenSpinning = on) }
            }
        }
    }
    WheelControls(scope)
    WheelResults(scope)
}

@Composable
private fun WheelResults(scope: WidgetScope) {
    val state = scope.runtime.state(scope.widget.id) { WheelState() }
    val version by scope.runtime.version.collectAsStateWithLifecycle()
    val results = remember(version) { state.results() }
    if (results.isEmpty()) return
    val format = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Group {
        results.forEach { result ->
            row { shapes ->
                RemovableRow(shapes, result.text, format.format(Date(result.time)), null) {
                    state.remove(result)
                    scope.runtime.invalidate()
                }
            }
        }
    }
}

@Composable
internal fun WheelControls(scope: WidgetScope) {
    val state = scope.runtime.state(scope.widget.id) { WheelState() }
    Group {
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.wheel_spin), Icons.Rounded.PlayArrow) {
                state.spin.spin()
                scope.runtime.invalidate()
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.wheel_shuffle), Icons.Rounded.Shuffle) {
                scope.update { it.copy(wheel = it.wheel.copy(options = it.wheel.options.shuffled())) }
            }
        }
    }
}
