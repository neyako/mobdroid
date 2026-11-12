package com.opirl.app.engine.chat

enum class ChatPlatform { TWITCH, YOUTUBE, KICK }

sealed interface ChatSegment {
    data class Text(val text: String) : ChatSegment

    data class Emote(val name: String, val url: String) : ChatSegment
}

data class ChatMessage(
    val platform: ChatPlatform,
    val id: String?,
    val userId: String?,
    val user: String,
    val userColor: Int?,
    val segments: List<ChatSegment>,
    val timestampMs: Long,
    val isAction: Boolean = false,
    val isBroadcaster: Boolean = false,
    val isModerator: Boolean = false,
    val isSubscriber: Boolean = false,
) {
    val text: String
        get() = segments.joinToString("") {
            when (it) {
                is ChatSegment.Text -> it.text
                is ChatSegment.Emote -> it.name
            }
        }
}

interface ChatListener {
    fun onConnected()

    fun onDisconnected(reason: String)

    fun onMessage(message: ChatMessage)

    fun onMessageDeleted(messageId: String)

    fun onUserMessagesDeleted(userId: String)

    fun onChannelId(id: String) = Unit
}

interface ChatClient {
    val canSend: Boolean

    fun start()

    fun stop()

    fun send(text: String, onResult: (error: String?) -> Unit)
}
