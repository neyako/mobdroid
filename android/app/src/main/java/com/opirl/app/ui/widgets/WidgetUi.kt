package com.opirl.app.ui.widgets

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Scoreboard
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.opirl.app.R
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.SettingsStore
import com.opirl.app.settings.Widget
import com.opirl.app.settings.WidgetType
import com.opirl.app.stream.StreamEngine
import com.opirl.app.ui.Group
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.widgets.WidgetRuntime
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WidgetScope internal constructor(
    val store: SettingsStore,
    val engine: StreamEngine,
    val settings: AppSettings,
    val widget: Widget,
    val page: String?,
    private val navigate: (String, String) -> Unit,
    val pop: () -> Unit,
) {
    val runtime: WidgetRuntime get() = engine.widgets

    fun update(transform: (Widget) -> Widget) = runtime.update(widget.id, transform)

    fun push(page: String, title: String) = navigate(page, title)
}

internal val WidgetType.icon: ImageVector
    get() = when (this) {
        WidgetType.TEXT -> Icons.Rounded.TextFields
        WidgetType.BROWSER -> Icons.Rounded.Public
        WidgetType.VIDEO_SOURCE -> Icons.Rounded.Videocam
        WidgetType.IMAGE -> Icons.Rounded.Image
        WidgetType.ALERTS -> Icons.Rounded.Campaign
        WidgetType.MAP -> Icons.Rounded.Map
        WidgetType.SNAPSHOT -> Icons.Rounded.PhotoCamera
        WidgetType.CHAT -> Icons.AutoMirrored.Rounded.Chat
        WidgetType.MUSIC -> Icons.Rounded.MusicNote
        WidgetType.SCENE -> Icons.Rounded.Layers
        WidgetType.SLIDESHOW -> Icons.Rounded.Slideshow
        WidgetType.VTUBER -> Icons.Rounded.AccountCircle
        WidgetType.PNGTUBER -> Icons.Rounded.Face
        WidgetType.QR_CODE -> Icons.Rounded.QrCode2
        WidgetType.SCOREBOARD -> Icons.Rounded.Scoreboard
        WidgetType.WHEEL_OF_LUCK -> Icons.Rounded.Casino
        WidgetType.BINGO_CARD -> Icons.Rounded.GridOn
        WidgetType.CROP -> Icons.Rounded.Crop
        WidgetType.POMODORO -> Icons.Rounded.Timer
    }

@Composable
internal fun WidgetSettings(scope: WidgetScope) = when (scope.widget.type) {
    WidgetType.TEXT -> TextPage(scope)
    WidgetType.BROWSER -> BrowserWidgetPage(scope)
    WidgetType.VIDEO_SOURCE -> VideoSourcePage(scope)
    WidgetType.IMAGE -> ImagePage(scope)
    WidgetType.ALERTS -> AlertsPage(scope)
    WidgetType.MAP -> MapPage(scope)
    WidgetType.SNAPSHOT -> SnapshotPage(scope)
    WidgetType.CHAT -> Unit
    WidgetType.MUSIC -> MusicPage(scope)
    WidgetType.SCENE -> SceneWidgetPage(scope)
    WidgetType.SLIDESHOW -> SlideshowPage(scope)
    WidgetType.VTUBER -> VTuberPage(scope)
    WidgetType.PNGTUBER -> PngTuberPage(scope)
    WidgetType.QR_CODE -> QrCodePage(scope)
    WidgetType.SCOREBOARD -> ScoreboardPage(scope)
    WidgetType.WHEEL_OF_LUCK -> WheelPage(scope)
    WidgetType.BINGO_CARD -> BingoPage(scope)
    WidgetType.CROP -> CropPage(scope)
    WidgetType.POMODORO -> PomodoroPage(scope)
}

internal val CONTROLLED = setOf(
    WidgetType.TEXT,
    WidgetType.SCOREBOARD,
    WidgetType.WHEEL_OF_LUCK,
    WidgetType.BINGO_CARD,
    WidgetType.POMODORO,
)

@Composable
internal fun WidgetControls(scope: WidgetScope) = when (scope.widget.type) {
    WidgetType.TEXT -> TextControls(scope)
    WidgetType.SCOREBOARD -> ScoreboardControls(scope)
    WidgetType.WHEEL_OF_LUCK -> WheelControls(scope)
    WidgetType.BINGO_CARD -> BingoControls(scope)
    WidgetType.POMODORO -> PomodoroControls(scope)
    else -> Unit
}

fun widgetFiles(runtime: WidgetRuntime, widgetId: String): List<File> =
    runtime.files.listFiles { file -> file.name.startsWith(widgetId) }?.toList().orEmpty()

@Composable
fun rememberWidgetFilePicker(scope: WidgetScope, onPicked: (String) -> Unit): (Array<String>) -> Unit {
    val context = LocalContext.current
    val coroutines = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            coroutines.launch(Dispatchers.IO) {
                val name = "${scope.widget.id}-${System.currentTimeMillis()}"
                val copied = runCatching {
                    scope.runtime.files.mkdirs()
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        File(scope.runtime.files, name).outputStream().use { input.copyTo(it) }
                    }
                }.isSuccess
                if (copied) onPicked(name)
            }
        }
    }
    return { types -> launcher.launch(types) }
}

@Composable
fun LocationAccess(engine: StreamEngine) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var checked by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        checked++
        engine.syncWidgetSources()
    }
    LifecycleResumeEffect(Unit) {
        checked++
        engine.syncWidgetSources()
        onPauseOrDispose {}
    }
    val allowed = remember(checked) { engine.location.allowed }
    if (allowed) return
    Note(stringResource(R.string.location_access_note))
    Group {
        row { shapes ->
            NavRow(shapes, stringResource(R.string.allow_location_access)) {
                val permission = Manifest.permission.ACCESS_FINE_LOCATION
                if (checked > 1 && activity?.shouldShowRequestPermissionRationale(permission) == false) {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } else {
                    launcher.launch(arrayOf(permission, Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }
        }
    }
}
