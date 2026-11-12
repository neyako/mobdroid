package com.opirl.app.remote

import android.content.Context
import android.graphics.Bitmap
import com.opirl.app.R
import com.opirl.app.engine.remote.RemoteAssistantState
import com.opirl.app.engine.remote.RemoteControlAssistant
import com.opirl.app.engine.remote.RemoteControlBridge
import com.opirl.app.engine.remote.RemoteControlStreamer
import com.opirl.app.engine.remote.RemoteControlTarget
import com.opirl.app.engine.remote.RemoteControlWeb
import com.opirl.app.engine.remote.RemoteState
import com.opirl.app.engine.remote.RemoteStreamerState
import com.opirl.app.settings.RemoteControlSettings
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

data class RemoteControlStatus(val text: String, val ok: Boolean)

fun isWebSocketUrl(url: String): Boolean {
    val trimmed = url.trim()
    return (trimmed.startsWith("ws://") || trimmed.startsWith("wss://")) && trimmed.substringAfter("://").isNotEmpty()
}

fun relayStreamerUrl(settings: RemoteControlSettings): String = "${settings.relayUrl.trim().trimEnd('/')}/streamer/${settings.bridgeId}"

enum class RemoteSummary { ASSISTANT_AND_STREAMER, ASSISTANT, STREAMER, WEB, WRONG_PASSWORD, DISCONNECTED }

fun remoteSummary(streamer: RemoteStreamerState?, assistant: RemoteAssistantState?, webClients: Int): RemoteSummary? = when {
    streamer == RemoteStreamerState.CONNECTED && assistant?.connected == true -> RemoteSummary.ASSISTANT_AND_STREAMER
    assistant?.connected == true -> RemoteSummary.ASSISTANT
    streamer == RemoteStreamerState.CONNECTED -> RemoteSummary.STREAMER
    webClients > 0 -> RemoteSummary.WEB
    streamer == RemoteStreamerState.WRONG_PASSWORD || assistant?.wrongPassword == true -> RemoteSummary.WRONG_PASSWORD
    streamer == null && assistant == null -> null
    else -> RemoteSummary.DISCONNECTED
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RemoteControlController(
    context: Context,
    private val scope: CoroutineScope,
    private val http: OkHttpClient,
    private val target: RemoteControlTarget,
    private val thumbnail: (width: Int, onResult: (Bitmap?) -> Unit) -> Unit,
    private val onAssistantError: (String) -> Unit,
) {
    private val context = context.applicationContext
    private var settings = RemoteControlSettings()
    private var streamer: RemoteControlStreamer? = null
    private var web: RemoteControlWeb? = null
    private var bridge: RemoteControlBridge? = null
    private val _assistant = MutableStateFlow<RemoteControlAssistant?>(null)
    val assistant: StateFlow<RemoteControlAssistant?> = _assistant.asStateFlow()
    private val streamerState = MutableStateFlow<StateFlow<RemoteStreamerState>?>(null)
    private val webClients = MutableStateFlow<StateFlow<Int>?>(null)
    private val _status = MutableStateFlow<RemoteControlStatus?>(null)
    val status: StateFlow<RemoteControlStatus?> = _status.asStateFlow()
    private var streamerPreview = false
    private var webPreview = false
    private var previewJob: Job? = null
    private var sent = RemoteState()

    init {
        scope.launch {
            combine(
                streamerState.flatMapLatest { it ?: flowOf(null) },
                _assistant.flatMapLatest { it?.state ?: flowOf(null) },
                webClients.flatMapLatest { it ?: flowOf(0) },
            ) { streamer, assistant, clients -> remoteSummary(streamer, assistant, clients) }.collect { summary ->
                _status.value = summary?.let {
                    RemoteControlStatus(
                        this@RemoteControlController.context.getString(
                            when (it) {
                                RemoteSummary.ASSISTANT_AND_STREAMER -> R.string.remote_assistant_and_streamer
                                RemoteSummary.ASSISTANT -> R.string.remote_assistant
                                RemoteSummary.STREAMER -> R.string.remote_streamer
                                RemoteSummary.WEB -> R.string.remote_web
                                RemoteSummary.WRONG_PASSWORD -> R.string.message_wrong_password
                                RemoteSummary.DISCONNECTED -> R.string.remote_disconnected
                            },
                        ),
                        it != RemoteSummary.WRONG_PASSWORD && it != RemoteSummary.DISCONNECTED,
                    )
                }
            }
        }
    }

    @Synchronized
    fun sync(next: RemoteControlSettings, active: Boolean) {
        val previous = settings
        settings = next
        val password = next.password.isNotEmpty()

        val streamerWanted = active && next.streamer && password && isWebSocketUrl(next.streamerUrl)
        if (streamer != null && (!streamerWanted || previous.streamerUrl != next.streamerUrl || previous.password != next.password)) {
            streamer?.stop()
            streamer = null
            streamerState.value = null
            setPreview(streamer = false)
        }
        if (streamerWanted && streamer == null) {
            val created = RemoteControlStreamer(http, next.streamerUrl.trim(), next.password, next.streamerId, target, { setPreview(streamer = it) })
            streamer = created
            streamerState.value = created.state
            created.start()
        }

        val webWanted = active && next.web && next.webPort in 1024..65534
        if (web != null && (!webWanted || previous.webPort != next.webPort)) {
            web?.stop()
            web = null
            webClients.value = null
            setPreview(web = false)
        }
        if (webWanted && web == null) {
            val created = RemoteControlWeb(next.webPort, target, ::asset, { setPreview(web = it) })
            web = created
            webClients.value = created.clients
            created.start()
        }

        val assistantWanted = active && next.assistant && password && next.assistantPort in 1024..65535
        if (_assistant.value != null && (!assistantWanted || previous.assistantPort != next.assistantPort || previous.password != next.password)) {
            bridge?.stop()
            bridge = null
            _assistant.value?.stop()
            _assistant.value = null
        }
        if (assistantWanted && _assistant.value == null) {
            _assistant.value = RemoteControlAssistant(next.assistantPort, next.password, onAssistantError).also { it.start() }
        }
        val bridgeWanted = assistantWanted && next.relay && isWebSocketUrl(next.relayUrl) && next.bridgeId.isNotEmpty()
        if (bridge != null && (!bridgeWanted || previous.relayUrl != next.relayUrl || previous.bridgeId != next.bridgeId)) {
            bridge?.stop()
            bridge = null
        }
        if (bridgeWanted && bridge == null) {
            bridge = RemoteControlBridge(http, next.relayUrl.trim().trimEnd('/'), next.bridgeId, "ws://127.0.0.1:${next.assistantPort}").also {
                it.start()
            }
        }
    }

    @Synchronized
    fun stateChanged(state: RemoteState) {
        val changes = state.changesFrom(sent) ?: return
        sent = state
        streamer?.stateChanged(changes)
        web?.stateChanged(changes)
    }

    private fun asset(path: String): ByteArray? = runCatching { context.assets.open("remote/$path").use { it.readBytes() } }.getOrNull()

    @Synchronized
    private fun setPreview(streamer: Boolean = streamerPreview, web: Boolean = webPreview) {
        streamerPreview = streamer
        webPreview = web
        val wanted = streamer || web
        if (wanted && previewJob == null) {
            previewJob = scope.launch {
                while (isActive) {
                    thumbnail(PREVIEW_WIDTH) { bitmap -> if (bitmap != null) scope.launch { send(bitmap) } }
                    delay(1000)
                }
            }
        } else if (!wanted) {
            previewJob?.cancel()
            previewJob = null
        }
    }

    private fun send(bitmap: Bitmap) {
        val jpeg = ByteArrayOutputStream(32 * 1024).also { bitmap.compress(Bitmap.CompressFormat.JPEG, 60, it) }.toByteArray()
        bitmap.recycle()
        synchronized(this) {
            if (streamerPreview) streamer?.sendPreview(jpeg)
            if (webPreview) web?.sendPreview(jpeg)
        }
    }

    private companion object {
        const val PREVIEW_WIDTH = 640
    }
}
