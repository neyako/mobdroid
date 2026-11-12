package com.opirl.app.chat

import com.opirl.app.engine.alerts.AlertEvent
import com.opirl.app.engine.chat.ChatClient
import com.opirl.app.engine.chat.ChatListener
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ChatLine(val key: Long, val message: ChatMessage)

sealed interface ChatEvent {
    class Added(val message: ChatMessage) : ChatEvent

    class Deleted(val platform: ChatPlatform, val messageId: String) : ChatEvent

    class UserDeleted(val platform: ChatPlatform, val userId: String) : ChatEvent
}

fun applyChatEvents(lines: List<ChatLine>, events: List<ChatEvent>, nextKey: () -> Long, limit: Int): List<ChatLine> {
    val result = ArrayList(lines)
    for (event in events) {
        when (event) {
            is ChatEvent.Added -> result += ChatLine(nextKey(), event.message)
            is ChatEvent.Deleted -> result.removeAll {
                it.message.platform == event.platform && it.message.id == event.messageId
            }
            is ChatEvent.UserDeleted -> result.removeAll {
                it.message.platform == event.platform && it.message.userId == event.userId
            }
        }
    }
    return if (result.size > limit) result.subList(result.size - limit, result.size).toList() else result
}

class ChatController(
    scope: CoroutineScope,
    private val createClients: ((ChatPlatform) -> ChatListener) -> List<Pair<ChatPlatform, ChatClient>>,
    private val transform: (ChatMessage) -> ChatMessage,
    private val onChannelId: (ChatPlatform, String) -> Unit,
    private val onMessage: (ChatMessage) -> Unit,
) {
    private val _lines = MutableStateFlow<List<ChatLine>>(emptyList())
    val lines: StateFlow<List<ChatLine>> = _lines.asStateFlow()
    private val _connected = MutableStateFlow<Map<ChatPlatform, Boolean>>(emptyMap())
    val connected: StateFlow<Map<ChatPlatform, Boolean>> = _connected.asStateFlow()
    private val _canSend = MutableStateFlow<List<ChatPlatform>>(emptyList())
    val canSend: StateFlow<List<ChatPlatform>> = _canSend.asStateFlow()
    private val _alerts = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val alerts: SharedFlow<AlertEvent> = _alerts
    private val pending = ConcurrentLinkedQueue<ChatEvent>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var clients = emptyList<Pair<ChatPlatform, ChatClient>>()
    private var generation = 0
    private var key = 0L

    init {
        scope.launch(Dispatchers.Default) {
            for (unused in wake) {
                delay(150)
                val events = generateSequence { pending.poll() }.toList()
                _lines.update { applyChatEvents(it, events, { ++key }, 200) }
            }
        }
    }

    @Synchronized
    fun start() {
        stop()
        val current = ++generation
        clients = createClients { platform ->
            object : ChatListener {
                override fun onConnected() = setConnected(current, platform, true)

                override fun onDisconnected(reason: String) = setConnected(current, platform, false)

                override fun onMessage(message: ChatMessage) {
                    if (current != generation) return
                    val shown = transform(message)
                    pending += ChatEvent.Added(shown)
                    wake.trySend(Unit)
                    this@ChatController.onMessage(shown)
                }

                override fun onMessageDeleted(messageId: String) {
                    if (current != generation) return
                    pending += ChatEvent.Deleted(platform, messageId)
                    wake.trySend(Unit)
                }

                override fun onUserMessagesDeleted(userId: String) {
                    if (current != generation) return
                    pending += ChatEvent.UserDeleted(platform, userId)
                    wake.trySend(Unit)
                }

                override fun onAlert(event: AlertEvent) {
                    if (current == generation) _alerts.tryEmit(event)
                }

                override fun onChannelId(id: String) {
                    if (current == generation) this@ChatController.onChannelId(platform, id)
                }
            }
        }
        _connected.value = clients.associate { it.first to false }
        _canSend.value = clients.filter { it.second.canSend }.map { it.first }
        clients.forEach { it.second.start() }
    }

    @Synchronized
    fun stop() {
        generation++
        val stopping = clients
        clients = emptyList()
        _connected.value = emptyMap()
        _canSend.value = emptyList()
        stopping.forEach { runCatching { it.second.stop() } }
    }

    @Synchronized
    fun send(platform: ChatPlatform, text: String, onResult: (String?) -> Unit) {
        val client = clients.firstOrNull { it.first == platform && it.second.canSend }?.second
        if (client == null) onResult("Not logged in") else client.send(text, onResult)
    }

    private fun setConnected(current: Int, platform: ChatPlatform, value: Boolean) {
        if (current == generation) _connected.update { it + (platform to value) }
    }
}
