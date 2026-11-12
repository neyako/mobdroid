package com.opirl.app.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.camera.Facing
import com.opirl.app.stream.key
import com.opirl.app.ui.ChoiceRow
import com.opirl.app.ui.ColorRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.widgets.videosource.USB_CAMERA
import com.opirl.app.widgets.videosource.VideoSourceSettings

@Composable
internal fun VideoSourcePage(scope: WidgetScope) {
    val ui by scope.engine.ui.collectAsStateWithLifecycle()
    val source = scope.widget.videoSource
    val cameras = ui.cameras.filter { it.facing != Facing.EXTERNAL }
    val update = { transform: (VideoSourceSettings) -> VideoSourceSettings -> scope.update { it.copy(videoSource = transform(it.videoSource)) } }
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.camera),
                listOf(null) + cameras.map { it.key } + USB_CAMERA,
                source.camera,
                { key ->
                    when (key) {
                        null -> stringResource(R.string.none)
                        USB_CAMERA -> stringResource(R.string.camera_usb)
                        else -> cameras.firstOrNull { it.key == key }?.label ?: stringResource(R.string.none)
                    }
                },
            ) { key -> update { it.copy(camera = key) } }
        }
    }
    Note(stringResource(R.string.video_source_note))
    Group {
        row { shapes ->
            ChoiceRow(shapes, stringResource(R.string.rotation), listOf(0, 90, 180, 270), source.rotation, { "$it\u00B0" }) { degrees ->
                update { it.copy(rotation = degrees) }
            }
        }
        row { shapes -> SwitchRow(shapes, stringResource(R.string.mirror), source.mirror) { on -> update { it.copy(mirror = on) } } }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.corner_radius), source.cornerRadius, 0..100, "${source.cornerRadius}%") { radius ->
                update { it.copy(cornerRadius = radius) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.border_width), source.borderWidth, 0..100, "${source.borderWidth}%") { width ->
                update { it.copy(borderWidth = width) }
            }
        }
        if (source.borderWidth > 0) {
            row { shapes -> ColorRow(shapes, stringResource(R.string.border_color), source.borderColor) { color -> update { it.copy(borderColor = color) } } }
        }
    }
    Group {
        row { shapes -> SwitchRow(shapes, stringResource(R.string.crop), source.cropEnabled) { on -> update { it.copy(cropEnabled = on) } } }
        if (source.cropEnabled) {
            row { shapes -> SliderRow(shapes, "X", source.cropX, 0..99, "${source.cropX}%") { x -> update { it.copy(cropX = x) } } }
            row { shapes -> SliderRow(shapes, "Y", source.cropY, 0..99, "${source.cropY}%") { y -> update { it.copy(cropY = y) } } }
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.width), source.cropWidth, 1..100, "${source.cropWidth}%") { width ->
                    update { it.copy(cropWidth = width) }
                }
            }
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.height), source.cropHeight, 1..100, "${source.cropHeight}%") { height ->
                    update { it.copy(cropHeight = height) }
                }
            }
        }
    }
}
