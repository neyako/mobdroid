package com.opirl.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.settings.REPLAY_SPEEDS
import com.opirl.app.settings.SettingsStore
import com.opirl.app.stream.ReplayState
import com.opirl.app.stream.StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReplayPanel {
    var open by mutableStateOf(false)
    var selected by mutableStateOf<Uri?>(null)
    var fromEndMs by mutableLongStateOf(10_000)
}

fun replayStartMs(durationMs: Long, fromEndMs: Long): Long = (durationMs - fromEndMs).coerceIn(0, maxOf(durationMs - 1000, 0))

fun formatSpeed(percent: Int): String = if (percent % 100 == 0) "${percent / 100}x" else "${percent / 100f}x"

private fun replayFrame(context: Context, uri: Uri, positionMs: Long): Bitmap? = runCatching {
    MediaMetadataRetriever().use {
        it.setDataSource(context, uri)
        it.getScaledFrameAtTime(positionMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, 384, 384)
    }
}.getOrNull()

private val Scrim = Color.Black.copy(alpha = 0.55f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayOverlay(engine: StreamEngine, store: SettingsStore, panel: ReplayPanel, aspect: Float, modifier: Modifier) {
    val recordings by engine.recordings.recordings.collectAsStateWithLifecycle()
    val state by engine.replay.collectAsStateWithLifecycle()
    val settings by store.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val replays = remember(recordings) { recordings.filter { it.isReplay } }
    val newest = replays.firstOrNull()?.uri
    LaunchedEffect(newest) { panel.selected = newest }
    val selected = replays.firstOrNull { it.uri == panel.selected }
    val durationMs = selected?.durationMs ?: 0
    val maxStartMs = maxOf(durationMs - 1000, 0)
    val startMs = replayStartMs(durationMs, panel.fromEndMs)
    val slider = remember { SliderState() }
    LaunchedEffect(startMs, maxStartMs) {
        if (!slider.isDragging) slider.value = if (maxStartMs > 0) startMs.toFloat() / maxStartMs else 0f
    }
    val position = rememberUpdatedState(startMs)
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(selected?.uri) {
        frame = null
        val uri = selected?.uri ?: return@LaunchedEffect
        snapshotFlow { position.value }.conflate().collect { ms ->
            frame = withContext(Dispatchers.IO) { replayFrame(context, uri, ms) }?.asImageBitmap()
        }
    }
    val delete: @Composable (Modifier) -> Unit = { buttonModifier ->
        selected?.let { replay ->
            DeleteReplayButton(replay.uri, buttonModifier) { scope.launch(Dispatchers.IO) { engine.recordings.delete(replay.uri) } }
        }
    }
    BoxWithConstraints(modifier.padding(10.dp), contentAlignment = Alignment.BottomEnd) {
        val compact = with(LocalDensity.current) { constraints.maxHeight.toDp() } < 260.dp
        val thumbHeight = if (compact) 36.dp else 46.dp
        val controls: @Composable () -> Unit = {
            val primary = MaterialTheme.colorScheme.primary
            val startLabel = stringResource(R.string.replay_start)
            Row(
                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).background(Scrim).padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Slider(
                    state = slider,
                    onValueChange = {
                        slider.value = it
                        panel.fromEndMs = durationMs - (it * maxStartMs).toLong()
                    },
                    modifier = Modifier.weight(1f).semantics { contentDescription = startLabel },
                    enabled = maxStartMs > 0,
                    thumb = { if (maxStartMs > 0) Box(Modifier.size(12.dp).clip(CircleShape).background(primary)) },
                    track = {
                        SliderDefaults.Track(
                            it,
                            Modifier.height(4.dp),
                            enabled = maxStartMs > 0,
                            colors = SliderDefaults.colors(activeTrackColor = Color.White.copy(alpha = 0.3f), inactiveTrackColor = primary),
                            thumbTrackGapSize = 0.dp,
                            drawStopIndicator = null,
                        )
                    },
                )
                Text(
                    "${((if (selected != null) durationMs - startMs else panel.fromEndMs) + 500) / 1000} s",
                    style = statusStyle,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Segments(REPLAY_SPEEDS, settings.replaySpeedPercent, Modifier, ::formatSpeed, small = true, container = Color.White.copy(alpha = 0.14f)) { speed ->
                    store.update { it.copy(replaySpeedPercent = speed) }
                }
                if (state == ReplayState.PLAYING) {
                    CompactIconButton(Icons.Rounded.Stop, stringResource(R.string.stop_replay), onClick = engine::stopReplay)
                } else {
                    CompactIconButton(
                        Icons.Rounded.PlayArrow,
                        stringResource(R.string.play_replay),
                        enabled = selected != null && state == ReplayState.IDLE,
                    ) { selected?.let { engine.playReplay(it.uri, startMs) } }
                }
                Box(Modifier.size(1.dp, 20.dp).background(Color.White.copy(alpha = 0.35f)))
                CompactIconButton(
                    Icons.Rounded.SaveAlt,
                    stringResource(R.string.save_replay),
                    enabled = state == ReplayState.IDLE,
                    onClick = engine::saveReplay,
                )
            }
        }
        val history: @Composable () -> Unit = {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Scrim).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (replays.isEmpty()) {
                    Text(
                        stringResource(R.string.no_replays),
                        style = statusStyle,
                        modifier = Modifier.weight(1f).height(thumbHeight).wrapContentHeight().padding(horizontal = 10.dp),
                    )
                } else {
                    LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(replays, key = { it.uri.toString() }) { replay ->
                            val chosen = replay.uri == selected?.uri
                            ReplayThumb(replay.uri, replay.name, frame.takeIf { chosen }, chosen, Modifier.size(thumbHeight * aspect, thumbHeight)) {
                                panel.selected = replay.uri
                            }
                        }
                    }
                }
                if (compact && state != ReplayState.PLAYING) delete(Modifier.padding(start = 4.dp))
            }
        }
        Column(
            Modifier.widthIn(max = 372.dp).fillMaxWidth().pointerInput(Unit) {},
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (compact) {
                history()
                controls()
            } else {
                if (selected != null && state != ReplayState.PLAYING) {
                    Box {
                        Box(
                            Modifier
                                .size(96.dp * aspect, 96.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            frame?.let { Image(it, selected.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        }
                        delete(Modifier.align(Alignment.TopStart).padding(6.dp))
                    }
                }
                controls()
                history()
            }
        }
    }
}

@Composable
private fun ReplayThumb(uri: Uri, name: String, frame: ImageBitmap?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(192, 192), null).asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(if (selected) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .clickable(onClick = onClick),
    ) {
        (frame ?: thumbnail)?.let { Image(it, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

@Composable
private fun DeleteReplayButton(uri: Uri, modifier: Modifier, onDelete: () -> Unit) {
    var confirm by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(confirm) {
        if (confirm) {
            delay(3000)
            confirm = false
        }
    }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (confirm) colors.error else Scrim)
            .clickable { if (confirm) onDelete() else confirm = true },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Delete,
            stringResource(if (confirm) R.string.confirm else R.string.delete_replay),
            Modifier.size(18.dp),
            tint = if (confirm) colors.onError else colors.error,
        )
    }
}
