package com.opirl.app.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.opirl.app.R
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.Note
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.widgets.map.MapSettings
import kotlin.math.roundToInt

@Composable
internal fun MapPage(scope: WidgetScope) {
    val map = scope.widget.map
    val update = { change: (MapSettings) -> MapSettings -> scope.update { it.copy(map = change(it.map)) } }
    val halves = (map.delay * 2).roundToInt()
    val delay = "${halves / 2}.${if (halves % 2 == 0) 0 else 5} s"
    LocationAccess(scope.engine)
    Group {
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.map_north_up), map.northUp) { on -> update { it.copy(northUp = on) } }
        }
    }
    Note(stringResource(R.string.map_north_up_note))
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.size), map.size, 500..10_000, "${map.size} m", 100) { size ->
                update { it.copy(size = size) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.map_delay), halves, 0..20, delay) { value ->
                update { it.copy(delay = value / 2.0) }
            }
        }
    }
    Note(stringResource(R.string.map_delay_note))
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.map_corner_radius), map.cornerRadius, 0..100, "${map.cornerRadius}%") { radius ->
                update { it.copy(cornerRadius = radius) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.map_border_width), map.borderWidth, 0..100, "${map.borderWidth}%") { width ->
                update { it.copy(borderWidth = width) }
            }
        }
        if (map.borderWidth > 0) {
            row { shapes ->
                ColorRow(shapes, stringResource(R.string.map_border_color), map.borderColor) { color ->
                    update { it.copy(borderColor = color) }
                }
            }
        }
    }
    Note(stringResource(R.string.map_credit_note))
}
