package com.opirl.app.chat

import com.opirl.app.engine.chat.ChatListener
import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.settings.Logins
import com.opirl.app.settings.StreamProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import okhttp3.OkHttpClient

class ChatClientsTest {
    private val listener = object : ChatListener {
        override fun onConnected() = Unit

        override fun onDisconnected(reason: String) = Unit

        override fun onMessage(message: ChatMessage) = Unit

        override fun onMessageDeleted(messageId: String) = Unit

        override fun onUserMessagesDeleted(userId: String) = Unit
    }

    private fun platforms(profile: StreamProfile) =
        createChatClients(profile, Logins(), OkHttpClient()) { listener }.map { it.first }

    @Test
    fun `a half typed channel is skipped instead of failing`() {
        assertEquals(listOf(ChatPlatform.KICK), platforms(StreamProfile(youtubeChannel = "@", kickChannel = "someone")))
    }

    @Test
    fun `every platform with a channel gets a client`() {
        assertEquals(
            listOf(ChatPlatform.TWITCH, ChatPlatform.YOUTUBE, ChatPlatform.KICK),
            platforms(StreamProfile(twitchChannel = "a", youtubeChannel = "@b", kickChannel = "c")),
        )
    }
}
