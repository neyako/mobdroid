package com.opirl.app.ui.widgets

import android.content.Intent
import android.provider.Settings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.settings.WidgetType
import com.opirl.app.ui.ActionRow
import com.opirl.app.ui.Group
import com.opirl.app.ui.NavRow
import com.opirl.app.ui.Note
import com.opirl.app.ui.PickerRow
import com.opirl.app.ui.RemovableRow
import com.opirl.app.ui.SliderRow
import com.opirl.app.ui.SwitchRow
import com.opirl.app.ui.TextRow
import com.opirl.app.widgets.slideshow.SLIDE_TYPES
import com.opirl.app.widgets.slideshow.Slide
import java.io.File

@Composable
internal fun ImagePage(scope: WidgetScope) {
    val widget = scope.widget
    val pick = rememberWidgetFilePicker(scope) { name ->
        widget.image?.let { File(scope.runtime.files, it).delete() }
        scope.update { it.copy(image = name) }
    }
    Group {
        row { shapes ->
            ActionRow(
                shapes,
                stringResource(if (widget.image == null) R.string.choose_image else R.string.change_image),
                Icons.Rounded.Add,
            ) { pick(arrayOf("image/*")) }
        }
    }
}

@Composable
internal fun BrowserWidgetPage(scope: WidgetScope) {
    val widget = scope.widget
    Group {
        row { TextRow(it, "URL", widget.url, "https://", keyboard = KeyboardType.Uri) { url -> scope.update { w -> w.copy(url = url.trim()) } } }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.width), widget.browserWidth, 200..1920, "${widget.browserWidth}", 10) { width ->
                scope.update { it.copy(browserWidth = width) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.height), widget.browserHeight, 100..1080, "${widget.browserHeight}", 10) { height ->
                scope.update { it.copy(browserHeight = height) }
            }
        }
    }
}

@Composable
internal fun MusicPage(scope: WidgetScope) {
    val widget = scope.widget
    val context = LocalContext.current
    val allowed by scope.engine.nowPlaying.allowed.collectAsStateWithLifecycle()
    if (allowed) {
        Group {
            row { SwitchRow(it, stringResource(R.string.cover), widget.cover) { on -> scope.update { w -> w.copy(cover = on) } } }
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.text_size), widget.textSize, 8..60, "${widget.textSize}") { size ->
                    scope.update { it.copy(textSize = size) }
                }
            }
            row { shapes ->
                SwitchRow(shapes, stringResource(R.string.hide_when_paused), widget.hideWhenPaused) { on ->
                    scope.update { it.copy(hideWhenPaused = on) }
                }
            }
        }
    } else {
        Note(stringResource(R.string.notification_access_note))
        Group {
            row { shapes ->
                NavRow(shapes, stringResource(R.string.allow_notification_access)) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

@Composable
internal fun SnapshotPage(scope: WidgetScope) {
    val showtime = scope.widget.snapshot.showtime
    Group {
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.showtime), showtime, 1..30, "$showtime s") { seconds ->
                scope.update { it.copy(snapshot = it.snapshot.copy(showtime = seconds)) }
            }
        }
    }
}

@Composable
internal fun SceneWidgetPage(scope: WidgetScope) {
    val scenes = scope.settings.scenes
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.scene),
                listOf(null) + scenes.map { it.id },
                scope.widget.sceneId.takeIf { id -> scenes.any { it.id == id } },
                { id -> scenes.firstOrNull { it.id == id }?.name ?: stringResource(R.string.none) },
            ) { id -> scope.update { it.copy(sceneId = id) } }
        }
    }
    Note(stringResource(R.string.scene_widget_note))
}

@Composable
internal fun SlideshowPage(scope: WidgetScope) {
    val slides = scope.widget.slideshow.slides
    val candidates = scope.settings.widgets.filter { it.type in SLIDE_TYPES }
    val update = { transform: (List<Slide>) -> List<Slide> ->
        scope.update { it.copy(slideshow = it.slideshow.copy(slides = transform(it.slideshow.slides))) }
    }
    val page = scope.page
    if (page != null) {
        val slide = slides.firstOrNull { it.id == page } ?: return
        Group {
            row { shapes ->
                PickerRow(
                    shapes,
                    stringResource(R.string.widget),
                    listOf(null) + candidates.map { it.id },
                    slide.widgetId.takeIf { id -> candidates.any { it.id == id } },
                    { id -> candidates.firstOrNull { it.id == id }?.name ?: stringResource(R.string.none) },
                ) { id -> update { list -> list.map { if (it.id == slide.id) it.copy(widgetId = id) else it } } }
            }
            row { shapes ->
                SliderRow(shapes, stringResource(R.string.slide_time), slide.seconds, 1..120, "${slide.seconds} s") { seconds ->
                    update { list -> list.map { if (it.id == slide.id) it.copy(seconds = seconds) else it } }
                }
            }
        }
        return
    }
    Group {
        slides.forEachIndexed { index, slide ->
            row { shapes ->
                val name = candidates.firstOrNull { it.id == slide.widgetId }?.name ?: stringResource(R.string.none)
                val title = stringResource(R.string.slide_number, index + 1)
                RemovableRow(shapes, name, "${slide.seconds} s", { scope.push(slide.id, title) }) {
                    update { list -> list.filterNot { it.id == slide.id } }
                }
            }
        }
        row { shapes ->
            ActionRow(shapes, stringResource(R.string.add_slide), Icons.Rounded.Add) {
                val slide = Slide(widgetId = candidates.firstOrNull()?.id)
                update { it + slide }
            }
        }
    }
    Note(stringResource(R.string.slideshow_note))
}

@Composable
internal fun CropPage(scope: WidgetScope) {
    val crop = scope.widget.crop
    val browsers = scope.settings.widgets.filter { it.type == WidgetType.BROWSER }
    val source = browsers.firstOrNull { it.id == crop.sourceWidgetId }
    Group {
        row { shapes ->
            PickerRow(
                shapes,
                stringResource(R.string.widget_browser),
                listOf(null) + browsers.map { it.id },
                source?.id,
                { id -> browsers.firstOrNull { it.id == id }?.name ?: stringResource(R.string.none) },
            ) { id -> scope.update { it.copy(crop = it.crop.copy(sourceWidgetId = id)) } }
        }
    }
    if (source == null) {
        Note(stringResource(R.string.crop_note))
        return
    }
    Group {
        row { shapes ->
            SliderRow(shapes, "X", crop.x, 0..source.browserWidth, "${crop.x}") { x -> scope.update { it.copy(crop = it.crop.copy(x = x)) } }
        }
        row { shapes ->
            SliderRow(shapes, "Y", crop.y, 0..source.browserHeight, "${crop.y}") { y -> scope.update { it.copy(crop = it.crop.copy(y = y)) } }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.width), crop.width, 1..source.browserWidth, "${crop.width}") { width ->
                scope.update { it.copy(crop = it.crop.copy(width = width)) }
            }
        }
        row { shapes ->
            SliderRow(shapes, stringResource(R.string.height), crop.height, 1..source.browserHeight, "${crop.height}") { height ->
                scope.update { it.copy(crop = it.crop.copy(height = height)) }
            }
        }
    }
}
