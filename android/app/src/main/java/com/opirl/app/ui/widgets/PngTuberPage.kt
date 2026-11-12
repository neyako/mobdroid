package com.opirl.app.ui.widgets

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.scenes.placement
import com.opirl.app.stream.key
import com.opirl.app.stream.outputSize
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.ValueRow
import com.opirl.app.widgets.pngtuber.COSTUMES
import com.opirl.app.widgets.pngtuber.PngTuberSettings
import com.opirl.app.widgets.pngtuber.PngTuberState
import com.opirl.app.widgets.pngtuber.PngTuberWidget
import java.io.File

@Composable
internal fun PngTuberPage(scope: WidgetScope) {
    val widget = scope.widget
    val pngTuber = widget.pngTuber
    val ui by scope.engine.ui.collectAsStateWithLifecycle()
    val version by scope.runtime.version.collectAsStateWithLifecycle()
    val state = scope.runtime.state(widget.id) { PngTuberState() }
    val update = { transform: (PngTuberSettings) -> PngTuberSettings -> scope.update { it.copy(pngTuber = transform(it.pngTuber)) } }
    val pick = rememberWidgetFilePicker(scope) { name ->
        pngTuber.model?.let { File(scope.runtime.files, it).delete() }
        update { it.copy(model = name) }
    }
    val (width, height) = outputSize(scope.settings.selectedProfile)
    val size = (scope.settings.selectedScene?.placement(widget) ?: PngTuberWidget.placement(widget)).size.coerceIn(1, 100)
    LaunchedEffect(pngTuber.model, pngTuber.costume) { state.ensure(scope.runtime, pngTuber, size, width, height) }
    val loaded = remember(version, pngTuber.model) { state.model?.takeIf { it.name == pngTuber.model } }
    val failed = remember(version, pngTuber.model) { state.failed == pngTuber.model }
    val available = ui.cameras
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.camera),
                listOf<String?>(null) + available.map { it.key },
                pngTuber.cameraKey?.takeIf { key -> available.any { it.key == key } },
                { key -> available.firstOrNull { it.key == key }?.label ?: stringResource(R.string.pngtuber_scene_camera) },
            ) { key -> update { it.copy(cameraKey = key) } }
        }
        row { shapes ->
            ActionRow(
                shapes,
                stringResource(if (pngTuber.model == null) R.string.pngtuber_choose_model else R.string.pngtuber_change_model),
                Icons.Rounded.Add,
            ) { pick(arrayOf("*/*")) }
        }
        row { shapes ->
            ValueRow(
                shapes,
                stringResource(R.string.pngtuber_model),
                when {
                    pngTuber.model == null -> stringResource(R.string.none)
                    loaded != null -> pluralStringResource(R.plurals.pngtuber_images, loaded.parts.size, loaded.parts.size)
                    failed -> stringResource(R.string.pngtuber_invalid)
                    else -> stringResource(R.string.pngtuber_loading)
                },
            )
        }
    }
    Note(stringResource(R.string.pngtuber_note))
    Group {
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.pngtuber_mouth_sensitivity),
                pngTuber.mouthSensitivity,
                5..300,
                "${pngTuber.mouthSensitivity}%",
                5,
            ) { value -> update { it.copy(mouthSensitivity = value) } }
        }
        row { shapes ->
            SliderRow(
                shapes,
                stringResource(R.string.pngtuber_eye_sensitivity),
                pngTuber.eyeSensitivity,
                5..500,
                "${pngTuber.eyeSensitivity}%",
                5,
            ) { value -> update { it.copy(eyeSensitivity = value) } }
        }
    }
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.pngtuber_costume),
                (1..COSTUMES).toList(),
                pngTuber.costume,
                { number -> "$number" },
            ) { number -> update { it.copy(costume = number) } }
        }
        row { shapes ->
            SwitchRow(shapes, stringResource(R.string.pngtuber_mirror), pngTuber.mirror) { on -> update { it.copy(mirror = on) } }
        }
    }
}
