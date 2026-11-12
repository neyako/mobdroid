package com.opirl.app.remote

import android.os.PowerManager
import com.opirl.app.camera.CameraDescriptor
import com.opirl.app.camera.Facing
import com.opirl.app.engine.remote.RemoteBitratePreset
import com.opirl.app.engine.remote.RemoteBondingConnection
import com.opirl.app.engine.remote.RemoteNamed
import com.opirl.app.engine.remote.RemoteSettings
import com.opirl.app.engine.remote.RemoteState
import com.opirl.app.engine.remote.RemoteStatus
import com.opirl.app.engine.remote.RemoteStatusGeneral
import com.opirl.app.engine.remote.RemoteStatusItem
import com.opirl.app.engine.remote.RemoteStatusTopRight
import com.opirl.app.engine.remote.RemoteZoomCamera
import com.opirl.app.engine.remote.RemoteZoomPreset
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.MicSource
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.LiveStats
import com.opirl.app.stream.RecordingStats
import com.opirl.app.stream.UiState
import com.opirl.app.ui.formatBitrate
import com.opirl.app.ui.formatUptime
import com.opirl.app.ui.formatZoom
import java.util.Locale
import java.util.UUID

fun bitratePresetId(kbps: Int): String = UUID.nameUUIDFromBytes("bitrate-$kbps".toByteArray()).toString()

fun zoomPresetId(ratio: Float): String = UUID.nameUUIDFromBytes("zoom-$ratio".toByteArray()).toString()

private fun zoomPresets(camera: CameraDescriptor?) = camera?.lensZoomStops.orEmpty().map { RemoteZoomPreset(zoomPresetId(it), formatZoom(it), it) }

fun remoteSettings(settings: AppSettings, cameras: List<CameraDescriptor>, micName: (MicSource) -> String) = RemoteSettings(
    streams = settings.profiles.map { RemoteNamed(it.id, it.name) },
    scenes = settings.scenes.map { RemoteNamed(it.id, it.name) },
    bitratePresets = settings.bitratePresetsKbps.map { RemoteBitratePreset(bitratePresetId(it), it * 1000L) },
    mics = MicSource.entries.map { RemoteNamed(it.name, micName(it)) },
    zoomFront = zoomPresets(cameras.firstOrNull { it.facing == Facing.FRONT }),
    zoomBack = zoomPresets(cameras.firstOrNull { it.facing == Facing.BACK }),
)

fun remoteState(settings: AppSettings, ui: UiState, recording: Boolean): RemoteState {
    val camera = ui.camera
    val stops = camera?.lensZoomStops.orEmpty()
    return RemoteState(
        scene = settings.selectedScene?.id,
        mic = settings.mic.name,
        bitrate = settings.selectedProfile.bitrateKbps.takeIf { it in settings.bitratePresetsKbps }?.let(::bitratePresetId),
        zoom = ui.zoom,
        zoomPreset = stops.firstOrNull { kotlin.math.abs(it - ui.zoom) < 0.01f }?.let(::zoomPresetId),
        zoomCamera = camera?.let {
            RemoteZoomCamera(
                when (it.facing) {
                    Facing.FRONT -> "front"
                    Facing.BACK -> "back"
                    Facing.EXTERNAL -> "other"
                },
                minOf(stops.minOrNull() ?: 1f, it.zoomRatioRange.start),
                it.zoomRatioRange.endInclusive,
            )
        },
        debugLogging = false,
        streaming = ui.live != LiveState.OFFLINE,
        recording = recording,
        muted = ui.muted,
        stealthMode = ui.stealth,
        torchOn = ui.torch,
        batteryCharging = ui.charging,
    )
}

class RemoteStatusInput(
    val settings: AppSettings,
    val ui: UiState,
    val stats: LiveStats,
    val recording: RecordingStats?,
    val audioLevel: Float,
    val streamText: String,
    val micName: String,
    val obs: String?,
    val moblink: RemoteStatusItem?,
    val remoteControl: RemoteStatusItem?,
    val gameControllers: Int,
    val mutedText: String,
)

fun remoteStatus(input: RemoteStatusInput): RemoteStatus {
    val ui = input.ui
    val live = ui.live != LiveState.OFFLINE
    val topLeft = LinkedHashMap<String, RemoteStatusItem>()
    topLeft["stream"] = RemoteStatusItem(input.streamText)
    (input.settings.selectedScene?.name ?: ui.camera?.label)?.let { topLeft["camera"] = RemoteStatusItem(it) }
    topLeft["mic"] = RemoteStatusItem(input.micName)
    if (ui.camera != null) topLeft["zoom"] = RemoteStatusItem(formatZoom(ui.zoom))
    input.obs?.let { topLeft["obs"] = RemoteStatusItem(it) }
    val topRight = LinkedHashMap<String, RemoteStatusItem>()
    topRight["audioLevel"] = RemoteStatusItem(if (ui.muted) input.mutedText else "%.0f dB".format(Locale.ROOT, input.audioLevel))
    input.remoteControl?.let { topRight["remoteControl"] = it }
    if (input.gameControllers > 0) topRight["gameController"] = RemoteStatusItem("${input.gameControllers}")
    if (live) {
        topRight["bitrate"] = RemoteStatusItem(formatBitrate(input.stats.bitrateBps))
        topRight["uptime"] = RemoteStatusItem(formatUptime(input.stats.uptimeSeconds))
    }
    if (input.stats.links.isNotEmpty()) {
        topRight["srtla"] = RemoteStatusItem(input.stats.links.joinToString(", ") { "${it.name} ${it.percent}%" })
    }
    input.recording?.let { topRight["recording"] = RemoteStatusItem(formatUptime(it.seconds)) }
    input.moblink?.let { topRight["moblink"] = it }
    return RemoteStatus(
        RemoteStatusGeneral(
            batteryCharging = ui.charging,
            batteryLevel = ui.battery,
            flame = when {
                ui.thermal >= PowerManager.THERMAL_STATUS_CRITICAL -> "Red"
                ui.thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Yellow"
                else -> "White"
            },
            isLive = live,
            isRecording = input.recording != null,
            isMuted = ui.muted,
        ),
        topLeft,
        RemoteStatusTopRight(
            items = topRight,
            audioLevel = input.audioLevel,
            muted = ui.muted,
            bitsPerSecond = input.stats.bitrateBps.takeIf { live },
            totalBytes = input.stats.totalBytes.takeIf { live },
            bonding = input.stats.links.takeIf { it.isNotEmpty() }?.map {
                RemoteBondingConnection(it.name, input.stats.bitrateBps * it.percent / 800, it.rttMs.takeIf { rtt -> rtt > 0 })
            },
        ),
    )
}
