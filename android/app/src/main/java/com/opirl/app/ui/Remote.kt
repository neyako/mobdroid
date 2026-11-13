package com.opirl.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opirl.app.R
import com.opirl.app.engine.remote.RemoteAssistantState
import com.opirl.app.engine.remote.RemoteControlAssistant
import com.opirl.app.engine.remote.RemoteRequest
import com.opirl.app.engine.remote.RemoteSettings
import com.opirl.app.engine.remote.RemoteZoomPreset
import com.opirl.app.settings.RemoteControlSettings
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.StreamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun remoteZoomPresets(settings: RemoteSettings?, position: String?): List<RemoteZoomPreset> = when (position) {
    "front" -> settings?.zoomFront
    "other" -> null
    else -> settings?.zoomBack
}.orEmpty()

@Composable
fun RemotePanel(engine: StreamEngine, settings: RemoteControlSettings, onHide: () -> Unit, onClose: () -> Unit, onSettings: () -> Unit) {
    val assistant by engine.remote.assistant.collectAsStateWithLifecycle()
    PanelSurface {
        PanelHeader(stringResource(R.string.quick_remote), onHide, onClose)
        PanelBody {
            val current = assistant
            if (current == null) {
                Waiting(stringResource(R.string.message_remote_settings), onSettings)
            } else {
                Assistant(current, settings.assistantPort, onSettings)
            }
        }
    }
}

@Composable
private fun Waiting(text: String, onSettings: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Group { row { NavRow(it, stringResource(R.string.remote_control), onClick = onSettings) } }
}

@Composable
private fun Assistant(assistant: RemoteControlAssistant, port: Int, onSettings: () -> Unit) {
    val state by assistant.state.collectAsStateWithLifecycle()
    LifecycleStartEffect(assistant) {
        assistant.setActive(true)
        onStopOrDispose { assistant.setActive(false) }
    }
    if (!state.connected) {
        val addresses = remember { localAddresses() }
        val reason = stringResource(if (state.wrongPassword) R.string.message_wrong_password else R.string.remote_waiting_streamer)
        Waiting((listOf(reason) + addresses.map { "ws://$it:$port" }).joinToString("\n"), onSettings)
        return
    }
    Connected(assistant, state)
}

@Composable
private fun Connected(assistant: RemoteControlAssistant, state: RemoteAssistantState) {
    val streamer = state.streamer
    val general = state.status.general
    val live = streamer.streaming ?: general?.isLive ?: false
    val recording = streamer.recording ?: general?.isRecording ?: false
    val muted = streamer.muted ?: general?.isMuted ?: false
    val torch = streamer.torchOn == true
    val stealth = streamer.stealthMode == true
    var confirmEnd by remember { mutableStateOf(false) }
    var confirmRecord by remember { mutableStateOf(false) }
    LaunchedEffect(confirmEnd, live) {
        if (!live) {
            confirmEnd = false
        } else if (confirmEnd) {
            delay(3000)
            confirmEnd = false
        }
    }
    LaunchedEffect(confirmRecord, recording) {
        if (!recording) {
            confirmRecord = false
        } else if (confirmRecord) {
            delay(3000)
            confirmRecord = false
        }
    }

    Picture(assistant, state, live)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiveButton(
            if (live) LiveState.LIVE else LiveState.OFFLINE,
            confirmEnd,
            {
                when {
                    !live -> assistant.request(RemoteRequest.SetLive(true))
                    confirmEnd -> {
                        confirmEnd = false
                        assistant.request(RemoteRequest.SetLive(false))
                    }
                    else -> confirmEnd = true
                }
            },
            Modifier.weight(1f).height(40.dp),
        )
        BarButton(
            if (confirmRecord) Icons.Rounded.Check else if (recording) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,
            stringResource(if (confirmRecord) R.string.confirm else if (recording) R.string.stop else R.string.quick_record),
            recording,
            40.dp,
            LiveRed,
        ) {
            when {
                !recording -> assistant.request(RemoteRequest.SetRecord(true))
                confirmRecord -> {
                    confirmRecord = false
                    assistant.request(RemoteRequest.SetRecord(false))
                }
                else -> confirmRecord = true
            }
        }
        BarButton(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, stringResource(R.string.quick_mic), muted, 40.dp) {
            assistant.request(RemoteRequest.SetMute(!muted))
        }
        BarButton(
            if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff,
            stringResource(R.string.quick_torch),
            torch,
            40.dp,
        ) { assistant.request(RemoteRequest.SetTorch(!torch)) }
        BarButton(Icons.Rounded.VisibilityOff, stringResource(R.string.quick_stealth), stealth, 40.dp) {
            assistant.request(RemoteRequest.SetStealthMode(!stealth))
        }
    }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val scenes = state.settings?.scenes.orEmpty()
    if (scenes.size > 1) {
        val fill = scenes.size <= 4
        Segments(
            scenes,
            scenes.firstOrNull { it.id == streamer.scene },
            if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            { it.name },
            fill,
            container,
        ) { if (it.id != streamer.scene) assistant.request(RemoteRequest.SetScene(it.id)) }
    }
    val bitrates = state.settings?.bitratePresets.orEmpty()
    if (bitrates.isNotEmpty()) {
        val fill = bitrates.size <= 8
        Segments(
            bitrates,
            bitrates.firstOrNull { it.id == streamer.bitrate },
            if (fill) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState()),
            { formatMbps((it.bitrate / 1000).toInt()) },
            fill,
            container,
        ) { if (it.id != streamer.bitrate) assistant.request(RemoteRequest.SetBitratePreset(it.id)) }
    }
}

@Composable
private fun Picture(assistant: RemoteControlAssistant, state: RemoteAssistantState, live: Boolean) {
    val bytes by assistant.preview.collectAsStateWithLifecycle()
    val image by produceState<ImageBitmap?>(null, bytes) {
        value = bytes?.let { withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
    }
    val description = stringResource(R.string.remote_picture)
    val general = state.status.general
    val top = state.status.topRight
    val presets = remoteZoomPresets(state.settings, state.streamer.zoomCamera?.position)
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(image?.let { (it.width.toFloat() / it.height).coerceAtLeast(1f) } ?: (16f / 9f))
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { contentDescription = description },
    ) {
        image?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().background(Color.Black)) }
        Column(Modifier.align(Alignment.TopEnd).padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.End) {
            if (live) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(LiveRed))
                    Spacer(Modifier.width(5.dp))
                    Text(top?.items?.get("uptime")?.message.orEmpty(), style = statusStyle)
                }
                (top?.bitsPerSecond?.let(::formatBitrate) ?: top?.items?.get("bitrate")?.message)?.let {
                    Text(it, style = statusStyle.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                }
                top?.items?.get("srtla")?.let { Text(it.message, style = statusStyle, maxLines = 1) }
            }
            general?.batteryLevel?.let {
                val color = when (general.flame) {
                    "Red" -> Warning
                    "Yellow" -> Pending
                    else -> Color.White
                }
                Text("$it%", style = statusStyle.copy(color = color))
            }
        }
        if (presets.size > 1) {
            Segments(
                presets,
                presets.firstOrNull { it.id == state.streamer.zoomPreset },
                Modifier.align(Alignment.BottomEnd).padding(8.dp).horizontalScroll(rememberScrollState()),
                { it.name },
            ) { assistant.request(RemoteRequest.SetZoomPreset(it.id)) }
        }
    }
}
