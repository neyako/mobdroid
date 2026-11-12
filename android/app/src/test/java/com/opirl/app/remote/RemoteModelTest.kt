package com.opirl.app.remote

import com.opirl.app.camera.CameraDescriptor
import com.opirl.app.camera.Facing
import com.opirl.app.engine.remote.RemoteAssistantState
import com.opirl.app.engine.remote.RemoteStatusItem
import com.opirl.app.engine.remote.RemoteStreamerState
import com.opirl.app.engine.remote.RemoteZoomCamera
import com.opirl.app.settings.AppSettings
import com.opirl.app.settings.RemoteControlSettings
import com.opirl.app.settings.Scene
import com.opirl.app.settings.StreamProfile
import com.opirl.app.stream.LinkShare
import com.opirl.app.stream.LiveState
import com.opirl.app.stream.LiveStats
import com.opirl.app.stream.RecordingStats
import com.opirl.app.stream.UiState
import kotlin.test.Test
import kotlin.test.assertEquals

private fun camera(facing: Facing) = CameraDescriptor(
    id = facing.name,
    physicalId = null,
    facing = facing,
    label = facing.name,
    zoomRatioRange = 0.6f..10f,
    lensZoomStops = listOf(0.6f, 1f, 3f),
    sensorOrientation = 90,
    sizes = emptyList(),
    fpsRanges = emptyList(),
    supports60Fps = true,
    stabilizationModes = emptyList(),
    hasFlash = true,
)

class RemoteModelTest {
    private val settings = AppSettings(
        profiles = listOf(StreamProfile(id = "p", name = "Main", bitrateKbps = 6000)),
        scenes = listOf(Scene(id = "s1", name = "Back"), Scene(id = "s2", name = "Front")),
        selectedSceneId = "s2",
    )

    @Test
    fun `the state names the scene, the preset bitrate and the zoom stop in use`() {
        val ui = UiState(camera = camera(Facing.BACK), zoom = 3f, torch = true, live = LiveState.LIVE, stealth = true, charging = true)
        val state = remoteState(settings, ui, recording = true)
        assertEquals("s2", state.scene)
        assertEquals(bitratePresetId(6000), state.bitrate)
        assertEquals(zoomPresetId(3f), state.zoomPreset)
        assertEquals(RemoteZoomCamera("back", 0.6f, 10f), state.zoomCamera)
        assertEquals(listOf(true, true, true, true, true), listOf(state.streaming, state.recording, state.torchOn, state.stealthMode, state.batteryCharging))
        val between = remoteState(settings.copy(profiles = listOf(StreamProfile(bitrateKbps = 6100))), ui.copy(zoom = 2f), recording = false)
        assertEquals(listOf(null, null), listOf(between.bitrate, between.zoomPreset))
    }

    @Test
    fun `the settings list streams, scenes, presets and each side's zoom stops`() {
        val remote = remoteSettings(settings, listOf(camera(Facing.FRONT), camera(Facing.BACK)), "Auto", listOf("USB:Rode:1" to "USB: Rode"))
        assertEquals(listOf("Main"), remote.streams.map { it.name })
        assertEquals(listOf("s1", "s2"), remote.scenes.map { it.id })
        assertEquals(listOf("AUTO", "USB:Rode:1"), remote.mics.map { it.id })
        assertEquals(settings.bitratePresetsKbps.map { it * 1000L }, remote.bitratePresets.map { it.bitrate })
        assertEquals(listOf("0.6x", "1x", "3x"), remote.zoomBack.map { it.name })
        assertEquals(listOf(0.6f, 1f, 3f), remote.zoomFront.map { it.x })
    }

    @Test
    fun `the status shows live items only while live`() {
        val input = { ui: UiState, stats: LiveStats, recording: RecordingStats? ->
            RemoteStatusInput(settings, ui, stats, recording, -20f, "Main (1080p30, HEVC, SRTLA)", "Automatic", "Scene 1", RemoteStatusItem("Blue🔋80%"), null, 1, "Muted")
        }
        val offline = remoteStatus(input(UiState(battery = 55, muted = true), LiveStats(), null))
        assertEquals(setOf("audioLevel", "gameController", "moblink"), offline.topRight!!.items.keys)
        assertEquals("Muted", offline.topRight!!.items.getValue("audioLevel").message)
        assertEquals(listOf(55, false, true, "White"), offline.general!!.let { listOf(it.batteryLevel, it.isLive, it.isMuted, it.flame) })
        assertEquals(listOf("Main (1080p30, HEVC, SRTLA)", "Front", "Automatic", "Scene 1"), listOf("stream", "camera", "mic", "obs").map { offline.topLeft!!.getValue(it).message })
        val live = remoteStatus(
            input(
                UiState(live = LiveState.LIVE, thermal = 4),
                LiveStats(bitrateBps = 4_000_000, uptimeSeconds = 65, links = listOf(LinkShare("Cell", 75, 40), LinkShare("Blue", 25)), totalBytes = 10),
                RecordingStats(seconds = 5),
            ),
        )
        assertEquals("4.0 Mbps", live.topRight!!.items.getValue("bitrate").message)
        assertEquals("0:01:05", live.topRight!!.items.getValue("uptime").message)
        assertEquals("Cell 75%, Blue 25%", live.topRight!!.items.getValue("srtla").message)
        assertEquals("0:00:05", live.topRight!!.items.getValue("recording").message)
        assertEquals("Red", live.general!!.flame)
        assertEquals(listOf(375_000L to 40, 125_000L to null), live.topRight!!.bonding!!.map { it.bytesPerSecond to it.rtt })
    }

    @Test
    fun `the status line names the roles that are connected`() {
        val connected = RemoteAssistantState(connected = true)
        assertEquals(RemoteSummary.ASSISTANT_AND_STREAMER, remoteSummary(RemoteStreamerState.CONNECTED, connected, 0))
        assertEquals(RemoteSummary.ASSISTANT, remoteSummary(null, connected, 0))
        assertEquals(RemoteSummary.STREAMER, remoteSummary(RemoteStreamerState.CONNECTED, null, 0))
        assertEquals(RemoteSummary.WEB, remoteSummary(RemoteStreamerState.CONNECTING, null, 2))
        assertEquals(RemoteSummary.WRONG_PASSWORD, remoteSummary(RemoteStreamerState.WRONG_PASSWORD, null, 0))
        assertEquals(RemoteSummary.DISCONNECTED, remoteSummary(RemoteStreamerState.CONNECTING, null, 0))
        assertEquals(null, remoteSummary(null, null, 0))
    }

    @Test
    fun `a relay gives the streamer its own URL`() {
        assertEquals("wss://relay.example/base/streamer/abc", relayStreamerUrl(RemoteControlSettings(relayUrl = "wss://relay.example/base/", bridgeId = "abc")))
    }
}
