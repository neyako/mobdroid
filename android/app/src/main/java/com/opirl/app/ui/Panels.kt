package com.opirl.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.effects.EffectLoader
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.Effects
import com.opirl.app.settings.Filter
import com.opirl.app.settings.Lut
import com.opirl.app.settings.PrivacyMode
import com.opirl.app.settings.Reaction
import com.opirl.app.settings.SettingsStore
import com.opirl.app.stream.StreamEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun PanelSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxSize().padding(6.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) { Column(content = content) }
}

@Composable
fun PanelHeader(
    title: String,
    onHide: () -> Unit,
    onClose: () -> Unit,
    onBack: (() -> Unit)? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().height(PanelHeaderHeight).padding(start = 4.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val back = remember { mutableStateOf(onBack) }
        if (onBack != null) back.value = onBack
        Appear(onBack != null, vertical = false) {
            IconButton({ back.value?.invoke() }, Modifier.size(44.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.navigate_back), Modifier.size(22.dp))
            }
        }
        val titleStart by animateDpAsState(if (onBack == null) 12.dp else 0.dp, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "title")
        Box(Modifier.weight(1f).padding(start = titleStart.coerceAtLeast(0.dp)), contentAlignment = Alignment.CenterStart) {
            if (titleContent != null) {
                titleContent()
            } else {
                val motion = MaterialTheme.motionScheme
                AnimatedContent(
                    title,
                    transitionSpec = { fadeIn(motion.defaultEffectsSpec()) togetherWith fadeOut(motion.fastEffectsSpec()) },
                    contentAlignment = Alignment.CenterStart,
                    label = "title",
                ) { shown -> Text(shown, style = PanelTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        actions()
        Spacer(Modifier.width(8.dp))
        PanelButtons(hidden = false, onHide, onClose)
    }
}

@Composable
fun ColumnScope.PanelBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 10.dp).padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
fun SidePanel(title: String, onHide: () -> Unit, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    PanelSurface {
        PanelHeader(title, onHide, onClose)
        PanelBody(content)
    }
}

@Composable
fun PanelNote(text: String, icon: ImageVector? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (icon == null) 0.dp else 20.dp),
        horizontalAlignment = if (icon == null) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = if (icon == null) TextAlign.Start else TextAlign.Center,
        )
        if (action != null) FilledTonalButton(onAction) { Text(action) }
    }
}

private val FACE_FILTERS = listOf(Filter.BLUR_FACES, Filter.BLUR_TEXT, Filter.PRIVACY)
private val PICTURE_FILTERS = Filter.entries - FACE_FILTERS.toSet() - Filter.BEAUTY - Filter.MOUTH

@Composable
fun FiltersPanel(store: SettingsStore, settings: AppSettings, engine: StreamEngine, onHide: () -> Unit, onClose: () -> Unit) {
    val effects = settings.effects
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val update = { change: (Effects) -> Effects -> store.update { it.copy(effects = change(it.effects)) } }
    val directory = File(context.filesDir, EffectLoader.DIRECTORY)
    val copy = { uri: android.net.Uri, name: String ->
        runCatching {
            directory.mkdirs()
            context.contentResolver.openInputStream(uri)!!.use { input -> File(directory, name).outputStream().use { input.copyTo(it) } }
        }.isSuccess
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val name = "face-${System.currentTimeMillis()}"
                if (copy(uri, name)) {
                    effects.faceImage?.let { File(directory, it).delete() }
                    update { it.copy(faceImage = name) }
                }
            }
        }
    }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val title = runCatching {
                    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: "LUT"
                val lut = Lut(name = title.substringBeforeLast('.'), file = "lut-${System.currentTimeMillis()}", enabled = true)
                if (copy(uri, lut.file!!)) update { it.copy(luts = it.luts + lut) }
            }
        }
    }
    val toggle = { filter: Filter, on: Boolean -> update { it.copy(filters = if (on) it.filters + filter else it.filters - filter) } }
    SidePanel(stringResource(R.string.filters), onHide, onClose) {
        Group {
            PICTURE_FILTERS.forEach { filter ->
                row { SwitchRow(it, stringResource(filter.label), filter in effects.filters) { on -> toggle(filter, on) } }
                if (filter !in effects.filters) return@forEach
                when (filter) {
                    Filter.PIXELLATE -> row { shapes ->
                        SliderRow(shapes, stringResource(R.string.strength), effects.pixellate, 0..100, "${effects.pixellate}%") { value ->
                            update { it.copy(pixellate = value) }
                        }
                    }
                    Filter.WHIRLPOOL -> row { shapes ->
                        SliderRow(shapes, stringResource(R.string.angle), effects.whirlpool, 90..360, "${effects.whirlpool}°") { value ->
                            update { it.copy(whirlpool = value) }
                        }
                    }
                    Filter.PINCH -> row { shapes ->
                        SliderRow(shapes, stringResource(R.string.strength), effects.pinch, 50..100, "${effects.pinch}%") { value ->
                            update { it.copy(pinch = value) }
                        }
                    }
                    else -> Unit
                }
            }
        }
        Group {
            FACE_FILTERS.forEach { filter ->
                row { SwitchRow(it, stringResource(filter.label), filter in effects.filters) { on -> toggle(filter, on) } }
            }
            if (FACE_FILTERS.any { it in effects.filters }) {
                row { shapes ->
                    ChoiceRow(shapes, stringResource(R.string.privacy_mode), PrivacyMode.entries, effects.privacyMode, { stringResource(it.label) }) { mode ->
                        update { it.copy(privacyMode = mode) }
                    }
                }
                when (effects.privacyMode) {
                    PrivacyMode.BLUR -> row { shapes ->
                        SliderRow(shapes, stringResource(R.string.strength), effects.blurStrength, 10..100, "${effects.blurStrength}%") { value ->
                            update { it.copy(blurStrength = value) }
                        }
                    }
                    PrivacyMode.PIXELLATE -> row { shapes ->
                        SliderRow(shapes, stringResource(R.string.strength), effects.facePixellate, 0..100, "${effects.facePixellate}%") { value ->
                            update { it.copy(facePixellate = value) }
                        }
                    }
                    PrivacyMode.IMAGE -> row { shapes ->
                        ActionRow(
                            shapes,
                            stringResource(if (effects.faceImage == null) R.string.choose_image else R.string.change_image),
                            Icons.Rounded.Add,
                        ) { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    }
                    PrivacyMode.ICON -> Unit
                }
            }
        }
        Group {
            row { SwitchRow(it, stringResource(Filter.BEAUTY.label), Filter.BEAUTY in effects.filters) { on -> toggle(Filter.BEAUTY, on) } }
            if (Filter.BEAUTY in effects.filters) {
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.beauty_smoothing), effects.smoothStrength, 0..100, "${effects.smoothStrength}%") { value ->
                        update { it.copy(smoothStrength = value) }
                    }
                }
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.beauty_smoothing_radius), effects.smoothRadius, 5..20, "${effects.smoothRadius}") { value ->
                        update { it.copy(smoothRadius = value) }
                    }
                }
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.beauty_shape), effects.shapeStrength, 0..100, "${effects.shapeStrength}%") { value ->
                        update { it.copy(shapeStrength = value) }
                    }
                }
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.beauty_shape_radius), effects.shapeRadius, 0..100, "${effects.shapeRadius}%") { value ->
                        update { it.copy(shapeRadius = value) }
                    }
                }
                row { shapes ->
                    SliderRow(shapes, stringResource(R.string.beauty_shape_position), effects.shapePosition, 0..100, "${effects.shapePosition}%") { value ->
                        update { it.copy(shapePosition = value) }
                    }
                }
            }
        }
        Group {
            row { SwitchRow(it, stringResource(Filter.MOUTH.label), Filter.MOUTH in effects.filters) { on -> toggle(Filter.MOUTH, on) } }
            Reaction.entries.forEach { reaction ->
                row { ActionRow(it, stringResource(reaction.label), Icons.Rounded.AutoAwesome) { engine.react(reaction) } }
            }
        }
        Group {
            effects.luts.forEach { lut ->
                val set = { on: Boolean ->
                    update { current -> current.copy(luts = current.luts.map { if (it.id == lut.id) it.copy(enabled = on) else it }) }
                }
                row { shapes ->
                    if (lut.file == null) {
                        SwitchRow(shapes, lut.name, lut.enabled, onChange = set)
                    } else {
                        SwitchRemovableRow(shapes, lut.name, lut.enabled, set) {
                            File(directory, lut.file).delete()
                            update { current -> current.copy(luts = current.luts.filterNot { it.id == lut.id }) }
                        }
                    }
                }
            }
            row { ActionRow(it, stringResource(R.string.add_lut), Icons.Rounded.Add) { pickLut.launch(arrayOf("*/*")) } }
        }
    }
}

@Composable
fun BitratePanel(presetsKbps: List<Int>, currentKbps: Int, onHide: () -> Unit, onClose: () -> Unit, onSelect: (Int) -> Unit) {
    SidePanel(stringResource(R.string.bitrate), onHide, onClose) {
        Group {
            presetsKbps.forEach { kbps ->
                row { shapes -> PickRow(shapes, "${formatMbps(kbps)} Mbps", kbps == currentKbps) { onSelect(kbps) } }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SwitcherPanel(store: SettingsStore, settings: AppSettings, onHide: () -> Unit, onClose: () -> Unit) {
    SidePanel(stringResource(R.string.auto_switchers), onHide, onClose) {
        if (settings.autoSwitchers.isEmpty()) {
            PanelNote(stringResource(R.string.auto_switchers_empty), Icons.Rounded.Autorenew)
            return@SidePanel
        }
        Group {
            (listOf(null) + settings.autoSwitchers).forEach { switcher ->
                row { shapes ->
                    PickRow(shapes, switcher?.name ?: stringResource(R.string.off), switcher?.id == settings.autoSwitcherId) {
                        store.update { it.copy(autoSwitcherId = switcher?.id) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MacrosPanel(settings: AppSettings, engine: StreamEngine, onHide: () -> Unit, onClose: () -> Unit) {
    val running by engine.macros.running.collectAsStateWithLifecycle()
    SidePanel(stringResource(R.string.macros), onHide, onClose) {
        if (settings.macros.isEmpty()) {
            PanelNote(stringResource(R.string.macros_empty), Icons.Rounded.Bolt)
            return@SidePanel
        }
        Group {
            settings.macros.forEach { macro ->
                val active = macro.id in running
                row { shapes ->
                    SegmentedListItem(
                        contentPadding = RowPadding,
                        colors = rowColors(),
                        onClick = {
                            engine.macros.toggle(macro.id)
                            if (!active && macro.closePanel) onClose()
                        },
                        shapes = shapes,
                        modifier = Modifier.heightIn(max = RowHeight),
                        trailingContent = {
                            Icon(
                                if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                                stringResource(if (active) R.string.stop else R.string.run),
                                tint = if (active) LiveRed else MaterialTheme.colorScheme.primary,
                            )
                        },
                    ) { Text(macro.name, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

class DrawTool {
    var color by mutableIntStateOf(DRAW_COLORS.first())
    var width by mutableFloatStateOf(6f)
}

val DRAW_COLORS = listOf(0xFFFF3B30.toInt(), 0xFFFFCC00.toInt(), 0xFF34C759.toInt(), 0xFF0A84FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrawStrip(tool: DrawTool, engine: StreamEngine, modifier: Modifier) {
    val lines by engine.drawnLines.collectAsStateWithLifecycle()
    val slider = remember { SliderState(value = (tool.width - 1) / 19) }
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactIconButton(Icons.Rounded.Delete, stringResource(R.string.draw_wipe), lines > 0, engine::drawWipe)
        CompactIconButton(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.draw_undo), lines > 0, engine::drawUndo)
        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DRAW_COLORS.forEach { color ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color(color))
                        .border(2.dp, if (color == tool.color) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.4f), CircleShape)
                        .clickable { tool.color = color },
                )
            }
        }
        Slider(
            state = slider,
            onValueChange = {
                slider.value = it
                tool.width = 1 + 19 * it
            },
            modifier = Modifier.width(96.dp).padding(horizontal = 8.dp),
            thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color(tool.color))) },
            track = { SliderDefaults.Track(it, Modifier.height(4.dp), thumbTrackGapSize = 0.dp, drawStopIndicator = null) },
        )
    }
}
