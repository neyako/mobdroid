package com.opirl.app.widgets

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
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

fun <T> currentSession(sessions: List<T>, own: (T) -> Boolean, playing: (T) -> Boolean): T? {
    val others = sessions.filterNot(own)
    return others.firstOrNull(playing) ?: others.firstOrNull()
}

class NowPlaying(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()
    private val _allowed = MutableStateFlow(false)
    val allowed: StateFlow<Boolean> = _allowed.asStateFlow()
    private var wanted = false
    private var watched = emptyList<Watched>()
    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { watch(it.orEmpty()) }

    private inner class Watched(val controller: MediaController) : MediaController.Callback() {
        var metadata: MediaMetadata? = null
        var playing = false
        var cover: Bitmap? = null

        fun read() {
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
            take(controller.metadata)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            take(metadata)
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            playing = state?.state == PlaybackState.STATE_PLAYING
            publish()
        }

        private fun take(next: MediaMetadata?) {
            metadata = next
            val art = (
                next?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                    ?: next?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: next?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                )?.let(::bounded)
            if (art == null || cover?.sameAs(art) != true) cover = art
        }
    }

    init {
        context.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(LISTENERS),
            false,
            object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = sync()
            },
        )
        handler.post(::sync)
    }

    fun setEnabled(enabled: Boolean) {
        handler.post {
            if (wanted != enabled) {
                wanted = enabled
                sync()
            }
        }
    }

    private fun sync() {
        val allowed = Settings.Secure.getString(context.contentResolver, LISTENERS)
            ?.split(':')
            ?.contains(component.flattenToString()) == true
        _allowed.value = allowed
        manager.removeOnActiveSessionsChangedListener(sessions)
        watch(
            if (wanted && allowed) {
                runCatching {
                    manager.addOnActiveSessionsChangedListener(sessions, component, handler)
                    manager.getActiveSessions(component)
                }.getOrElse {
                    manager.removeOnActiveSessionsChangedListener(sessions)
                    emptyList()
                }
            } else {
                emptyList()
            },
        )
    }

    private fun watch(next: List<MediaController>) {
        watched.forEach { it.controller.unregisterCallback(it) }
        watched = next.map { controller -> Watched(controller).also { controller.registerCallback(it, handler) } }
        watched.forEach { it.read() }
        publish()
    }

    private fun publish() {
        val session = currentSession(watched, { it.controller.packageName == context.packageName }, { it.playing })
        val metadata = session?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (session == null || metadata == null || title.isNullOrBlank()) {
            _track.value = null
            return
        }
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val current = _track.value
        if (current == null || current.title != title || current.artist != artist || current.cover !== session.cover || current.playing != session.playing) {
            _track.value = Track(title, artist, session.cover, session.playing)
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
        const val LISTENERS = "enabled_notification_listeners"
    }
}
