package com.opirl.app.chat

import com.opirl.app.engine.chat.ChatMessage
import com.opirl.app.engine.chat.ChatPlatform
import com.opirl.app.engine.chat.ChatSegment
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatEventsTest {
    private var key = 0L

    private fun message(id: String, userId: String, platform: ChatPlatform = ChatPlatform.TWITCH) =
        ChatMessage(platform, id, userId, userId, null, listOf(ChatSegment.Text(id)), 0)

    private fun apply(lines: List<ChatLine>, vararg events: ChatEvent, limit: Int = 200) =
        applyChatEvents(lines, events.toList(), { ++key }, limit)

    @Test
    fun `a deleted message and a banned user's messages are removed on their own platform only`() {
        val lines = apply(
            emptyList(),
            ChatEvent.Added(message("1", "ann")),
            ChatEvent.Added(message("2", "bob")),
            ChatEvent.Added(message("3", "ann")),
            ChatEvent.Added(message("2", "bob", ChatPlatform.KICK)),
            ChatEvent.Deleted(ChatPlatform.TWITCH, "2"),
            ChatEvent.UserDeleted(ChatPlatform.TWITCH, "ann"),
        )
        assertEquals(listOf(ChatPlatform.KICK to "2"), lines.map { it.message.platform to it.message.id })
    }

    @Test
    fun `only the newest lines are kept`() {
        val lines = apply(emptyList(), *(1..5).map { ChatEvent.Added(message("$it", "ann")) }.toTypedArray(), limit = 3)
        assertEquals(listOf("3", "4", "5"), lines.map { it.message.id })
    }
}
