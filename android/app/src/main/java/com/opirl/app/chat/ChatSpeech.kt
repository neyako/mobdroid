package com.opirl.app.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatSegment
import com.opirl.app.engine.chat.tts.VoiceService
import java.io.File
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RemoteVoice(val service: VoiceService, val voiceId: String)

class ChatSpeech(private val context: Context, private val onError: (String) -> Unit) {
    private val queue = ArrayBlockingQueue<String>(10)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private var tts: TextToSpeech? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var ready = false

    @Volatile
    private var spoken: CountDownLatch? = null

    @Volatile
    var voice: RemoteVoice? = null

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == (worker != null)) return
        if (!enabled) {
            worker?.interrupt()
            worker = null
            queue.clear()
            ready = false
            tts?.shutdown()
            tts = null
            return
        }
        tts = TextToSpeech(context) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    spoken?.countDown()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    spoken?.countDown()
                }
            })
            ready = true
        }
        worker = Thread(::run, "chat-speech").apply {
            isDaemon = true
            start()
        }
    }

    fun say(text: String) {
        if (worker != null) queue.offer(text)
    }

    private fun run() {
        var failure: String? = null
        try {
            while (true) {
                val text = queue.take()
                val remote = voice
                if (remote == null) {
                    speak(text)
                    continue
                }
                val audio = try {
                    remote.service.speech(remote.voiceId, text)
                } catch (e: IOException) {
                    if (Thread.currentThread().isInterrupted) return
                    if (e.message != failure) onError(e.message.orEmpty())
                    failure = e.message
                    continue
                }
                failure = null
                play(audio)
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun speak(text: String) {
        val engine = synchronized(this) { tts } ?: return
        if (!ready) return
        val done = CountDownLatch(1)
        spoken = done
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, "chat") == TextToSpeech.SUCCESS) done.await(60, TimeUnit.SECONDS)
    }

    private fun play(audio: ByteArray) {
        val file = File(context.cacheDir, "speech")
        val done = CountDownLatch(1)
        val player = MediaPlayer()
        try {
            file.writeBytes(audio)
            player.setAudioAttributes(attributes)
            player.setOnCompletionListener { done.countDown() }
            player.setOnErrorListener { _, _, _ ->
                done.countDown()
                true
            }
            player.setDataSource(file.path)
            player.prepare()
            player.start()
            done.await(60, TimeUnit.SECONDS)
        } catch (_: IOException) {
        } catch (_: IllegalStateException) {
        } finally {
            player.release()
        }
    }
}

fun spokenText(message: ChatMessage, names: Boolean, said: String = "%1\$s said %2\$s"): String? {
    val text = message.segments.filterIsInstance<ChatSegment.Text>().joinToString(" ") { it.text }
        .split(Regex("\\s+")).filterNot { it.startsWith("http://") || it.startsWith("https://") }
        .joinToString(" ").trim()
    if (text.isEmpty() || text.startsWith("!")) return null
    return if (names) said.format(message.user, text) else text
}
