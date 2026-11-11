package app.mobdroid.widgets

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaListener : NotificationListenerService()

class Track(val title: String, val artist: String, val cover: Bitmap?, val playing: Boolean)

class NowPlaying(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()
    private var wanted = false
    private var listening = false
    private var controllers = emptyList<MediaController>()
    private var art: Bitmap? = null
    private var cover: Bitmap? = null
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()

        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
    }

    fun setEnabled(enabled: Boolean) {
        handler.post {
            wanted = enabled
            sync()
        }
    }

    fun refresh() {
        handler.post(::sync)
    }

    private fun sync() {
        val allowed = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?.split(':')
            ?.contains(component.flattenToString()) == true
        _allowed.value = allowed
        val listen = wanted && allowed
        if (listen == listening) return
        listening = listen
        if (listen) {
            runCatching {
                manager.addOnActiveSessionsChangedListener(sessions, component, handler)
                watch(manager.getActiveSessions(component))
            }.onFailure { listening = false }
        } else {
            manager.removeOnActiveSessionsChangedListener(sessions)
            watch(emptyList())
        }
    }

    private fun watch(next: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = next.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        publish()
    }

    private fun publish() {
        val controller = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: controllers.firstOrNull()
        val metadata = controller?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (metadata == null || title.isNullOrBlank()) {
            art = null
            cover = null
            _track.value = null
            return
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val source = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (source !== art) {
            art = source
            cover = source?.let(::bounded)
        }
        val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        val current = _track.value
        if (current == null || current.title != title || current.artist != artist || current.cover !== cover || current.playing != playing) {
            _track.value = Track(title, artist, cover, playing)
        }
    }

    private fun bounded(source: Bitmap): Bitmap? = runCatching {
        val scale = COVER_SIZE.toFloat() / maxOf(source.width, source.height)
        val software = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) else source
        if (scale >= 1f) {
            software.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            Bitmap.createScaledBitmap(software, (source.width * scale).toInt().coerceAtLeast(1), (source.height * scale).toInt().coerceAtLeast(1), true)
        }
    }.getOrNull()

    private companion object {
        const val COVER_SIZE = 256
    }
}
