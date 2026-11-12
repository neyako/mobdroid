package com.opirl.app.ui.widgets

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ListItemShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.camera.Facing
import com.opirl.app.stream.key
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.ValueRow
import com.opirl.app.widgets.vtuber.VTuberSettings
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
internal fun FaceCameraRow(scope: WidgetScope, shapes: ListItemShapes, camera: String?, onSelect: (String?) -> Unit) {
    val ui by scope.engine.ui.collectAsStateWithLifecycle()
    val cameras = ui.cameras.filter { it.facing != Facing.EXTERNAL }
    PickerRow(
        shapes,
        stringResource(R.string.face_camera),
        listOf(null) + cameras.map { it.key },
        camera.takeIf { key -> cameras.any { it.key == key } },
        { key -> cameras.firstOrNull { it.key == key }?.label ?: stringResource(R.string.scene_camera) },
        onSelect,
    )
}

@Composable
internal fun VTuberPage(scope: WidgetScope) {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val tuber = scope.widget.vTuber
    val update = { transform: (VTuberSettings) -> VTuberSettings -> scope.update { it.copy(vTuber = transform(it.vTuber)) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutines.launch(Dispatchers.IO) {
                val name = "${scope.widget.id}-${System.currentTimeMillis()}"
                val shown = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: "VRM"
                val copied = runCatching {
                    scope.runtime.files.mkdirs()
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        File(scope.runtime.files, name).outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
                if (copied) {
                    tuber.model?.let { File(scope.runtime.files, it).delete() }
                    update { it.copy(model = name, modelName = shown) }
                }
            }
        }
    }
    Group {
        if (tuber.model != null) row { ValueRow(it, stringResource(R.string.vtuber_model), tuber.modelName) }
        row { shapes ->
            ActionRow(shapes, stringResource(if (tuber.model == null) R.string.choose_model else R.string.change_model), Icons.Rounded.Add) {
                pick.launch(arrayOf("*/*"))
            }
        }
    }
    Note(stringResource(R.string.vtuber_note))
    Group {
        row { shapes -> FaceCameraRow(scope, shapes, tuber.camera) { key -> update { it.copy(camera = key) } } }
        row { shapes -> SwitchRow(shapes, stringResource(R.string.mirror), tuber.mirror) { on -> update { it.copy(mirror = on) } } }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.mouth_sensitivity), tuber.mouthSensitivity, 10..300, "${tuber.mouthSensitivity}%", 10) { value ->
                update { it.copy(mouthSensitivity = value) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.eyes_sensitivity), tuber.eyesSensitivity, 10..300, "${tuber.eyesSensitivity}%", 10) { value ->
                update { it.copy(eyesSensitivity = value) }
            }
        }
    }
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.vertical_position), tuber.cameraPositionY, 100..200, "%.2f".format(tuber.cameraPositionY / 100f)) { value ->
                update { it.copy(cameraPositionY = value) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.field_of_view), tuber.cameraFieldOfView, 10..30, "${tuber.cameraFieldOfView}\u00B0") { value ->
                update { it.copy(cameraFieldOfView = value) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.arms_angle), tuber.armsAngle, 20..90, "${tuber.armsAngle}\u00B0") { value ->
                update { it.copy(armsAngle = value) }
            }
        }
    }
}
