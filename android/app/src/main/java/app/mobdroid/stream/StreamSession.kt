package app.mobdroid.stream

import app.mobdroid.engine.media.AudioFormat
import app.mobdroid.engine.media.EncodedFrame
import app.mobdroid.engine.media.MediaSink
import app.mobdroid.engine.media.VideoFormat
import app.mobdroid.engine.net.Publisher
import app.mobdroid.engine.net.PublisherListener
import app.mobdroid.engine.net.PublisherStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class LiveState { OFFLINE, CONNECTING, LIVE, RECONNECTING }

class StreamSession(
    private val scope: CoroutineScope,
    private val createPublisher: (PublisherListener) -> Publisher,
    private val requestKeyFrame: () -> Unit,
) : MediaSink {
    private val _state = MutableStateFlow(LiveState.CONNECTING)
    val state: StateFlow<LiveState> = _state
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    @Volatile
    var publisher: Publisher? = null
        private set

    @Volatile
    private var waitingForKeyFrame = true

    @Volatile
    private var videoFormat: VideoFormat? = null

    @Volatile
    private var audioFormat: AudioFormat? = null
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            var attempt = 0
            while (true) {
                val events = Channel<String?>(Channel.UNLIMITED)
                val next = try {
                    createPublisher(object : PublisherListener {
                        override fun onConnected() {
                            events.trySend(null)
                        }

                        override fun onDisconnected(reason: String) {
                            events.trySend(reason)
                        }
                    })
                } catch (e: Exception) {
                    _lastError.value = describeError(e.message ?: e.toString())
                    _state.value = LiveState.RECONNECTING
                    delay(BACKOFF_MS.last())
                    continue
                }
                try {
                    synchronized(formatLock) {
                        videoFormat?.let(next::setVideoFormat)
                        audioFormat?.let(next::setAudioFormat)
                        waitingForKeyFrame = true
                        publisher = next
                    }
                    next.start()
                    requestKeyFrame()
                    var reason = events.receive()
                    if (reason == null) {
                        attempt = 0
                        requestKeyFrame()
                        _state.value = LiveState.LIVE
                        _lastError.value = null
                        reason = events.receive()
                    }
                    _lastError.value = reason?.let(::describeError)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    _lastError.value = describeError(e.message ?: e.toString())
                } finally {
                    synchronized(formatLock) { publisher = null }
                    runCatching { next.stop() }
                }
                _state.value = LiveState.RECONNECTING
                delay(BACKOFF_MS[minOf(attempt++, BACKOFF_MS.lastIndex)])
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = LiveState.OFFLINE
    }

    fun stats(): PublisherStats = publisher?.stats() ?: PublisherStats()

    private val formatLock = Any()

    override fun setVideoFormat(format: VideoFormat): Unit = synchronized(formatLock) {
        videoFormat = format
        publisher?.setVideoFormat(format)
    }

    override fun setAudioFormat(format: AudioFormat): Unit = synchronized(formatLock) {
        audioFormat = format
        publisher?.setAudioFormat(format)
    }

    override fun writeVideo(frame: EncodedFrame) {
        val p = publisher ?: return
        if (waitingForKeyFrame) {
            if (!frame.isKeyFrame) return
            waitingForKeyFrame = false
        }
        p.writeVideo(frame)
    }

    override fun writeAudio(frame: EncodedFrame) {
        publisher?.writeAudio(frame)
    }

    private companion object {
        val BACKOFF_MS = longArrayOf(500, 1000, 2000, 3000, 5000)
    }
}

fun describeError(reason: String): String {
    val lower = reason.lowercase()
    return when {
        "econnrefused" in lower || "connection refused" in lower -> "Server refused the connection"
        "unknownhost" in lower || "unable to resolve" in lower || "no address" in lower -> "Server not found"
        "timed out" in lower || "timeout" in lower -> "Connection timed out"
        "enetunreach" in lower || "network is unreachable" in lower -> "No network"
        "econnreset" in lower || "broken pipe" in lower || "connection reset" in lower -> "Connection dropped"
        else -> reason.lineSequence().first().take(120)
    }
}
