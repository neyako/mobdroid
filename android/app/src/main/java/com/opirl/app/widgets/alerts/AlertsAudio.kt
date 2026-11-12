package com.opirl.app.widgets.alerts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import java.io.File

private const val TAG = "Alerts"
private const val SPEECH_RATE = 0.85f
private const val SPEECH_PITCH = 0.8f

internal class AlertSound(val asset: String?, val file: File?)

internal class AlertsAudio(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val speechToken = Any()
    private var player: MediaPlayer? = null
    private var speech: TextToSpeech? = null
    private var speechReady = false
    private var waiting: Pair<String, Long>? = null

    fun play(sound: AlertSound) {
        handler.post {
            player?.release()
            player = null
            val created = runCatching { create(sound) }.onFailure { Log.w(TAG, "Alert sound failed", it) }.getOrNull()
            player = created
        }
    }

    fun speak(text: String, delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        handler.post {
            handler.removeCallbacksAndMessages(speechToken)
            if (speech == null) speech = createSpeech()
            if (speechReady) schedule(text, at) else waiting = text to at
        }
    }

    fun release() {
        handler.post {
            handler.removeCallbacksAndMessages(speechToken)
            player?.release()
            player = null
            speech?.shutdown()
            speech = null
            speechReady = false
            waiting = null
        }
    }

    private fun create(sound: AlertSound): MediaPlayer {
        val created = MediaPlayer()
        try {
            created.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            )
            val asset = sound.asset
            if (asset != null) {
                context.assets.openFd(asset).use { created.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            } else {
                created.setDataSource(sound.file!!.path)
            }
            created.setOnPreparedListener { it.start() }
            created.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            created.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                true
            }
            created.prepareAsync()
        } catch (e: Exception) {
            created.release()
            throw e
        }
        return created
    }

    private fun createSpeech(): TextToSpeech {
        lateinit var engine: TextToSpeech
        engine = TextToSpeech(context) { status ->
            handler.post {
                if (speech !== engine) return@post
                if (status != TextToSpeech.SUCCESS) {
                    speech = null
                    engine.shutdown()
                    return@post
                }
                engine.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                engine.setSpeechRate(SPEECH_RATE)
                engine.setPitch(SPEECH_PITCH)
                speechReady = true
                waiting?.let { (text, at) -> schedule(text, at) }
                waiting = null
            }
        }
        return engine
    }

    private fun schedule(text: String, at: Long) {
        handler.postAtTime({ speech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alert") }, speechToken, at)
    }
}
