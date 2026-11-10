package app.mobdroid.chat

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import app.mobdroid.engine.chat.ChatMessage
import app.mobdroid.engine.chat.ChatSegment
import java.util.concurrent.atomic.AtomicInteger

class ChatSpeech(private val context: Context) {
    private var tts: TextToSpeech? = null
    private val queued = AtomicInteger()

    @Volatile
    private var ready = false

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        if (enabled == (tts != null)) return
        if (!enabled) {
            ready = false
            tts?.shutdown()
            tts = null
            queued.set(0)
            return
        }
        tts = TextToSpeech(context) { status ->
            val engine = tts ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) return@TextToSpeech
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    queued.decrementAndGet()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    queued.decrementAndGet()
                }
            })
            ready = true
        }
    }

    fun say(message: ChatMessage, names: Boolean) {
        val engine = tts ?: return
        if (!ready || queued.get() >= 10) return
        val text = spokenText(message, names) ?: return
        queued.incrementAndGet()
        if (engine.speak(text, TextToSpeech.QUEUE_ADD, null, "chat") != TextToSpeech.SUCCESS) queued.decrementAndGet()
    }
}

fun spokenText(message: ChatMessage, names: Boolean): String? {
    val text = message.segments.filterIsInstance<ChatSegment.Text>().joinToString(" ") { it.text }
        .split(Regex("\\s+")).filterNot { it.startsWith("http://") || it.startsWith("https://") }
        .joinToString(" ").trim()
    if (text.isEmpty()) return null
    return if (names) "${message.user} said $text" else text
}
