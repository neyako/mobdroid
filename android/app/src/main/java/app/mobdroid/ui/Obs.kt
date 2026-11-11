package app.mobdroid.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobdroid.engine.obs.ObsClient
import app.mobdroid.engine.obs.ObsInput
import app.mobdroid.engine.obs.ObsOutput
import app.mobdroid.engine.obs.ObsState
import app.mobdroid.engine.obs.obsUrl
import app.mobdroid.settings.AppSettings
import app.mobdroid.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun obsStatus(state: ObsState): String = if (state.connected) {
    listOfNotNull(
        "OBS ${state.scene.orEmpty()}".trim(),
        "live".takeIf { state.stream == ObsOutput.STARTED },
        "rec".takeIf { state.record == ObsOutput.STARTED },
    ).joinToString(", ")
} else {
    "OBS offline"
}

@Composable
fun ObsPanel(
    store: SettingsStore,
    obs: ObsClient,
    settings: AppSettings,
    onHide: () -> Unit,
    onClose: () -> Unit,
    onSettings: () -> Unit,
) {
    val profile = settings.selectedProfile
    val url = obsUrl(profile.obs.url).takeIf { profile.obs.enabled }
    val state by obs.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(obs) {
        obs.setScreenshots(true)
        onStopOrDispose { obs.setScreenshots(false) }
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column {
            Row(
                Modifier.fillMaxWidth().height(PanelHeaderHeight).padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) { Text("OBS", style = PanelTitle) }
                PanelButtons(hidden = false, onHide, onClose)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    url == null -> Setup(profile.obs.url, profile.obs.password) { newUrl, password ->
                        store.updateProfile(profile.copy(obs = profile.obs.copy(enabled = true, url = newUrl, password = password)))
                    }
                    !state.connected -> {
                        Text(
                            if (state.error == null) {
                                "Connecting to $url"
                            } else {
                                "${state.error} ($url). Retrying every 5 seconds."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                        Group { row { NavRow(it, "OBS remote settings", onClick = onSettings) } }
                    }
                    else -> Connected(obs, state)
                }
            }
        }
    }
}

@Composable
private fun Setup(url: String, password: String, onConnect: (String, String) -> Unit) {
    var urlDraft by remember { mutableStateOf(url) }
    var passwordDraft by remember { mutableStateOf(password) }
    val valid = obsUrl(urlDraft)
    Group {
        row { TextRow(it, "URL", urlDraft, "ws://192.168.1.20:4455", keyboard = KeyboardType.Uri) { text -> urlDraft = text } }
        row { TextRow(it, "Password", passwordDraft, keyboard = KeyboardType.Password) { text -> passwordDraft = text } }
        row { shapes ->
            ActionRow(shapes, "Connect", Icons.Rounded.Link, enabled = valid != null) { onConnect(valid!!, passwordDraft) }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Connected(obs: ObsClient, state: ObsState) {
    Row(Modifier.fillMaxWidth().height(88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Snapshot(obs, state.scene.orEmpty())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutputButton(
                "Stream",
                "Streaming",
                state.stream,
                state.streamStartedAtMs,
                obs,
                RoundedCornerShape(16.dp, 16.dp, 4.dp, 4.dp),
                obs::startStream,
                obs::stopStream,
                Modifier.weight(1f),
            )
            OutputButton(
                "Record",
                "Recording",
                state.record,
                state.recordStartedAtMs,
                obs,
                RoundedCornerShape(4.dp, 4.dp, 16.dp, 16.dp),
                obs::startRecord,
                obs::stopRecord,
                Modifier.weight(1f),
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.scenes.forEach { scene ->
            ToggleButton(
                checked = scene == state.scene,
                onCheckedChange = { if (scene != state.scene) obs.setScene(scene) },
                colors = ToggleButtonDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text(scene, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (state.inputs.isNotEmpty()) {
        Group { state.inputs.forEach { input -> row { MuteRow(it, input) { obs.setMuted(input.name, !input.muted) } } } }
    }
}

@Composable
private fun Snapshot(obs: ObsClient, scene: String) {
    val bytes by obs.screenshot.collectAsStateWithLifecycle()
    val image by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    }
    Box(
        Modifier
            .size(156.dp, 88.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .semantics { contentDescription = "OBS picture" },
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        Text(
            scene,
            style = MaterialTheme.typography.labelMedium.copy(shadow = Shadow(Color.Black, blurRadius = 6f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OutputButton(
    label: String,
    activeLabel: String,
    output: ObsOutput,
    startedAtMs: Long,
    obs: ObsClient,
    shape: Shape,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier,
) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(confirm, output) {
        if (output != ObsOutput.STARTED) {
            confirm = false
        } else if (confirm) {
            delay(3000)
            confirm = false
        }
    }
    val active = output == ObsOutput.STARTED
    Button(
        onClick = {
            when {
                output == ObsOutput.STOPPED -> onStart()
                !active -> Unit
                confirm -> {
                    confirm = false
                    onStop()
                }
                else -> confirm = true
            }
        },
        shapes = ButtonDefaults.shapes(shape = shape, pressedShape = RoundedCornerShape(8.dp)),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainer,
            contentColor = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
        ),
        contentPadding = PaddingValues(horizontal = 14.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            when {
                confirm -> "Confirm stop"
                output == ObsOutput.STARTING -> "Starting"
                output == ObsOutput.STOPPING -> "Stopping"
                active -> activeLabel
                else -> label
            },
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (active && !confirm) Elapsed(obs, startedAtMs)
    }
}

@Composable
private fun Elapsed(obs: ObsClient, startedAtMs: Long) {
    var seconds by remember(startedAtMs) { mutableLongStateOf(obs.elapsedMs(startedAtMs) / 1000) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            delay(1000 - obs.elapsedMs(startedAtMs).mod(1000L))
            seconds = obs.elapsedMs(startedAtMs) / 1000
        }
    }
    Text(
        formatUptime(seconds.coerceAtLeast(0)),
        style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MuteRow(shapes: ListItemShapes, input: ObsInput, onClick: () -> Unit) {
    SegmentedListItem(
        contentPadding = RowPadding,
        colors = rowColors(),
        onClick = onClick,
        shapes = shapes,
        modifier = Modifier.heightIn(max = RowHeight),
        trailingContent = {
            Icon(
                if (input.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                if (input.muted) "Muted" else "Not muted",
                Modifier.size(20.dp),
                tint = if (input.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) { Text(input.name, style = RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
